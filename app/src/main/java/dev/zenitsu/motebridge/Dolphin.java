package dev.zenitsu.motebridge;

import android.content.Context;
import android.hardware.input.InputManager;
import android.util.Base64;
import android.view.InputDevice;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Points Dolphin's emulated Wii Remotes 1-4 at the bridge's controllers, with every part mapped: buttons,
 * the sensor bar (raw IR camera dots, plus a pointer fallback), real accelerometer data, MotionPlus gyro,
 * shake, Nunchuk, Classic Controller and rumble. See native/wiibridge.c for the axis layout.
 */
final class Dolphin {
    static final int VENDOR = 0x057e, PRODUCT = 0x7f02;
    static final String NAME = "Wii Remote";

    // The bridge's extra axis codes (Linux ABS_*) that Android numbers as AXIS_GENERIC_n.
    static final int HAT1X = 0x12, HAT1Y = 0x13, HAT2X = 0x14, HAT2Y = 0x15, HAT3X = 0x16, HAT3Y = 0x17,
            GYRO_X = 0x28, GYRO_Y = 0x29, GYRO_Z = 0x2a, CC_RY = 0x2b, CC_LT = 0x2c, CC_RT = 0x2d;

    /** A line only the current mapping writes, to recognise mappings from older versions. */
    static final String CURRENT = "IMUAccelerometer/Up = (`Axis 20+` - `Axis 20-`)";

    static final double ACCEL = 4 * 9.80665;              // m/s^2 at full axis (4 g)
    static final double GYRO = 2000 * Math.PI / 180;      // rad/s at full axis (2000 deg/s)

    /** The bridge's controllers ("Wii Remote 1" to "Wii Remote 4") as Android sees them, in player order. */
    static List<InputDevice> controllers(Context c) {
        InputManager im = c.getSystemService(InputManager.class);
        List<InputDevice> list = new ArrayList<>();
        for (int id : im.getInputDeviceIds()) {
            InputDevice d = im.getInputDevice(id);
            if (d != null && d.getVendorId() == VENDOR && d.getProductId() == PRODUCT) list.add(d);
        }
        list.sort(Comparator.comparing(InputDevice::getName));
        return list;
    }

    /** Player 1's controller, or null. */
    static InputDevice controller(Context c) {
        List<InputDevice> l = controllers(c);
        return l.isEmpty() ? null : l.get(0);
    }

    static int player(InputDevice d) {
        String n = d.getName();
        return n.matches(NAME + " [1-4]") ? n.charAt(n.length() - 1) - '0' : 1;
    }

    /** Dolphin names Android devices by controller number: "Android/<n>/<name>". */
    static String qualifier(InputDevice d) { return "Android/" + d.getControllerNumber() + "/" + d.getName(); }

    /** Installed Dolphin builds (official, plus forks that keep the org.dolphinemu prefix). */
    static String[] packages(IShell s) throws Exception {
        String out = s.run("pm list packages | grep -o 'org\\.dolphinemu[A-Za-z0-9_.]*'").trim();
        return out.isEmpty() ? new String[0] : out.split("\\s+");
    }

    static String configDir(String pkg) { return "/sdcard/Android/data/" + pkg + "/files/Config"; }

    /** What each player has attached, from the bridge: player -> "nunchuk" / "classic" / "none" (+ " mplus"). */
    static Map<Integer, String> extensions(IShell s) throws Exception {
        Map<Integer, String> m = new HashMap<>();
        for (String line : s.extensions().split("\n")) {
            String[] f = line.trim().split("\\s+", 2);
            if (f.length == 2 && f[0].matches("[1-4]")) m.put(Integer.parseInt(f[0]), f[1]);
        }
        return m;
    }

    /** Writes the mapping into every installed Dolphin. Returns a line per Dolphin describing what happened. */
    static String setUp(Context c, IShell s) throws Exception {
        List<InputDevice> pads = controllers(c);
        if (pads.isEmpty()) return "Start the bridge first: Dolphin needs the controllers' numbers.";
        String[] pkgs = packages(s);
        if (pkgs.length == 0) return "Dolphin is not installed.";
        Map<Integer, String> ext = extensions(s);
        StringBuilder report = new StringBuilder();
        for (String pkg : pkgs) {
            String dir = configDir(pkg);
            if (s.run("[ -d " + dir + " ] && echo yes").trim().isEmpty()) {
                report.append(pkg).append(": open Dolphin once, then set up again.\n");
                continue;
            }
            s.run("am force-stop " + pkg);   // Dolphin keeps its own copy of the settings while it runs
            String ini = s.run("cat " + dir + "/WiimoteNew.ini 2>/dev/null");
            String updated = ini.startsWith("error") ? "" : ini;
            for (InputDevice d : pads) {
                Map<Integer, Integer> axes = genericAxes(s.axisMap(d.getName()));
                String e = ext.getOrDefault(player(d), "none");
                updated = withWiimote(updated, player(d), section(player(d), qualifier(d), e, axes));
            }
            write(s, dir + "/WiimoteNew.ini", updated);
            report.append(pkg).append(": ").append(pads.size() == 1 ? "Wii Remote 1 is set up"
                    : pads.size() + " Wii Remotes are set up").append('\n');
        }
        return report.toString().trim();
    }

    /**
     * Sets Dolphin up by itself when a connected remote is not mapped yet (first run, or a new player),
     * but never while Dolphin is running: that would close the game.
     */
    static void setUpIfNeeded(Context c, IShell s) throws Exception {
        List<InputDevice> pads = controllers(c);
        if (pads.isEmpty()) return;
        for (String pkg : packages(s)) {
            if (!s.run("pidof " + pkg).trim().isEmpty()) continue;
            String ini = s.run("cat " + configDir(pkg) + "/WiimoteNew.ini 2>/dev/null");
            if (s.run("[ -d " + configDir(pkg) + " ] && echo yes").trim().isEmpty()) continue;
            // Not mapped yet, or mapped by an older version of this app (a different axis layout).
            boolean missing = !ini.contains(CURRENT);
            for (InputDevice d : pads) if (!ini.contains("/" + d.getName() + "\n")) missing = true;
            if (missing) { setUp(c, s); return; }
        }
    }

    /** After the controller numbers change (reboot, other pads), keep Dolphin pointing at them. */
    static void refreshDevice(Context c, IShell s) throws Exception {
        for (InputDevice d : controllers(c)) {
            s.run("for f in /sdcard/Android/data/org.dolphinemu*/files/Config/WiimoteNew.ini; do [ -f \"$f\" ] && "
                    + "sed -i 's#^Device = Android/[0-9]*/" + d.getName() + "$#Device = " + qualifier(d) + "#' \"$f\"; done");
        }
    }

    /** "40=1,45=2" -> raw axis code -> Dolphin axis number (AXIS_GENERIC_1 is Android axis 32). */
    static Map<Integer, Integer> genericAxes(String map) {
        Map<Integer, Integer> m = new HashMap<>();
        for (String pair : map.split(",")) {
            String[] kv = pair.split("=");
            if (kv.length == 2) m.put(Integer.parseInt(kv[0].trim()), 31 + Integer.parseInt(kv[1].trim()));
        }
        return m;
    }

    private static void write(IShell s, String path, String content) throws Exception {
        String b64 = Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        s.run("echo '" + b64 + "' | base64 -d > '" + path + ".tmp' && mv '" + path + ".tmp' '" + path + "'");
    }

    /** Replaces (or adds) the [WiimoteN] section, keeping the others untouched. */
    static String withWiimote(String ini, int n, String section) {
        String head = "[Wiimote" + n + "]";
        int a = ini.indexOf(head);
        if (a < 0) return ini.isEmpty() || ini.endsWith("\n") ? ini + section : ini + "\n" + section;
        int b = ini.indexOf("\n[", a + 1);
        return ini.substring(0, a) + section + (b < 0 ? "" : ini.substring(b + 1));
    }

    // ---- expressions ----

    private static String axis(int n, char dir) { return "`Axis " + n + dir + "`"; }
    /** A two-sided Android axis as -1..1. */
    private static String signed(int n) { return "(" + axis(n, '+') + " - " + axis(n, '-') + ")"; }
    /** A one-sided (0..1) axis carrying a signed value as (v + 1) / 2. */
    private static String signed01(int n) { return "(" + axis(n, '+') + " * 2 - 1)"; }
    private static String times(String e, double k) { return e + " * " + String.format(Locale.US, "%.4f", k); }

    /**
     * One player's section. Axis numbers: X 0, Y 1, Z 11, RX 12, RY 13, RZ 14, THROTTLE 19, RUDDER 20,
     * WHEEL 21, GAS 22, BRAKE 23; generic ones come from the device (map g).
     */
    static String section(int n, String device, String ext, Map<Integer, Integer> g) {
        StringBuilder s = new StringBuilder();
        String extension = ext.startsWith("nunchuk") ? "Nunchuk" : ext.startsWith("classic") ? "Classic" : "None";
        s.append("[Wiimote").append(n).append("]\n")
         .append("Device = ").append(device).append('\n')
         .append("Source = 1\n")
         .append("Extension = ").append(extension).append('\n')
         .append("Buttons/A = `Button A`\nButtons/B = `Button B`\nButtons/1 = `Button Y`\nButtons/2 = `Button X`\n")
         .append("Buttons/- = Select\nButtons/+ = Start\nButtons/Home = Mode\n")
         .append("D-Pad/Up = Up\nD-Pad/Down = Down\nD-Pad/Left = Left\nD-Pad/Right = Right\n")
         // Pointer fallback (used if pass-through is switched off in Dolphin).
         .append("IR/Up = `Axis 13-`\nIR/Down = `Axis 13+`\nIR/Left = `Axis 12-`\nIR/Right = `Axis 12+`\n")
         .append("IR/Relative Input = False\n");
        // The camera's own dots: Dolphin then sees the sensor bar exactly as the remote does.
        if (g.containsKey(HAT1X) && g.containsKey(HAT3Y)) {
            int[][] dots = {{HAT1X, HAT1Y, HAT2X}, {HAT2Y, HAT3X, HAT3Y}};
            s.append("IRPassthrough/Enabled = True\n");
            for (int i = 0; i < 2; i++) {
                String o = "IRPassthrough/Object " + (i + 1);
                s.append(o).append(" X = ").append(axis(g.get(dots[i][0]), '+')).append('\n')
                 .append(o).append(" Y = ").append(axis(g.get(dots[i][1]), '+')).append('\n')
                 .append(o).append(" Size = ").append(axis(g.get(dots[i][2]), '+')).append('\n');
            }
        }
        // Dolphin's accelerometer: x = Left - Right, y = Backward - Forward, z = Up - Down, in m/s^2.
        s.append("Shake/X = `Button L3`\nShake/Y = `Button L3`\nShake/Z = `Button L3`\n")
         .append("IMUAccelerometer/Left = ").append(times(signed(11), ACCEL)).append('\n')
         .append("IMUAccelerometer/Backward = ").append(times(signed(14), ACCEL)).append('\n')
         .append("IMUAccelerometer/Up = ").append(times(signed(20), ACCEL)).append('\n');
        // MotionPlus: Dolphin's gyro x = Pitch Down - Pitch Up, y = Roll Left - Roll Right, z = Yaw Left -
        // Yaw Right, in rad/s; the remote reports yaw, roll, pitch.
        if (ext.contains("mplus") && g.containsKey(GYRO_X) && g.containsKey(GYRO_Y) && g.containsKey(GYRO_Z)) {
            s.append("Extension/Attach MotionPlus = True\n")
             .append("IMUGyroscope/Pitch Down = ").append(times(signed01(g.get(GYRO_Z)), GYRO)).append('\n')
             .append("IMUGyroscope/Roll Left = ").append(times(signed01(g.get(GYRO_Y)), GYRO)).append('\n')
             .append("IMUGyroscope/Yaw Left = ").append(times(signed01(g.get(GYRO_X)), GYRO)).append('\n');
        }
        s.append("Rumble/Motor = `Motor 0`\n");
        // Nunchuk
        s.append("Nunchuk/Buttons/C = `Button L1`\nNunchuk/Buttons/Z = `Button R1`\n")
         .append("Nunchuk/Stick/Up = `Axis 1-`\nNunchuk/Stick/Down = `Axis 1+`\n")
         .append("Nunchuk/Stick/Left = `Axis 0-`\nNunchuk/Stick/Right = `Axis 0+`\n")
         .append("Nunchuk/Shake/X = `Button R3`\nNunchuk/Shake/Y = `Button R3`\nNunchuk/Shake/Z = `Button R3`\n")
         .append("Nunchuk/IMUAccelerometer/Left = ").append(times(signed01(19), ACCEL)).append('\n')
         .append("Nunchuk/IMUAccelerometer/Backward = ").append(times(signed01(22), ACCEL)).append('\n')
         .append("Nunchuk/IMUAccelerometer/Up = ").append(times(signed01(23), ACCEL)).append('\n');
        // Classic Controller (its buttons are Android BUTTON_1..15)
        String[][] cc = {{"Buttons/A", "1"}, {"Buttons/B", "2"}, {"Buttons/X", "3"}, {"Buttons/Y", "4"},
                {"Buttons/ZL", "5"}, {"Buttons/ZR", "6"}, {"Triggers/L", "7"}, {"Triggers/R", "8"},
                {"Buttons/+", "9"}, {"Buttons/-", "10"}, {"Buttons/Home", "11"}, {"D-Pad/Up", "12"},
                {"D-Pad/Down", "13"}, {"D-Pad/Left", "14"}, {"D-Pad/Right", "15"}};
        for (String[] b : cc) s.append("Classic/").append(b[0]).append(" = `Button ").append(b[1]).append("`\n");
        s.append("Classic/Left Stick/Up = `Axis 1-`\nClassic/Left Stick/Down = `Axis 1+`\n")
         .append("Classic/Left Stick/Left = `Axis 0-`\nClassic/Left Stick/Right = `Axis 0+`\n")
         .append("Classic/Right Stick/Left = `Axis 21-`\nClassic/Right Stick/Right = `Axis 21+`\n");
        if (g.containsKey(CC_RY)) {
            // Sent with up negative: Up = 1 - 2a; Down stays unbound so the two do not add up twice.
            s.append("Classic/Right Stick/Up = (1 - ").append(axis(g.get(CC_RY), '+')).append(" * 2)\n");
        }
        if (g.containsKey(CC_LT)) s.append("Classic/Triggers/L-Analog = ").append(axis(g.get(CC_LT), '+')).append('\n');
        if (g.containsKey(CC_RT)) s.append("Classic/Triggers/R-Analog = ").append(axis(g.get(CC_RT), '+')).append('\n');
        return s.toString();
    }
}
