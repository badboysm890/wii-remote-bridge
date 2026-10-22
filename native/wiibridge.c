// wiibridge: real Wii Remotes as Android game controllers, without root. Up to four players.
//
// The kernel's hid-wiimote driver splits each remote into several input devices (buttons, IR camera,
// accelerometer, extensions, MotionPlus). Android only adopts the one with buttons, so apps such as
// Dolphin never see the pointer or the motion. This program (run as the adb/Shizuku shell user, which may
// read /dev/input and write /dev/uinput) groups each remote's parts by its Bluetooth address and re-emits
// them as one virtual gamepad per remote, "Wii Remote 1" to "Wii Remote 4".
//
// Android scales two kinds of axes differently: "centred" ones (X Y Z RX RY RZ RUDDER WHEEL) report -1..1,
// all others 0..1 (Dolphin then only offers their + direction). Signed data on a 0..1 axis is sent as
// (v + 1) / 2 and decoded in Dolphin's mapping as `Axis n+` * 2 - 1. The layout (Android axis numbers):
//
//   X Y (0 1)              extension stick: Nunchuk, or the Classic Controller's left stick
//   RX RY (12 13)          pointer: middle of the sensor bar's dots, -1..1, flipped like the screen
//   Z RZ RUDDER (11 14 20) remote acceleration x y z, +-1 = 4 g
//   WHEEL (21), 0x2b (41)  Classic right stick x, y (signed)
//   THROTTLE GAS BRAKE (19 22 23)  Nunchuk acceleration x y z (signed, 0..1 encoded)
//   HAT1X..HAT3Y (32..37)  raw IR camera dots 1 and 2: x, y, size (0..1; size 0 = not seen)
//   MISC 0x29 0x2a (38 39 40)      MotionPlus angular velocity x y z (signed, +-1 = 2000 deg/s)
//   0x2c 0x2d (42 43)      Classic L and R analog triggers (0..1)
//
//   buttons: remote A=SOUTH B=EAST 1=WEST 2=NORTH +=START -=SELECT Home=MODE D-pad=arrow keys
//            Nunchuk C=TL Z=TR; shake: remote THUMBL, Nunchuk THUMBR
//            Classic A B X Y ZL ZR L R + - Home Up Down Left Right = BTN_0..BTN_0+14 (Android BUTTON_1..15)
//
// Rumble from Android (each controller's vibrator, e.g. Dolphin) goes back to the matching remote.
// A remote keeps its player number for as long as the bridge runs, also across reconnects. The attached
// extension of every player is written to STATUS_FILE so the app can tell Dolphin.
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <math.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

enum { KEYS, IR, ACCEL, NUNCHUK, CLASSIC, MPLUS, NPART };
static const char *PART_NAMES[NPART] = {
    "Nintendo Wii Remote", "Nintendo Wii Remote IR", "Nintendo Wii Remote Accelerometer",
    "Nintendo Wii Remote Nunchuk", "Nintendo Wii Remote Classic Controller", "Nintendo Wii Remote Motion Plus"};
#define STATUS_FILE "/data/local/tmp/wiibridge.status"

// Axes beyond Android's named ones (it numbers them AXIS_GENERIC_1.. in code order).
#define ABS_GYRO_X ABS_MISC
#define ABS_GYRO_Y (ABS_MISC + 1)
#define ABS_GYRO_Z (ABS_MISC + 2)
#define ABS_CC_RY (ABS_MISC + 3)
#define ABS_CC_LT (ABS_MISC + 4)
#define ABS_CC_RT (ABS_MISC + 5)
#define GYRO_SCALE 167.56   // hid-wiimote MotionPlus units per degree/second
#define GYRO_RANGE 2000.0   // degrees/second at full axis

#define MAX_REMOTES 4
#define AXMAX 32767
#define G 104.0   // raw accelerometer units per g

typedef struct {
    int used;
    char addr[32];           // Bluetooth address: the remote's identity across reconnects
    int fd[NPART];
    char node[NPART][16];    // eventN each part was opened from
    int u;                   // its virtual controller
    int rumble_id;           // effect uploaded to the real remote
    int ff_len[16];          // replay length (ms) of effects Android uploaded to us
    int dot[4][2];
    double sepx, sepy, px, py;
    int have_sep;
    int acc[2][3];           // latest raw acceleration: remote, Nunchuk
    int shaking[2];
    struct input_absinfo cc[6];   // Classic Controller stick and trigger ranges (HAT1X..HAT3Y)
} Remote;

static Remote remotes[MAX_REMOTES];
static int status_dirty = 1;

// Pointer: outside games, player 1's sensor-bar pointing drives an Android mouse pointer (hover selects in
// launchers), and B clicks while pointing. Inside Dolphin it is off, so it cannot touch the game.
#define PMAX 32767
static int pointer_u = -1;
static volatile int pointer_on = 0;
static double pointer_seen;     // last time player 1 pointed at the sensor bar
static int screen_w = 1920, screen_h = 1080;
static double aim_x = -1, aim_y = -1, carry_x, carry_y;   // last aim in screen pixels, sub-pixel rest
static int b_as_click;          // the B press in progress went to the pointer (so its release does too)

/** "1 nunchuk mplus" per connected player, for the app (it sets Dolphin's extension to match). */
static void write_status(void) {
    status_dirty = 0;
    FILE *f = fopen(STATUS_FILE ".tmp", "w");
    if (!f) return;
    for (int i = 0; i < MAX_REMOTES; i++) {
        Remote *r = &remotes[i];
        if (!r->used || r->fd[KEYS] < 0) continue;
        fprintf(f, "%d %s%s\n", i + 1, r->fd[NUNCHUK] >= 0 ? "nunchuk" : r->fd[CLASSIC] >= 0 ? "classic" : "none",
                r->fd[MPLUS] >= 0 ? " mplus" : "");
    }
    fclose(f);
    rename(STATUS_FILE ".tmp", STATUS_FILE);
}

static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }

static void emit(Remote *r, int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = type; ev.code = code; ev.value = value;
    if (r->u >= 0 && write(r->u, &ev, sizeof ev) < 0) { /* uinput full: drop */ }
}

static int clampi(double v) { return v > AXMAX ? AXMAX : v < -AXMAX ? -AXMAX : (int) v; }

/** A signed value (-1..1) on a 0..1 axis: (v + 1) / 2. */
static void emit_signed01(Remote *r, int code, double v) {
    if (v > 1) v = 1;
    if (v < -1) v = -1;
    emit(r, EV_ABS, code, (int) ((v + 1) / 2 * AXMAX));
}

static void emit_unit(Remote *r, int code, double v) {
    if (v > 1) v = 1;
    if (v < 0) v = 0;
    emit(r, EV_ABS, code, (int) (v * AXMAX));
}

/** -1..1 from a kernel axis range, centred on its middle. */
static double norm(const struct input_absinfo *a, int v) {
    double mid = (a->maximum + a->minimum) / 2.0, half = (a->maximum - a->minimum) / 2.0;
    return half > 0 ? (v - mid) / half : 0;
}

/** Retroid handhelds replace every new controller with a copy of their own pad (fixed axes, no rumble)
 *  unless this setting is on while the controller is created. Returns whether it was switched on. */
static int retroid_cloning_off(void) {
    FILE *f = popen("settings get system no_create_gamepad_button_layout 2>/dev/null", "r");
    char v[16] = "";
    if (f) { if (!fgets(v, sizeof v, f)) v[0] = 0; pclose(f); }
    if (v[0] != '0') return 0;   // not a Retroid ("null"), or already on
    return system("settings put system no_create_gamepad_button_layout 1") == 0;
}

static void retroid_cloning_back(int was_switched) {
    if (!was_switched) return;
    sleep(2);   // Retroid's mapping service reacts to the new device after it appears
    if (system("settings put system no_create_gamepad_button_layout 0") != 0) { }
}

/** A relative mouse: stock Android only shows a pointer for those (absolute ones are ignored). */
static int make_pointer(void) {
    int u = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (u < 0) return -1;
    ioctl(u, UI_SET_EVBIT, EV_KEY);
    ioctl(u, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(u, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(u, UI_SET_EVBIT, EV_REL);
    ioctl(u, UI_SET_RELBIT, REL_X);
    ioctl(u, UI_SET_RELBIT, REL_Y);
    struct uinput_setup st;
    memset(&st, 0, sizeof st);
    snprintf(st.name, sizeof st.name, "Wii Remote Pointer");
    st.id.bustype = BUS_VIRTUAL; st.id.vendor = 0x057e; st.id.product = 0x7f01;
    if (ioctl(u, UI_DEV_SETUP, &st) < 0 || ioctl(u, UI_DEV_CREATE) < 0) { close(u); return -1; }
    return u;
}

static void pointer_emit(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = type; ev.code = code; ev.value = value;
    if (pointer_u >= 0 && write(pointer_u, &ev, sizeof ev) < 0) { }
}

/** Whether a game (Dolphin) is in front; checked off the input path, every 1.5 s. */
static void *focus_watch(void *unused) {
    (void) unused;
    for (;;) {
        FILE *f = popen("dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp'", "r");
        char line[512] = "";
        if (f) { if (!fgets(line, sizeof line, f)) line[0] = 0; pclose(f); }
        pointer_on = line[0] && !strstr(line, "org.dolphinemu");
        usleep(1500000);
    }
    return NULL;
}

/** The virtual controller for player slot+1. UI_DEV_SETUP, not the legacy uinput_user_dev write:
 *  Retroid's kernel rewrites legacy devices into a copy of its own pad (its vendor id, six axes, no rumble). */
static int make_controller(int slot) {
    int u = open("/dev/uinput", O_RDWR | O_NONBLOCK);
    if (u < 0) { perror("uinput"); return -1; }
    ioctl(u, UI_SET_EVBIT, EV_KEY);
    int keys[] = {BTN_SOUTH, BTN_EAST, BTN_WEST, BTN_NORTH, BTN_START, BTN_SELECT, BTN_MODE, BTN_TL, BTN_TR,
                  KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, BTN_THUMBL, BTN_THUMBR};
    for (unsigned i = 0; i < sizeof keys / sizeof *keys; i++) ioctl(u, UI_SET_KEYBIT, keys[i]);
    for (int k = BTN_0; k < BTN_0 + 15; k++) ioctl(u, UI_SET_KEYBIT, k);   // Classic Controller
    ioctl(u, UI_SET_EVBIT, EV_ABS);
    int centred[] = {ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ, ABS_RUDDER, ABS_WHEEL};
    int unit[] = {ABS_THROTTLE, ABS_GAS, ABS_BRAKE, ABS_HAT1X, ABS_HAT1Y, ABS_HAT2X, ABS_HAT2Y, ABS_HAT3X, ABS_HAT3Y,
                  ABS_GYRO_X, ABS_GYRO_Y, ABS_GYRO_Z, ABS_CC_RY, ABS_CC_LT, ABS_CC_RT};
    for (unsigned i = 0; i < sizeof centred / sizeof *centred + sizeof unit / sizeof *unit; i++) {
        int is_centred = i < sizeof centred / sizeof *centred;
        struct uinput_abs_setup a;
        memset(&a, 0, sizeof a);
        a.code = is_centred ? centred[i] : unit[i - sizeof centred / sizeof *centred];
        a.absinfo.minimum = is_centred ? -AXMAX : 0;
        a.absinfo.maximum = AXMAX;
        if (!is_centred) a.absinfo.value = a.code >= ABS_HAT1X && a.code <= ABS_HAT3Y ? 0 : AXMAX / 2;
        ioctl(u, UI_SET_ABSBIT, a.code);
        ioctl(u, UI_ABS_SETUP, &a);
    }
    ioctl(u, UI_SET_EVBIT, EV_FF);
    ioctl(u, UI_SET_FFBIT, FF_RUMBLE);
    char phys[32];
    snprintf(phys, sizeof phys, "wiibridge/%d", slot + 1);
    ioctl(u, UI_SET_PHYS, phys);
    struct uinput_setup st;
    memset(&st, 0, sizeof st);
    snprintf(st.name, sizeof st.name, "Wii Remote %d", slot + 1);
    st.id.bustype = BUS_BLUETOOTH; st.id.vendor = 0x057e; st.id.product = 0x7f02; st.id.version = slot + 1;
    st.ff_effects_max = 16;
    int retroid = retroid_cloning_off();
    if (ioctl(u, UI_DEV_SETUP, &st) < 0 || ioctl(u, UI_DEV_CREATE) < 0) {
        perror("uinput create");
        close(u);
        retroid_cloning_back(retroid);
        return -1;
    }
    retroid_cloning_back(retroid);
    fprintf(stderr, "wiibridge: created \"Wii Remote %d\"\n", slot + 1);
    return u;
}

static void rumble_setup(Remote *r) {
    struct ff_effect e;
    memset(&e, 0, sizeof e);
    e.type = FF_RUMBLE; e.id = -1; e.u.rumble.strong_magnitude = 0xffff; e.replay.length = 100;
    r->rumble_id = ioctl(r->fd[KEYS], EVIOCSFF, &e) < 0 ? -1 : e.id;
}

/** Turns the real remote's motor on (for ms, 0 = until stopped) or off. */
static void rumble(Remote *r, int on, int ms) {
    if (r->fd[KEYS] < 0 || r->rumble_id < 0) return;
    if (on) {
        struct ff_effect e;
        memset(&e, 0, sizeof e);
        e.type = FF_RUMBLE; e.id = r->rumble_id; e.u.rumble.strong_magnitude = 0xffff;
        e.replay.length = ms > 0 ? ms : 0xffff;
        ioctl(r->fd[KEYS], EVIOCSFF, &e);
    }
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = EV_FF; ev.code = r->rumble_id; ev.value = on;
    if (write(r->fd[KEYS], &ev, sizeof ev) < 0) { /* remote gone */ }
}

/** Android's vibrator talks to the virtual controller; play it on the real remote. */
static void handle_ff(Remote *r) {
    struct input_event ev;
    while (read(r->u, &ev, sizeof ev) == sizeof ev) {
        if (ev.type == EV_UINPUT && ev.code == UI_FF_UPLOAD) {
            struct uinput_ff_upload up;
            memset(&up, 0, sizeof up);
            up.request_id = ev.value;
            ioctl(r->u, UI_BEGIN_FF_UPLOAD, &up);
            if (up.effect.id >= 0 && up.effect.id < 16) r->ff_len[up.effect.id] = up.effect.replay.length;
            up.retval = 0;
            ioctl(r->u, UI_END_FF_UPLOAD, &up);
        } else if (ev.type == EV_UINPUT && ev.code == UI_FF_ERASE) {
            struct uinput_ff_erase er;
            memset(&er, 0, sizeof er);
            er.request_id = ev.value;
            ioctl(r->u, UI_BEGIN_FF_ERASE, &er);
            er.retval = 0;
            ioctl(r->u, UI_END_FF_ERASE, &er);
        } else if (ev.type == EV_FF && ev.code < 16) {
            rumble(r, ev.value > 0, ev.value > 0 ? r->ff_len[ev.code] : 0);
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

static int map_classic(int code) {
    switch (code) {
        case BTN_A: return BTN_0;
        case BTN_B: return BTN_0 + 1;
        case BTN_X: return BTN_0 + 2;
        case BTN_Y: return BTN_0 + 3;
        case BTN_TL2: return BTN_0 + 4;       // ZL
        case BTN_TR2: return BTN_0 + 5;       // ZR
        case BTN_TL: return BTN_0 + 6;        // L
        case BTN_TR: return BTN_0 + 7;        // R
        case KEY_NEXT: return BTN_0 + 8;      // +
        case KEY_PREVIOUS: return BTN_0 + 9;  // -
        case BTN_MODE: return BTN_0 + 10;     // Home
        case KEY_UP: return BTN_0 + 11;
        case KEY_DOWN: return BTN_0 + 12;
        case KEY_LEFT: return BTN_0 + 13;
        case KEY_RIGHT: return BTN_0 + 14;
        default: return -1;
    }
}

/** A shake (remote or Nunchuk) is a button: down past 2.2 g, up again below 1.4 g. */
static void shake(Remote *r, int which) {
    double m = sqrt((double) r->acc[which][0] * r->acc[which][0] + (double) r->acc[which][1] * r->acc[which][1] +
                    (double) r->acc[which][2] * r->acc[which][2]) / G;
    int on = r->shaking[which] ? m > 1.4 : m > 2.2;
    if (on != r->shaking[which]) { r->shaking[which] = on; emit(r, EV_KEY, which ? BTN_THUMBR : BTN_THUMBL, on); }
}

/** A part went away: let go of everything it was holding, so nothing sticks. */
static void release(Remote *r, int part) {
    static const int remote_keys[] = {BTN_SOUTH, BTN_EAST, BTN_WEST, BTN_NORTH, BTN_START, BTN_SELECT, BTN_MODE,
                                      KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, BTN_THUMBL};
    static const int nunchuk_keys[] = {BTN_TL, BTN_TR, BTN_THUMBR};
    if (part == KEYS)
        for (unsigned i = 0; i < sizeof remote_keys / sizeof *remote_keys; i++) emit(r, EV_KEY, remote_keys[i], 0);
    if (part == NUNCHUK || part == CLASSIC) {
        if (part == NUNCHUK)
            for (unsigned i = 0; i < sizeof nunchuk_keys / sizeof *nunchuk_keys; i++) emit(r, EV_KEY, nunchuk_keys[i], 0);
        else
            for (int k = BTN_0; k < BTN_0 + 15; k++) emit(r, EV_KEY, k, 0);
        emit(r, EV_ABS, ABS_X, 0);
        emit(r, EV_ABS, ABS_Y, 0);
        emit(r, EV_ABS, ABS_WHEEL, 0);
        emit_signed01(r, ABS_CC_RY, 0);
        emit_unit(r, ABS_CC_LT, 0);
        emit_unit(r, ABS_CC_RT, 0);
    }
    if (part == MPLUS) {
        emit_signed01(r, ABS_GYRO_X, 0);
        emit_signed01(r, ABS_GYRO_Y, 0);
        emit_signed01(r, ABS_GYRO_Z, 0);
    }
    if (part == IR)
        for (int c = ABS_HAT1X; c <= ABS_HAT3Y; c++) emit(r, EV_ABS, c, 0);   // no dots: sensor bar not seen
    emit(r, EV_SYN, SYN_REPORT, 0);
}

/** The Bluetooth address of the remote an event node belongs to (its HID parent's HID_UNIQ). */
static int remote_address(const char *node, char *out, size_t len) {
    char path[128];
    snprintf(path, sizeof path, "/sys/class/input/%s/device/device/uevent", node);
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char line[160];
    int found = 0;
    while (fgets(line, sizeof line, f)) {
        if (!strncmp(line, "HID_UNIQ=", 9)) {
            line[strcspn(line, "\n")] = 0;
            snprintf(out, len, "%s", line + 9);
            found = out[0] != 0;
        }
    }
    fclose(f);
    if (!found) {   // no address: group by the HID device itself
        snprintf(path, sizeof path, "/sys/class/input/%s/device/device", node);
        char real[160];
        if (realpath(path, real)) { snprintf(out, len, "%s", real); found = 1; }
    }
    return found;
}

static Remote *remote_for(const char *addr) {
    for (int i = 0; i < MAX_REMOTES; i++) if (remotes[i].used && !strcmp(remotes[i].addr, addr)) return &remotes[i];
    for (int i = 0; i < MAX_REMOTES; i++) if (!remotes[i].used) {
        Remote *r = &remotes[i];
        memset(r, 0, sizeof *r);
        r->used = 1;
        snprintf(r->addr, sizeof r->addr, "%s", addr);
        for (int p = 0; p < NPART; p++) r->fd[p] = -1;
        for (int d = 0; d < 4; d++) r->dot[d][0] = r->dot[d][1] = 1023;
        r->rumble_id = -1;
        r->u = make_controller(i);
        return r;
    }
    return NULL;   // more than four remotes
}

static int part_open(const char *node) {
    for (int i = 0; i < MAX_REMOTES; i++)
        for (int p = 0; p < NPART; p++)
            if (remotes[i].used && remotes[i].fd[p] >= 0 && !strcmp(remotes[i].node[p], node)) return 1;
    return 0;
}

/** Finds remotes and parts that appeared since the last look (a remote may connect any time, or gain a Nunchuk). */
static void scan(void) {
    DIR *d = opendir("/dev/input");
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (strncmp(e->d_name, "event", 5) || part_open(e->d_name)) continue;
        char path[64], name[128] = "";
        snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDWR | O_NONBLOCK);
        if (fd < 0) fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        ioctl(fd, EVIOCGNAME(sizeof name), name);
        int part = -1;
        for (int p = 0; p < NPART; p++) if (!strcmp(name, PART_NAMES[p])) part = p;
        char addr[160];
        Remote *r = part >= 0 && remote_address(e->d_name, addr, sizeof addr) ? remote_for(addr) : NULL;
        if (!r || r->fd[part] >= 0) { close(fd); continue; }
        r->fd[part] = fd;
        snprintf(r->node[part], sizeof r->node[part], "%s", e->d_name);
        fprintf(stderr, "wiibridge: player %d (%s): %s connected\n", (int) (r - remotes) + 1, r->addr, name);
        if (part == CLASSIC)
            for (int a = 0; a < 6; a++) ioctl(fd, EVIOCGABS(ABS_HAT1X + a), &r->cc[a]);
        status_dirty = 1;
        if (part == KEYS) {
            // Keep Android from also seeing the raw remote (it would be a second, button-only pad).
            ioctl(fd, EVIOCGRAB, 1);
            rumble_setup(r);
            rumble(r, 1, 120);   // a short buzz: "you are connected"
        }
    }
    closedir(d);
}

static void read_part(Remote *r, int part, int *ir_moved, int *synced) {
    struct input_event ev;
    while (read(r->fd[part], &ev, sizeof ev) == sizeof ev) {
        if (ev.type == EV_SYN) { *synced = 1; if (part == IR) *ir_moved = 1; continue; }
        if (ev.type == EV_KEY && ev.value != 2) {
            if (part == KEYS && ev.code == BTN_B && r == &remotes[0]) {
                // Pointing at the screen outside a game: B clicks. Otherwise B is the controller's B (Back).
                if (ev.value == 1) b_as_click = pointer_on && pointer_u >= 0 && now() - pointer_seen < 1.0;
                if (b_as_click) {
                    pointer_emit(EV_KEY, BTN_LEFT, ev.value);
                    pointer_emit(EV_SYN, SYN_REPORT, 0);
                    if (ev.value == 1) rumble(r, 1, 30);
                    continue;
                }
            }
            int k = part == CLASSIC ? map_classic(ev.code) : map_key(ev.code);
            if (k >= 0) emit(r, EV_KEY, k, ev.value);
        } else if (ev.type != EV_ABS) {
            continue;
        } else if (part == IR && ev.code >= ABS_HAT0X && ev.code <= ABS_HAT3Y) {
            int k = ev.code - ABS_HAT0X;
            r->dot[k / 2][k % 2] = ev.value;
        } else if (part == ACCEL && ev.code >= ABS_RX && ev.code <= ABS_RZ) {
            int k = ev.code - ABS_RX;
            r->acc[0][k] = ev.value;
            int code = k == 0 ? ABS_Z : k == 1 ? ABS_RZ : ABS_RUDDER;
            emit(r, EV_ABS, code, clampi(ev.value * (AXMAX / (4 * G))));
            shake(r, 0);
        } else if (part == NUNCHUK) {
            if (ev.code == ABS_HAT0X) emit(r, EV_ABS, ABS_X, clampi(ev.value * (AXMAX / 100.0)));
            else if (ev.code == ABS_HAT0Y) emit(r, EV_ABS, ABS_Y, clampi(-ev.value * (AXMAX / 100.0)));
            else if (ev.code >= ABS_RX && ev.code <= ABS_RZ) {
                int k = ev.code - ABS_RX;
                r->acc[1][k] = ev.value;
                int code = k == 0 ? ABS_THROTTLE : k == 1 ? ABS_GAS : ABS_BRAKE;
                emit_signed01(r, code, ev.value / (4 * G));
                shake(r, 1);
            }
        } else if (part == CLASSIC && ev.code >= ABS_HAT1X && ev.code <= ABS_HAT3Y) {
            const struct input_absinfo *a = &r->cc[ev.code - ABS_HAT1X];
            double v = norm(a, ev.value);
            switch (ev.code) {
                case ABS_HAT1X: emit(r, EV_ABS, ABS_X, clampi(v * AXMAX)); break;
                case ABS_HAT1Y: emit(r, EV_ABS, ABS_Y, clampi(-v * AXMAX)); break;   // up is negative on Android
                case ABS_HAT2X: emit(r, EV_ABS, ABS_WHEEL, clampi(v * AXMAX)); break;
                case ABS_HAT2Y: emit_signed01(r, ABS_CC_RY, -v); break;
                case ABS_HAT3X: emit_unit(r, ABS_CC_RT, (v + 1) / 2); break;   // R analog
                case ABS_HAT3Y: emit_unit(r, ABS_CC_LT, (v + 1) / 2); break;   // L analog
            }
        } else if (part == MPLUS && ev.code >= ABS_RX && ev.code <= ABS_RZ) {
            int code = ABS_GYRO_X + (ev.code - ABS_RX);
            emit_signed01(r, code, ev.value / GYRO_SCALE / GYRO_RANGE);
        }
    }
}

/** The pointer is the middle of the sensor bar's two dots; with one in view, assume the pair kept its
 *  spacing. The camera sees the room mirrored, so x is flipped. */
static void update_pointer(Remote *r) {
    int idx[4], n = 0;
    for (int i = 0; i < 4; i++) if (r->dot[i][0] < 1023 && r->dot[i][1] < 1023) idx[n++] = i;
    // The camera's own view for Dolphin's IR pass-through: the first two dots, in camera coordinates.
    for (int j = 0; j < 2; j++) {
        int base = ABS_HAT1X + j * 3;
        if (j < n) {
            emit_unit(r, base, r->dot[idx[j]][0] / 1023.0);
            emit_unit(r, base + 1, r->dot[idx[j]][1] / 767.0);
            emit_unit(r, base + 2, 0.3);   // basic IR mode reports no size; a mid-sized dot
        } else {
            emit(r, EV_ABS, base + 2, 0);   // size 0: not seen
        }
    }
    double cx = -1, cy = -1;
    if (n >= 2) {
        cx = (r->dot[idx[0]][0] + r->dot[idx[1]][0]) / 2.0;
        cy = (r->dot[idx[0]][1] + r->dot[idx[1]][1]) / 2.0;
        r->sepx = cx - r->dot[idx[0]][0]; r->sepy = cy - r->dot[idx[0]][1]; r->have_sep = 1;
    } else if (n == 1 && r->have_sep) {
        double ax = r->dot[idx[0]][0] + r->sepx, bx = r->dot[idx[0]][0] - r->sepx;
        cx = (ax - 512) * (ax - 512) < (bx - 512) * (bx - 512) ? ax : bx;
        cy = r->dot[idx[0]][1] + (cx == ax ? r->sepy : -r->sepy);
    }
    if (cx < 0) return;
    double x = 1 - cx / 1023.0 * 2, y = cy / 767.0 * 2 - 1;   // -1..1, right/down positive
    r->px += (x - r->px) * 0.5; r->py += (y - r->py) * 0.5;
    emit(r, EV_ABS, ABS_RX, clampi(r->px * AXMAX));
    emit(r, EV_ABS, ABS_RY, clampi(r->py * AXMAX));
    if (r == &remotes[0]) {
        if (pointer_on) {
            // The camera's edges are hard to reach; use its middle 64% for the whole screen.
            double sx = (r->px * 0.5 + 0.5 - 0.18) / 0.64, sy = (r->py * 0.5 + 0.5 - 0.18) / 0.64;
            if (sx < 0) sx = 0; if (sx > 1) sx = 1; if (sy < 0) sy = 0; if (sy > 1) sy = 1;
            double tx = sx * screen_w, ty = sy * screen_h;
            if (aim_x >= 0 && now() - pointer_seen < 0.5) {
                // Move by how far the aim moved; a long gap (bar out of view) restarts from here.
                double dx = tx - aim_x + carry_x, dy = ty - aim_y + carry_y;
                int ix = (int) dx, iy = (int) dy;
                carry_x = dx - ix; carry_y = dy - iy;
                // Android accelerates fast mouse moves, so the cursor drifts from the aim. Aiming past an
                // edge pushes the cursor into that edge too, which lines the two up again.
                if (sx <= 0) ix -= 64; else if (sx >= 1) ix += 64;
                if (sy <= 0) iy -= 64; else if (sy >= 1) iy += 64;
                if (ix || iy) {
                    pointer_emit(EV_REL, REL_X, ix);
                    pointer_emit(EV_REL, REL_Y, iy);
                    pointer_emit(EV_SYN, SYN_REPORT, 0);
                }
            }
            aim_x = tx; aim_y = ty;
        }
        pointer_seen = now();
    }
}

static volatile int running = 1;
static void quit(int s) { (void) s; running = 0; }

int main(void) {
    signal(SIGTERM, quit); signal(SIGINT, quit); signal(SIGHUP, SIG_IGN);
    setvbuf(stderr, NULL, _IOLBF, 0);
    {
        FILE *f = popen("wm size 2>/dev/null | tail -1", "r");
        char line[96] = "";
        if (f) { if (fgets(line, sizeof line, f)) { char *p = strrchr(line, ' '); int w, h;
            if (p && sscanf(p + 1, "%dx%d", &w, &h) == 2) { screen_w = w > h ? w : h; screen_h = w > h ? h : w; } } pclose(f); }
        int retroid = retroid_cloning_off();
        pointer_u = make_pointer();
        retroid_cloning_back(retroid);
        pthread_t t;
        pthread_create(&t, NULL, focus_watch, NULL);
        pthread_detach(t);
    }
    double last_scan = -10;
    while (running) {
        if (now() - last_scan > 2) { last_scan = now(); scan(); }
        if (status_dirty) write_status();
        struct pollfd p[MAX_REMOTES * (NPART + 1)];
        Remote *owner[MAX_REMOTES * (NPART + 1)];
        int part_of[MAX_REMOTES * (NPART + 1)];
        int n = 0;
        for (int i = 0; i < MAX_REMOTES; i++) {
            Remote *r = &remotes[i];
            if (!r->used) continue;
            for (int k = 0; k <= NPART; k++) {
                int fd = k < NPART ? r->fd[k] : r->u;
                if (fd < 0) continue;
                p[n].fd = fd; p[n].events = POLLIN; p[n].revents = 0;
                owner[n] = r; part_of[n] = k; n++;
            }
        }
        if (poll(p, n, 500) < 0 && errno != EINTR) break;
        for (int i = 0; i < MAX_REMOTES; i++) {
            Remote *r = &remotes[i];
            if (!r->used) continue;
            int ir_moved = 0, synced = 0;
            for (int j = 0; j < n; j++) {
                if (owner[j] != r || !p[j].revents) continue;
                int k = part_of[j];
                if (k == NPART) { handle_ff(r); continue; }
                if (p[j].revents & (POLLERR | POLLHUP | POLLNVAL)) {
                    fprintf(stderr, "wiibridge: player %d: %s gone\n", i + 1, PART_NAMES[k]);
                    close(r->fd[k]); r->fd[k] = -1; r->node[k][0] = 0;
                    if (k == KEYS) r->rumble_id = -1;
                    release(r, k);
                    status_dirty = 1;
                    continue;
                }
                read_part(r, k, &ir_moved, &synced);
            }
            if (ir_moved) update_pointer(r);
            if (synced) emit(r, EV_SYN, SYN_REPORT, 0);
        }
    }
    for (int i = 0; i < MAX_REMOTES; i++) {
        Remote *r = &remotes[i];
        if (!r->used) continue;
        if (r->fd[KEYS] >= 0) ioctl(r->fd[KEYS], EVIOCGRAB, 0);
        if (r->u >= 0) ioctl(r->u, UI_DEV_DESTROY);
    }
    if (pointer_u >= 0) ioctl(pointer_u, UI_DEV_DESTROY);
    return 0;
}
