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

    private Process bridge;
    private final ArrayDeque<String> log = new ArrayDeque<>();

    public ShellService() { }

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
        // An earlier copy (or an older hand-started bridge) would hold the remote's devices.
        exec("for p in $(pidof motebridge-wiibridge wiibridge); do kill $p; done");
        // Executables in the app's install directory are not runnable by the shell user; run a copy.
        exec("cp '" + executable + "' " + COPY + " && chmod 755 " + COPY);
        // Retroid handhelds replace every new controller with a copy of their own pad (fixed axes, no
        // rumble) unless this setting is on while the controller is created.
        boolean retroid = !exec("settings get system " + RETROID).trim().equals("null");
        if (retroid) exec("settings put system " + RETROID + " 1");
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
        } finally {
            if (retroid) exec("settings put system " + RETROID + " 0");
        }
        return bridgeRunning();
    }

    private synchronized void append(String line) {
        log.add(line);
        while (log.size() > 40) log.poll();
    }

    @Override public synchronized void stopBridge() {
        if (bridge != null) { bridge.destroy(); bridge = null; }
    }

    @Override public synchronized boolean bridgeRunning() {
        return bridge != null && bridge.isAlive();
    }

    @Override public synchronized String bridgeLog() {
        return String.join("\n", log);
    }
}
