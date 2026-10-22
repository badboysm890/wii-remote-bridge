package dev.zenitsu.motebridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import rikka.shizuku.Shizuku;

/** The connection to ShellService through Shizuku, shared by the app's screens and the boot starter. */
final class Shell {
    enum State { NO_SHIZUKU, NEEDS_PERMISSION, CONNECTING, READY }

    private static IShell shell;
    private static final List<Runnable> listeners = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean binding;
    private static Context app;

    private static final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            shell = IShell.Stub.asInterface(b);
            binding = false;
            changed();
            autoStart();
        }
        @Override public void onServiceDisconnected(ComponentName n) { shell = null; binding = false; changed(); }
    };

    static synchronized void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        Shizuku.addBinderReceivedListenerSticky(() -> { connect(app); changed(); });
        Shizuku.addBinderDeadListener(() -> { shell = null; binding = false; changed(); });
        Shizuku.addRequestPermissionResultListener((code, result) -> { connect(app); changed(); });
    }

    static void listen(Runnable r) { listeners.add(r); }
    static void unlisten(Runnable r) { listeners.remove(r); }
    private static void changed() { main.post(() -> { for (Runnable r : new ArrayList<>(listeners)) r.run(); }); }

    static State state() {
        if (!Shizuku.pingBinder()) return State.NO_SHIZUKU;
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return State.NEEDS_PERMISSION;
        return shell == null ? State.CONNECTING : State.READY;
    }

    static void requestPermission() {
        if (Shizuku.pingBinder() && !Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(1);
    }

    static synchronized void connect(Context c) {
        if (shell != null || binding || !Shizuku.pingBinder()
                || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
        binding = true;
        Shizuku.bindUserService(new Shizuku.UserServiceArgs(new ComponentName(c.getPackageName(), ShellService.class.getName()))
                .daemon(true).processNameSuffix("shell").debuggable(BuildConfig.DEBUG).version(serviceVersion(c)),
                connection);
    }

    /** Changes with every install, so Shizuku replaces a running service left over from an older version. */
    private static int serviceVersion(Context c) {
        try {
            return (int) (c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime / 1000);
        } catch (PackageManager.NameNotFoundException e) {
            return BuildConfig.VERSION_CODE;
        }
    }

    static IShell get() { return shell; }

    /** Whenever Shizuku becomes reachable (boot, Shizuku started later, app opened), make sure the bridge runs. */
    private static void autoStart() {
        if (app == null || !app.getSharedPreferences("motebridge", Context.MODE_PRIVATE).getBoolean("autostart", true)) return;
        async(s -> {
            if (!s.bridgeRunning()) {
                s.startBridge(bridgeExecutable(app));
                Thread.sleep(1500);
                Dolphin.refreshDevice(app, s);
            }
            Dolphin.setUpIfNeeded(app, s);
            return true;
        }, ok -> changed());
    }

    /** Runs work that talks to the shell off the main thread; the result comes back on it. */
    static <T> void async(Task<T> work, Consumer<T> done) {
        new Thread(() -> {
            T r = null;
            try { IShell s = shell; if (s != null) r = work.run(s); } catch (Exception ignored) { }
            final T result = r;
            main.post(() -> done.accept(result));
        }, "shell").start();
    }

    interface Task<T> { T run(IShell s) throws Exception; }

    static String bridgeExecutable(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libwiibridge.so").getPath();
    }
}
