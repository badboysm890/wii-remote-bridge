package dev.zenitsu.motebridge;

import android.content.Context;
import android.hardware.input.InputManager;
import android.util.Base64;
import android.view.InputDevice;

import java.nio.charset.StandardCharsets;

/**
 * Points Dolphin's emulated Wii Remote 1 at the bridge's controller, with every part mapped: buttons,
 * IR pointer (sensor bar), real accelerometer data, shake, Nunchuk and rumble.
 */
final class Dolphin {
    static final int VENDOR = 0x057e, PRODUCT = 0x7f02;
    static final String NAME = "Wii Remote";

    /** The bridge's controllers ("Wii Remote 1" to "Wii Remote 4") as Android sees them, in player order. */
    static java.util.List<InputDevice> controllers(Context c) {
        InputManager im = c.getSystemService(InputManager.class);
        java.util.List<InputDevice> list = new java.util.ArrayList<>();
        for (int id : im.getInputDeviceIds()) {
            InputDevice d = im.getInputDevice(id);
            if (d != null && d.getVendorId() == VENDOR && d.getProductId() == PRODUCT) list.add(d);
        }
        list.sort(java.util.Comparator.comparing(InputDevice::getName));
        return list;
    }

    /** Player 1's controller, or null. */
    static InputDevice controller(Context c) {
        java.util.List<InputDevice> l = controllers(c);
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

    /** Writes the mapping into every installed Dolphin. Returns a line per Dolphin describing what happened. */
    static String setUp(Context c, IShell s, boolean nunchuk) throws Exception {
        java.util.List<InputDevice> pads = controllers(c);
        if (pads.isEmpty()) return "Start the bridge first: Dolphin needs the controllers' numbers.";
        StringBuilder report = new StringBuilder();
        String[] pkgs = packages(s);
        if (pkgs.length == 0) return "Dolphin is not installed.";
        for (String pkg : pkgs) {
            String dir = configDir(pkg);
            if (s.run("[ -d " + dir + " ] && echo yes").trim().isEmpty()) {
                report.append(pkg).append(": open Dolphin once, then set up again.\n");
                continue;
            }
            s.run("am force-stop " + pkg);   // Dolphin rewrites its config when it exits
            String ini = s.run("cat " + dir + "/WiimoteNew.ini 2>/dev/null");
            String updated = ini.startsWith("error") ? "" : ini;
            for (InputDevice d : pads) updated = withWiimote(updated, player(d), section(player(d), qualifier(d), nunchuk));
            write(s, dir + "/WiimoteNew.ini", updated);
            report.append(pkg).append(": ").append(pads.size() == 1 ? "Wii Remote 1 is set up" : pads.size() + " Wii Remotes are set up").append('\n');
        }
        return report.toString().trim();
    }

    /** After the controller number changes (reboot, other pads), keep Dolphin pointing at it. */
    static void refreshDevice(Context c, IShell s) throws Exception {
        for (InputDevice d : controllers(c)) {
            s.run("for f in /sdcard/Android/data/org.dolphinemu*/files/Config/WiimoteNew.ini; do [ -f \"$f\" ] && "
                    + "sed -i 's#^Device = Android/[0-9]*/" + d.getName() + "$#Device = " + qualifier(d) + "#' \"$f\"; done");
        }
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

    // Axis numbers are Android's MotionEvent axes: X 0, Y 1, Z 11, RX 12, RY 13, RZ 14, THROTTLE 19,
    // RUDDER 20, WHEEL 21, GAS 22. Acceleration axes carry +-4 g; Dolphin's accelerometer input wants
    // m/s^2 (it rests at 9.8), so each is scaled by 4 g = 39.2266 m/s^2. Its y is Backward - Forward.
    static String section(int n, String device, boolean nunchuk) {
        String acc = "`Axis %d%s` * 39.2266";
        StringBuilder s = new StringBuilder();
        s.append("[Wiimote").append(n).append("]\n")
         .append("Device = ").append(device).append('\n')
         .append("Source = 1\n")
         .append("Extension = ").append(nunchuk ? "Nunchuk" : "None").append('\n')
         .append("Buttons/A = `Button A`\nButtons/B = `Button B`\nButtons/1 = `Button Y`\nButtons/2 = `Button X`\n")
         .append("Buttons/- = Select\nButtons/+ = Start\nButtons/Home = Mode\n")
         .append("D-Pad/Up = Up\nD-Pad/Down = Down\nD-Pad/Left = Left\nD-Pad/Right = Right\n")
         .append("IR/Up = `Axis 13-`\nIR/Down = `Axis 13+`\nIR/Left = `Axis 12-`\nIR/Right = `Axis 12+`\n")
         .append("IR/Relative Input = False\n")
         .append("Shake/X = `Button L3`\nShake/Y = `Button L3`\nShake/Z = `Button L3`\n")
         .append("IMUAccelerometer/Up = ").append(String.format(acc, 19, "+")).append('\n')
         .append("IMUAccelerometer/Down = ").append(String.format(acc, 19, "-")).append('\n')
         .append("IMUAccelerometer/Left = ").append(String.format(acc, 11, "+")).append('\n')
         .append("IMUAccelerometer/Right = ").append(String.format(acc, 11, "-")).append('\n')
         .append("IMUAccelerometer/Forward = ").append(String.format(acc, 14, "-")).append('\n')
         .append("IMUAccelerometer/Backward = ").append(String.format(acc, 14, "+")).append('\n')
         .append("Rumble/Motor = `Motor 0`\n")
         .append("Nunchuk/Buttons/C = `Button L1`\nNunchuk/Buttons/Z = `Button R1`\n")
         .append("Nunchuk/Stick/Up = `Axis 1-`\nNunchuk/Stick/Down = `Axis 1+`\n")
         .append("Nunchuk/Stick/Left = `Axis 0-`\nNunchuk/Stick/Right = `Axis 0+`\n")
         .append("Nunchuk/Shake/X = `Button R3`\nNunchuk/Shake/Y = `Button R3`\nNunchuk/Shake/Z = `Button R3`\n")
         .append("Nunchuk/IMUAccelerometer/Up = ").append(String.format(acc, 22, "+")).append('\n')
         .append("Nunchuk/IMUAccelerometer/Down = ").append(String.format(acc, 22, "-")).append('\n')
         .append("Nunchuk/IMUAccelerometer/Left = ").append(String.format(acc, 20, "+")).append('\n')
         .append("Nunchuk/IMUAccelerometer/Right = ").append(String.format(acc, 20, "-")).append('\n')
         .append("Nunchuk/IMUAccelerometer/Forward = ").append(String.format(acc, 21, "-")).append('\n')
         .append("Nunchuk/IMUAccelerometer/Backward = ").append(String.format(acc, 21, "+")).append('\n');
        return s.toString();
    }
}
