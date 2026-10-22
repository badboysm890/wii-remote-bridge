package dev.zenitsu.motebridge;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayDeque;

/**
 * Started by Shizuku in its own process as the shell user (uid 2000). It runs the bridge executable and
 * the few shell commands the app needs (Bluetooth address, Dolphin's config files, settings).
 */
public class ShellService extends IShell.Stub {
    private static final String COPY = "/data/local/tmp/motebridge-wiibridge";
    private static final String RETROID = "no_create_gamepad_button_layout";
    private static final String STATUS = "/data/local/tmp/wiibridge.status";
    private String lastExtensions = "";

    private Process bridge;
    private String wanted;          // executable to keep running, null when stopped on purpose
    private long lastStart;
    private final ArrayDeque<String> log = new ArrayDeque<>();

    public ShellService() {
        // Watchdog: if the bridge dies on its own, start it again (at most every 5 seconds).
        Thread w = new Thread(() -> {
            while (true) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                String exe;
                synchronized (this) { exe = wanted != null && !bridgeRunning() ? wanted : null; }
                if (exe != null && System.currentTimeMillis() - lastStart > 5000) {
                    append("bridge exited; restarting");
                    startBridge(exe);
                }
                followExtensions();
            }
        }, "watchdog");
        w.setDaemon(true);
        w.start();
    }

    @Override public void destroy() {
        stopBridge();
        System.exit(0);
    }

    @Override public String run(String command) {
        return exec(command);
    }

    static String exec(String command) {
        try {
            Process p = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            }
            p.waitFor();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "error: " + e;
        }
    }

    @Override public synchronized boolean startBridge(String executable) {
        stopBridge();
        wanted = executable;
        lastStart = System.currentTimeMillis();
        // An earlier copy (or an older hand-started bridge) would hold the remote's devices.
        exec("for p in $(pidof motebridge-wiibridge wiibridge); do kill $p; done");
        // Executables in the app's install directory are not runnable by the shell user; run a copy.
        exec("cp '" + executable + "' " + COPY + " && chmod 755 " + COPY);
        // A leftover from an interrupted start would leave a Retroid's own pad unmapped.
        if (exec("settings get system " + RETROID).trim().equals("1")) exec("settings put system " + RETROID + " 0");
        try {
            bridge = new ProcessBuilder(COPY).redirectErrorStream(true).start();
            final Process p = bridge;
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    for (String line; (line = r.readLine()) != null; ) append(line);
                } catch (Exception ignored) { }
                append("bridge stopped");
            }, "bridge-log");
            t.setDaemon(true);
            t.start();
            Thread.sleep(2500);
        } catch (Exception e) {
            append("start failed: " + e);
            bridge = null;
        }
        syncDolphin();
        return bridgeRunning();
    }

    /**
     * Dolphin names the controller by its Android controller number, which can change when the bridge
     * restarts; point every Dolphin that we set up (its Wii Remote 1 uses ".../Wii Remote") at the new one.
     */
    private void syncDolphin() {
        String input = exec("dumpsys input");
        for (int player = 1; player <= 4; player++) {
            String name = "Wii Remote " + player;
            // The Input Reader section: "  Device 23: Wii Remote 1" followed by "ControllerNum: 2".
            java.util.regex.Matcher d = java.util.regex.Pattern.compile("\n  Device -?\\d+: " + name + "\n").matcher(input);
            if (!d.find()) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("ControllerNum: (\\d+)").matcher(input);
            if (!m.find(d.end())) continue;
            String n = m.group(1);
            exec("for f in /sdcard/Android/data/org.dolphinemu*/files/Config/WiimoteNew.ini; do [ -f \"$f\" ] && "
                    + "sed -i 's#^Device = Android/[0-9]*/" + name + "$#Device = Android/" + n + "/" + name + "#' \"$f\"; done");
            append("Dolphin player " + player + " uses Android/" + n + "/" + name);
        }
    }

    /**
     * A Nunchuk or Classic Controller was plugged in or out: set that player's extension in every Dolphin we
     * set up, so the next game sees what is really attached.
     */
    private void followExtensions() {
        String now = extensions().trim();
        if (now.equals(lastExtensions)) return;
        lastExtensions = now;
        for (String line : now.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 2 || !f[0].matches("[1-4]")) continue;
            String ext = f[1].equals("nunchuk") ? "Nunchuk" : f[1].equals("classic") ? "Classic" : "None";
            String section = "/^\\[Wiimote" + f[0] + "\\]/,/^\\[/";
            exec("for f in /sdcard/Android/data/org.dolphinemu*/files/Config/WiimoteNew.ini; do "
                    + "grep -q '/Wii Remote " + f[0] + "$' \"$f\" && sed -i '" + section + "s/^Extension = .*/Extension = " + ext
                    + "/' \"$f\"; done");
            append("player " + f[0] + ": " + ext + (line.contains("mplus") ? " + MotionPlus" : ""));
        }
    }

    private synchronized void append(String line) {
        log.add(line);
        while (log.size() > 40) log.poll();
    }

    @Override public synchronized void stopBridge() {
        wanted = null;
        if (bridge != null) { bridge.destroy(); bridge = null; }
    }

    @Override public String axisMap(String deviceName) {
        String input = exec("dumpsys input");
        java.util.regex.Matcher d = java.util.regex.Pattern.compile("\n  Device -?\\d+: " + java.util.regex.Pattern.quote(deviceName) + "\n").matcher(input);
        if (!d.find()) return "";
        int end = input.indexOf("\n  Device ", d.end());
        String dev = input.substring(d.end(), end < 0 ? input.length() : end);
        // Joystick Input Mapper: "GENERIC_3: min=..." then "rawAxis=22, ..." a line later.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("GENERIC_(\\d+): min=[^\n]*\n[^\n]*\n\\s*rawAxis=(\\d+)").matcher(dev);
        StringBuilder out = new StringBuilder();
        while (m.find()) out.append(out.length() == 0 ? "" : ",").append(m.group(2)).append('=').append(m.group(1));
        return out.toString();
    }

    @Override public String extensions() {
        return exec("cat " + STATUS + " 2>/dev/null");
    }

    @Override public synchronized boolean bridgeRunning() {
        return bridge != null && bridge.isAlive();
    }

    @Override public synchronized String bridgeLog() {
        return String.join("\n", log);
    }
}
