// wiibridge: the whole Wii Remote as one Android game controller, without root.
//
// The kernel's hid-wiimote driver splits a remote into several input devices (buttons, IR camera,
// accelerometer, Nunchuk). Android only adopts the one with buttons, so apps such as Dolphin never see
// the pointer or the motion. This program (run as the adb/Shizuku shell user, which may read
// /dev/input and write /dev/uinput) grabs them all and re-emits them as a single virtual gamepad:
//
//   buttons   A=SOUTH B=EAST 1=WEST 2=NORTH  +=START -=SELECT Home=MODE  D-pad=arrow keys (Android drops BTN_DPAD_*)  C=TL Z=TR
//   ABS_X/Y   Nunchuk stick                     ABS_RX/RY  IR pointer (sensor bar)
//   ABS_Z/RZ/THROTTLE  remote acceleration x/y/z (+-1 = 4 g)   ABS_RUDDER/WHEEL/GAS  Nunchuk acceleration x/y/z
//   THUMBL/THUMBR      remote / Nunchuk shake
//
// and forwards rumble from Android (InputDevice vibrator, e.g. Dolphin) back to the remote.
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <math.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

enum { KEYS, IR, ACCEL, NUNCHUK, NDEV };
static const char *NAMES[NDEV] = {
    "Nintendo Wii Remote", "Nintendo Wii Remote IR", "Nintendo Wii Remote Accelerometer",
    "Nintendo Wii Remote Nunchuk"};
static int fds[NDEV] = {-1, -1, -1, -1};

#define AXMAX 32767
static int u = -1;               // the virtual controller
static int rumble_id = -1;       // effect uploaded to the real remote
static int ff_len[16];           // replay length (ms) of effects Android uploaded to us

static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }

static void emit(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = type; ev.code = code; ev.value = value;
    if (write(u, &ev, sizeof ev) < 0) { /* uinput full: drop */ }
}

static int clampi(double v) { return v > AXMAX ? AXMAX : v < -AXMAX ? -AXMAX : (int) v; }

static int open_named(const char *want) {
    DIR *d = opendir("/dev/input");
    if (!d) return -1;
    struct dirent *e;
    int found = -1;
    while ((e = readdir(d)) && found < 0) {
        if (strncmp(e->d_name, "event", 5)) continue;
        char path[64], name[128] = "";
        snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDWR | O_NONBLOCK);
        if (fd < 0) fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        ioctl(fd, EVIOCGNAME(sizeof name), name);
        if (!strcmp(name, want)) found = fd; else close(fd);
    }
    closedir(d);
    return found;
}

static void rumble_setup(void) {
    struct ff_effect e;
    memset(&e, 0, sizeof e);
    e.type = FF_RUMBLE; e.id = -1; e.u.rumble.strong_magnitude = 0xffff; e.replay.length = 100;
    rumble_id = ioctl(fds[KEYS], EVIOCSFF, &e) < 0 ? -1 : e.id;
}

/** Turns the real remote's motor on (for ms, 0 = until stopped) or off. */
static void rumble(int on, int ms) {
    if (fds[KEYS] < 0 || rumble_id < 0) return;
    if (on) {
        struct ff_effect e;
        memset(&e, 0, sizeof e);
        e.type = FF_RUMBLE; e.id = rumble_id; e.u.rumble.strong_magnitude = 0xffff;
        e.replay.length = ms > 0 ? ms : 0xffff;
        ioctl(fds[KEYS], EVIOCSFF, &e);
    }
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = EV_FF; ev.code = rumble_id; ev.value = on;
    if (write(fds[KEYS], &ev, sizeof ev) < 0) { /* remote gone */ }
}

static void make_controller(void) {
    // UI_DEV_SETUP/UI_ABS_SETUP, not the legacy uinput_user_dev write: Retroid's kernel rewrites legacy
    // devices into a copy of its own pad (its vendor id, six axes, no rumble).
    u = open("/dev/uinput", O_RDWR | O_NONBLOCK);
    if (u < 0) { perror("uinput"); exit(1); }
    ioctl(u, UI_SET_EVBIT, EV_KEY);
    int keys[] = {BTN_SOUTH, BTN_EAST, BTN_WEST, BTN_NORTH, BTN_START, BTN_SELECT, BTN_MODE, BTN_TL, BTN_TR,
                  KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, BTN_THUMBL, BTN_THUMBR};
    for (unsigned i = 0; i < sizeof keys / sizeof *keys; i++) ioctl(u, UI_SET_KEYBIT, keys[i]);
    ioctl(u, UI_SET_EVBIT, EV_ABS);
    int axes[] = {ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ, ABS_THROTTLE, ABS_RUDDER, ABS_WHEEL, ABS_GAS};
    for (unsigned i = 0; i < sizeof axes / sizeof *axes; i++) {
        struct uinput_abs_setup a;
        memset(&a, 0, sizeof a);
        a.code = axes[i]; a.absinfo.minimum = -AXMAX; a.absinfo.maximum = AXMAX;
        ioctl(u, UI_SET_ABSBIT, axes[i]);
        ioctl(u, UI_ABS_SETUP, &a);
    }
    ioctl(u, UI_SET_EVBIT, EV_FF);
    ioctl(u, UI_SET_FFBIT, FF_RUMBLE);
    struct uinput_setup st;
    memset(&st, 0, sizeof st);
    snprintf(st.name, sizeof st.name, "Wii Remote");
    st.id.bustype = BUS_BLUETOOTH; st.id.vendor = 0x057e; st.id.product = 0x7f02; st.id.version = 1;
    st.ff_effects_max = 16;
    if (ioctl(u, UI_DEV_SETUP, &st) < 0 || ioctl(u, UI_DEV_CREATE) < 0) { perror("uinput create"); exit(1); }
}

/** Android's vibrator talks to our virtual controller; play it on the real remote. */
static void handle_ff(void) {
    struct input_event ev;
    while (read(u, &ev, sizeof ev) == sizeof ev) {
        if (ev.type == EV_UINPUT && ev.code == UI_FF_UPLOAD) {
            struct uinput_ff_upload up;
            memset(&up, 0, sizeof up);
            up.request_id = ev.value;
            ioctl(u, UI_BEGIN_FF_UPLOAD, &up);
            if (up.effect.id >= 0 && up.effect.id < 16) ff_len[up.effect.id] = up.effect.replay.length;
            up.retval = 0;
            ioctl(u, UI_END_FF_UPLOAD, &up);
        } else if (ev.type == EV_UINPUT && ev.code == UI_FF_ERASE) {
            struct uinput_ff_erase er;
            memset(&er, 0, sizeof er);
            er.request_id = ev.value;
            ioctl(u, UI_BEGIN_FF_ERASE, &er);
            er.retval = 0;
            ioctl(u, UI_END_FF_ERASE, &er);
        } else if (ev.type == EV_FF && ev.code < 16) {
            rumble(ev.value > 0, ev.value > 0 ? ff_len[ev.code] : 0);
        }
    }
}

static int map_key(int code) {
    switch (code) {
        case BTN_A: return BTN_SOUTH;
        case BTN_B: return BTN_EAST;
        case BTN_1: return BTN_WEST;
        case BTN_2: return BTN_NORTH;
        case KEY_NEXT: return BTN_START;        // +
        case KEY_PREVIOUS: return BTN_SELECT;   // -
        case BTN_MODE: return BTN_MODE;         // Home
        case KEY_UP: return KEY_UP;
        case KEY_DOWN: return KEY_DOWN;
        case KEY_LEFT: return KEY_LEFT;
        case KEY_RIGHT: return KEY_RIGHT;
        case BTN_C: return BTN_TL;
        case BTN_Z: return BTN_TR;
        default: return -1;
    }
}

#define G 104.0
static int acc[2][3];          // latest raw acceleration: remote, Nunchuk
static int shaking[2];

/** A shake (remote or Nunchuk) is a button: down past 2.2 g, up again below 1.4 g. */
static void shake(int which) {
    double m = sqrt((double) acc[which][0] * acc[which][0] + (double) acc[which][1] * acc[which][1] +
                    (double) acc[which][2] * acc[which][2]) / G;
    int now_on = shaking[which] ? m > 1.4 : m > 2.2;
    if (now_on != shaking[which]) { shaking[which] = now_on; emit(EV_KEY, which ? BTN_THUMBR : BTN_THUMBL, now_on); }
}

static volatile int running = 1;
static void quit(int s) { (void) s; running = 0; }

int main(void) {
    signal(SIGTERM, quit); signal(SIGINT, quit); signal(SIGHUP, SIG_IGN);
    make_controller();
    int dot[4][2];
    for (int i = 0; i < 4; i++) dot[i][0] = dot[i][1] = 1023;
    double lastScan = 0, sepx = 0, sepy = 0, px = 0, py = 0;
    int haveSep = 0;
    while (running) {
        // (Re)find the remote's parts: it may connect later, drop out, or gain a Nunchuk.
        if (now() - lastScan > 2) {
            lastScan = now();
            for (int i = 0; i < NDEV; i++) if (fds[i] < 0 && (fds[i] = open_named(NAMES[i])) >= 0) {
                fprintf(stderr, "wiibridge: %s connected\n", NAMES[i]);
                // Keep Android from also seeing the raw remote (it would be a second, button-only pad).
                if (i == KEYS) { ioctl(fds[i], EVIOCGRAB, 1); rumble_setup(); rumble(1, 120); }
            }
        }
        struct pollfd p[NDEV + 1];
        for (int i = 0; i < NDEV; i++) { p[i].fd = fds[i]; p[i].events = POLLIN; p[i].revents = 0; }
        p[NDEV].fd = u; p[NDEV].events = POLLIN; p[NDEV].revents = 0;
        if (poll(p, NDEV + 1, 500) < 0 && errno != EINTR) break;
        if (p[NDEV].revents & POLLIN) handle_ff();
        int irMoved = 0, synced = 0;
        for (int i = 0; i < NDEV; i++) {
            if (fds[i] < 0) continue;
            if (p[i].revents & (POLLERR | POLLHUP | POLLNVAL)) {
                fprintf(stderr, "wiibridge: %s gone\n", NAMES[i]);
                close(fds[i]); fds[i] = -1;
                if (i == KEYS) rumble_id = -1;
                continue;
            }
            struct input_event ev;
            while (read(fds[i], &ev, sizeof ev) == sizeof ev) {
                if (ev.type == EV_SYN) { synced = 1; if (i == IR) irMoved = 1; continue; }
                if (ev.type == EV_KEY && ev.value != 2) {
                    int k = map_key(ev.code);
                    if (k >= 0) emit(EV_KEY, k, ev.value);
                } else if (ev.type == EV_ABS && i == IR && ev.code >= ABS_HAT0X && ev.code <= ABS_HAT3Y) {
                    int k = ev.code - ABS_HAT0X;
                    dot[k / 2][k % 2] = ev.value;
                } else if (ev.type == EV_ABS && i == ACCEL) {
                    // Raw acceleration for Dolphin's Accelerometer input: axis +-1 = +-4 g (about 104 raw per g).
                    int k = ev.code - ABS_RX;
                    if (k >= 0 && k < 3) acc[0][k] = ev.value;
                    int code = ev.code == ABS_RX ? ABS_Z : ev.code == ABS_RY ? ABS_RZ : ABS_THROTTLE;
                    emit(EV_ABS, code, clampi(ev.value * (AXMAX / (4 * G))));
                    shake(0);
                } else if (ev.type == EV_ABS && i == NUNCHUK) {
                    if (ev.code == ABS_HAT0X) emit(EV_ABS, ABS_X, clampi(ev.value * (AXMAX / 100.0)));
                    else if (ev.code == ABS_HAT0Y) emit(EV_ABS, ABS_Y, clampi(-ev.value * (AXMAX / 100.0)));
                    else if (ev.code >= ABS_RX && ev.code <= ABS_RZ) {
                        acc[1][ev.code - ABS_RX] = ev.value;
                        int code = ev.code == ABS_RX ? ABS_RUDDER : ev.code == ABS_RY ? ABS_WHEEL : ABS_GAS;
                        emit(EV_ABS, code, clampi(ev.value * (AXMAX / (4 * G))));
                        shake(1);
                    }
                }
            }
        }
        if (irMoved) {
            // The pointer is the middle of the sensor bar's two dots; with one in view, assume the pair
            // kept its spacing. The camera sees the room mirrored, so x is flipped.
            int idx[4], n = 0;
            for (int i = 0; i < 4; i++) if (dot[i][0] < 1023 && dot[i][1] < 1023) idx[n++] = i;
            double cx = -1, cy = -1;
            if (n >= 2) {
                cx = (dot[idx[0]][0] + dot[idx[1]][0]) / 2.0;
                cy = (dot[idx[0]][1] + dot[idx[1]][1]) / 2.0;
                sepx = cx - dot[idx[0]][0]; sepy = cy - dot[idx[0]][1]; haveSep = 1;
            } else if (n == 1 && haveSep) {
                double ax = dot[idx[0]][0] + sepx, bx = dot[idx[0]][0] - sepx;
                cx = (ax - 512) * (ax - 512) < (bx - 512) * (bx - 512) ? ax : bx;
                cy = dot[idx[0]][1] + (cx == ax ? sepy : -sepy);
            }
            if (cx >= 0) {
                double x = 1 - cx / 1023.0 * 2, y = cy / 767.0 * 2 - 1;   // -1..1, right/down positive
                px += (x - px) * 0.5; py += (y - py) * 0.5;
                emit(EV_ABS, ABS_RX, clampi(px * AXMAX));
                emit(EV_ABS, ABS_RY, clampi(py * AXMAX));
            }
        }
        if (synced) emit(EV_SYN, SYN_REPORT, 0);
    }
    if (fds[KEYS] >= 0) ioctl(fds[KEYS], EVIOCGRAB, 0);
    ioctl(u, UI_DEV_DESTROY);
    return 0;
}
