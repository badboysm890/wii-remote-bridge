package dev.zenitsu.motebridge;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * One screen, four steps to a working Wii Remote in Dolphin: Shizuku, pairing, the bridge, Dolphin's
 * mapping. Underneath, a live test shows the pointer, motion, buttons and rumble exactly as games get them.
 */
public class MainActivity extends Activity {
    private static final int BG = 0xFF0E1116, CARD = 0xFF161B22, TEXT = 0xFFE6EDF3, DIM = 0xFF8B949E,
            OK = 0xFF3FB950, WARN = 0xFFD29922, BAD = 0xFFF85149, ACCENT = 0xFF1F6FEB;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Step shizuku, remote, bridge, dolphin;
    private TestView test;
    private TextView pressed, log;
    private CheckBox autostart;
    private Pairing pairing;
    private final Set<String> held = new TreeSet<>();
    private final Runnable refresher = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 2000); }
    };
    private final Runnable onShell = this::refresh;

    /** A numbered step: title, status line, one action button. */
    private final class Step {
        final TextView status;
        final Button action;
        Step(LinearLayout parent, String number, String title) {
            LinearLayout card = card(parent);
            TextView t = text(number + "  " + title, 20, TEXT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(t);
            status = text("", 15, DIM);
            status.setPadding(0, dp(4), 0, dp(8));
            card.addView(status);
            action = new Button(MainActivity.this);
            action.setAllCaps(false);
            card.addView(action, new LinearLayout.LayoutParams(-2, -2));
        }
        void set(String s, int color, String button, Runnable onClick) {
            status.setText(s);
            status.setTextColor(color);
            action.setVisibility(button == null ? View.GONE : View.VISIBLE);
            if (button != null) { action.setText(button); action.setOnClickListener(v -> onClick.run()); }
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("motebridge", MODE_PRIVATE);
        Shell.init(this);
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS}, 1);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(root);
        TextView title = text("Wii Remote Bridge", 28, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = text("No DolphinBar needed. Pair your Wii Remotes here and this app sets up Dolphin for you: "
                + "pointer, motion, Nunchuk, Classic Controller, rumble, up to 4 players. No root.", 15, DIM);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        shizuku = new Step(root, "1", "Shizuku");
        remote = new Step(root, "2", "Pair the Wii Remote");
        bridge = new Step(root, "3", "Start the bridge");
        autostart = new CheckBox(this);
        autostart.setText("Start automatically after a restart");
        autostart.setTextColor(TEXT);
        autostart.setChecked(prefs.getBoolean("autostart", true));
        autostart.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("autostart", on).apply());
        ((LinearLayout) bridge.action.getParent()).addView(autostart);
        dolphin = new Step(root, "4", "Dolphin (set up automatically)");

        LinearLayout testCard = card(root);
        TextView tt = text("Test", 20, TEXT);
        tt.setTypeface(Typeface.DEFAULT_BOLD);
        testCard.addView(tt);
        TextView th = text("Point at the sensor bar, tilt, shake and press buttons.", 15, DIM);
        testCard.addView(th);
        test = new TestView(this);
        testCard.addView(test, new LinearLayout.LayoutParams(-1, dp(260)));
        pressed = text("Buttons: none", 16, TEXT);
        testCard.addView(pressed);
        Button rumble = new Button(this);
        rumble.setAllCaps(false);
        rumble.setText("Test rumble");
        rumble.setOnClickListener(v -> rumble());
        testCard.addView(rumble, new LinearLayout.LayoutParams(-2, -2));
        log = text("", 12, DIM);
        log.setTypeface(Typeface.MONOSPACE);
        testCard.addView(log);

        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        Shell.listen(onShell);
        Shell.connect(this);
        handler.post(refresher);
    }

    @Override protected void onPause() {
        Shell.unlisten(onShell);
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (pairing != null) pairing.stop();
        super.onDestroy();
    }

    // ---- status ----

    private void refresh() {
        switch (Shell.state()) {
            case NO_SHIZUKU:
                shizuku.set("Shizuku gives this app the access it needs without root. Install it, start it with "
                        + "Wireless debugging, then come back.", BAD, installed("moe.shizuku.privileged.api")
                        ? "Open Shizuku" : "Get Shizuku", this::openShizuku);
                break;
            case NEEDS_PERMISSION:
                shizuku.set("Shizuku is running. Allow this app to use it.", WARN, "Allow", Shell::requestPermission);
                break;
            case CONNECTING:
                shizuku.set("Connecting…", WARN, null, null);
                break;
            case READY:
                shizuku.set("Ready", OK, null, null);
                break;
        }
        IShell s = Shell.get();
        boolean paired = Pairing.paired(this);
        if (s == null) {
            remote.set(paired ? "Paired" : "Not paired yet", paired ? OK : DIM, paired ? "Pair again" : "Pair",
                    this::pair);
            bridge.set("Waiting for Shizuku", DIM, null, null);
            dolphin.set("Waiting for Shizuku", DIM, null, null);
            return;
        }
        Shell.async(sh -> {
            String[] r = new String[6];
            r[0] = sh.bridgeRunning() ? "1" : "0";
            r[1] = sh.run("ls /sys/bus/hid/drivers/ 2>/dev/null | grep -x wiimote; "
                    + "grep -l 'Nintendo Wii Remote' /sys/class/input/*/name 2>/dev/null | head -1; "
                    + "dmesg 2>/dev/null | grep -c 'wiimote.*failed with error -12'");
            r[2] = String.join(" ", Dolphin.packages(sh));
            r[3] = sh.bridgeLog();
            r[5] = sh.extensions();
            Dolphin.setUpIfNeeded(this, sh);
            r[4] = r[2].isEmpty() ? "" : sh.run("grep -A1 '^\\[Wiimote1\\]' " + Dolphin.configDir(r[2].split(" ")[0])
                    + "/WiimoteNew.ini 2>/dev/null | grep -o 'Android/[0-9]*/Wii Remote[ 0-9]*'");
            return r;
        }, r -> { if (r != null) show(paired, r); });
    }

    private void show(boolean paired, String[] r) {
        boolean running = r[0].equals("1");
        String sys = r[1];
        boolean driver = sys.contains("wiimote"), connected = sys.contains("/sys/class/input/");
        boolean outOfMemory = !sys.trim().endsWith("0") && !connected;
        if (!driver) {
            remote.set("This device's system has no Wii Remote driver (hid-wiimote), so it cannot be used here.",
                    BAD, null, null);
        } else if (connected) {
            int battery = remoteBattery();
            remote.set(battery < 0 ? "Connected" : "Connected · battery " + battery + "%"
                    + (battery <= 15 ? " (low: replace the batteries)" : ""), battery >= 0 && battery <= 15 ? WARN : OK,
                    "Pair another", this::pair);
        } else if (outOfMemory && paired) {
            remote.set("The Wii Remote connected but the system could not start its driver (memory is too "
                    + "fragmented). Restart this device, then press a button on the remote.", BAD, "Pair again", this::pair);
        } else if (paired) {
            remote.set("Paired. Press any button on the Wii Remote to connect.", WARN, "Pair again", this::pair);
        } else {
            remote.set("Not paired yet", DIM, "Pair", this::pair);
        }
        InputDevice c = Dolphin.controller(this);
        java.util.List<InputDevice> pads = Dolphin.controllers(this);
        if (running && c != null) {
            bridge.set("Running: " + attachments(r[5], pads.size()), OK, "Restart", this::startBridge);
        } else if (running) {
            bridge.set("Running, waiting for the Wii Remote", WARN, "Restart", this::startBridge);
        } else {
            bridge.set("Stopped", DIM, "Start", this::startBridge);
        }
        if (r[2].isEmpty()) {
            dolphin.set("Dolphin is not installed.", DIM, "Get Dolphin", () -> open(
                    "https://play.google.com/store/apps/details?id=org.dolphinemu.dolphinemu"));
        } else {
            String want = c == null ? null : Dolphin.qualifier(c);
            boolean done = want != null && r[4].trim().equals(want);
            dolphin.set(done ? "Wii Remote 1 uses the bridge. Start a game." : "Not set up yet (this closes Dolphin if it is open)",
                    done ? OK : DIM, c == null ? null : (done ? "Set up again" : "Set up"), this::setUpDolphin);
        }
        String[] lines = r[3].split("\n");
        StringBuilder tail = new StringBuilder();
        for (int i = Math.max(0, lines.length - 4); i < lines.length; i++) tail.append(lines[i]).append('\n');
        log.setText(tail.toString().trim());
    }

    // ---- actions ----

    private void pair() {
        if (pairing != null) pairing.stop();
        Shell.async(sh -> sh.run("settings get secure bluetooth_address").trim(), addr -> {
            String host = addr != null && addr.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}") ? addr : null;
            pairing = new Pairing(this, host, msg -> runOnUiThread(() -> remote.status.setText(msg)));
            pairing.start();
        });
    }

    private void startBridge() {
        bridge.set("Starting…", WARN, null, null);
        String exe = Shell.bridgeExecutable(this);
        Shell.async(sh -> {
            boolean ok = sh.startBridge(exe);
            Thread.sleep(1500);
            Dolphin.refreshDevice(this, sh);
            return ok;
        }, ok -> refresh());
    }

    private void setUpDolphin() {
        dolphin.set("Setting up…", WARN, null, null);
        Shell.async(sh -> Dolphin.setUp(this, sh), msg -> {
            dolphin.status.setText(msg == null ? "Failed" : msg);
            handler.postDelayed(this::refresh, 1500);
        });
    }

    /** Lowest battery among connected remotes, as the Wii Remote driver reports it, or -1. */
    private int remoteBattery() {
        android.hardware.input.InputManager im = getSystemService(android.hardware.input.InputManager.class);
        int lowest = -1;
        for (int id : im.getInputDeviceIds()) {
            InputDevice d = im.getInputDevice(id);
            if (d == null || !d.getName().equals("Nintendo Wii Remote")) continue;
            android.hardware.BatteryState b = d.getBatteryState();
            if (!b.isPresent()) continue;
            int v = Math.round(b.getCapacity() * 100);
            lowest = lowest < 0 ? v : Math.min(lowest, v);
        }
        return lowest;
    }

    /** Buzzes every remote, one after another, so each player can tell which one is theirs. */
    private void rumble() {
        java.util.List<InputDevice> pads = Dolphin.controllers(this);
        for (int i = 0; i < pads.size(); i++) {
            Vibrator v = pads.get(i).getVibratorManager().getDefaultVibrator();
            handler.postDelayed(() -> v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)), i * 600L);
        }
    }

    /** "player 1: Wii Remote + Nunchuk + MotionPlus" per line, from the bridge's status. */
    private static String attachments(String status, int pads) {
        StringBuilder b = new StringBuilder();
        for (String line : status.trim().split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 2) continue;
            b.append(b.length() == 0 ? "" : "\n").append("player ").append(f[0]).append(": Wii Remote")
             .append(f[1].equals("nunchuk") ? " + Nunchuk" : f[1].equals("classic") ? " + Classic Controller" : "")
             .append(line.contains("mplus") ? " + MotionPlus" : "");
        }
        return b.length() == 0 ? pads + " controller(s), waiting for the Wii Remote" : b.toString();
    }

    private static String names(java.util.List<InputDevice> pads) {
        StringBuilder b = new StringBuilder();
        for (InputDevice d : pads) b.append(b.length() == 0 ? "" : ", ").append(d.getName());
        return b.toString();
    }

    private void openShizuku() {
        Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (i != null) startActivity(i);
        else open("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api");
    }

    private void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
    }

    private boolean installed(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    // ---- live test ----

    private boolean fromRemote(InputDevice d) {
        return d != null && d.getVendorId() == Dolphin.VENDOR && d.getProductId() == Dolphin.PRODUCT;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (!fromRemote(e.getDevice())) return super.dispatchKeyEvent(e);
        String name = label(e.getKeyCode());
        int pl = Dolphin.player(e.getDevice());
        if (Dolphin.controllers(this).size() > 1) name = "P" + pl + ":" + name;
        if (e.getAction() == KeyEvent.ACTION_DOWN) held.add(name); else held.remove(name);
        pressed.setText("Buttons: " + (held.isEmpty() ? "none" : String.join(" ", held)));
        return true;   // the test eats the remote's buttons (B would otherwise mean Back)
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (!fromRemote(e.getDevice())) return super.dispatchGenericMotionEvent(e);
        test.update(e);
        return true;
    }

    private static String label(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_Y: return "1";
            case KeyEvent.KEYCODE_BUTTON_X: return "2";
            case KeyEvent.KEYCODE_BUTTON_START: return "+";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "−";
            case KeyEvent.KEYCODE_BUTTON_MODE: return "Home";
            case KeyEvent.KEYCODE_DPAD_UP: return "↑";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "↓";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "←";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "→";
            case KeyEvent.KEYCODE_BUTTON_L1: return "C";
            case KeyEvent.KEYCODE_BUTTON_R1: return "Z";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "Shake";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "Nunchuk-shake";
            case KeyEvent.KEYCODE_BUTTON_1: return "CC-A";
            case KeyEvent.KEYCODE_BUTTON_2: return "CC-B";
            case KeyEvent.KEYCODE_BUTTON_3: return "CC-X";
            case KeyEvent.KEYCODE_BUTTON_4: return "CC-Y";
            case KeyEvent.KEYCODE_BUTTON_5: return "CC-ZL";
            case KeyEvent.KEYCODE_BUTTON_6: return "CC-ZR";
            case KeyEvent.KEYCODE_BUTTON_7: return "CC-L";
            case KeyEvent.KEYCODE_BUTTON_8: return "CC-R";
            case KeyEvent.KEYCODE_BUTTON_9: return "CC-+";
            case KeyEvent.KEYCODE_BUTTON_10: return "CC-−";
            case KeyEvent.KEYCODE_BUTTON_11: return "CC-Home";
            case KeyEvent.KEYCODE_BUTTON_12: return "CC-↑";
            case KeyEvent.KEYCODE_BUTTON_13: return "CC-↓";
            case KeyEvent.KEYCODE_BUTTON_14: return "CC-←";
            case KeyEvent.KEYCODE_BUTTON_15: return "CC-→";
            default: return KeyEvent.keyCodeToString(key).replace("KEYCODE_", "");
        }
    }

    /** Pointer dot (IR), remote tilt bars (acceleration) and the Nunchuk stick. */
    private final class TestView extends View {
        private float px, py, ax, ay, az, sx, sy;
        private boolean seen;
        private long irAt;   // last time the pointer moved, i.e. the camera saw the sensor bar
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        TestView(Context c) { super(c); }

        void update(MotionEvent e) {
            float nx = e.getAxisValue(MotionEvent.AXIS_RX), ny = e.getAxisValue(MotionEvent.AXIS_RY);
            if (nx != px || ny != py) irAt = System.currentTimeMillis();
            px = nx; py = ny;
            ax = e.getAxisValue(MotionEvent.AXIS_Z) * 4; ay = e.getAxisValue(MotionEvent.AXIS_RZ) * 4;
            az = e.getAxisValue(MotionEvent.AXIS_RUDDER) * 4;
            sx = e.getAxisValue(MotionEvent.AXIS_X); sy = e.getAxisValue(MotionEvent.AXIS_Y);   // Nunchuk / Classic left stick
            seen = true;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), screenW = w * 0.62f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setColor(0xFF30363D);
            c.drawRect(dp(4), dp(8), screenW, h - dp(8), p);
            p.setStyle(Paint.Style.FILL); p.setColor(DIM); p.setTextSize(dp(12));
            boolean bar = System.currentTimeMillis() - irAt < 1500;
            p.setColor(bar ? OK : WARN);
            c.drawText(bar ? "Pointer: sensor bar in view" : "Pointer: no sensor bar in view (place it above or below the screen)",
                    dp(10), dp(26), p);
            postInvalidateDelayed(500);
            if (seen && bar) {
                float x = dp(4) + (px + 1) / 2 * (screenW - dp(4)), y = dp(8) + (py + 1) / 2 * (h - dp(16));
                p.setColor(0x551F6FEB); c.drawCircle(x, y, dp(18), p);
                p.setColor(Color.WHITE); c.drawCircle(x, y, dp(6), p);
            }
            float bx = screenW + dp(16), bw = w - bx - dp(4);
            bar(c, bx, dp(30), bw, "Tilt X", ax);
            bar(c, bx, dp(80), bw, "Tilt Y", ay);
            bar(c, bx, dp(130), bw, "Up/down", az);
            p.setColor(DIM); c.drawText("Nunchuk / Classic stick", bx, dp(190), p);
            p.setStyle(Paint.Style.STROKE); p.setColor(0xFF30363D);
            float cx = bx + dp(36), cy = dp(226), r = dp(28);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.FILL); p.setColor(ACCENT);
            c.drawCircle(cx + sx * r, cy + sy * r, dp(7), p);
        }

        private void bar(Canvas c, float x, float y, float w, String label, float g) {
            p.setColor(DIM); c.drawText(String.format(Locale.US, "%s  %+.1f g", label, g), x, y, p);
            p.setColor(0xFF30363D); c.drawRect(x, y + dp(8), x + w, y + dp(20), p);
            float mid = x + w / 2, end = mid + Math.max(-1f, Math.min(1f, g / 2)) * w / 2;
            p.setColor(ACCENT); c.drawRect(Math.min(mid, end), y + dp(8), Math.max(mid, end), y + dp(20), p);
        }
    }

    // ---- views ----

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackgroundColor(CARD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        parent.addView(c, lp);
        return c;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        return t;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
