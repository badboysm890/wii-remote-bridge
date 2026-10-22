package dev.zenitsu.motebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * After boot, waits for Shizuku (it comes up once Wireless debugging is on) and starts the bridge, then
 * stops itself. The bridge keeps running in Shizuku's process.
 */
public class AutoStartService extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int tries;

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("start", "Starting", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "start").setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Starting the Wii Remote bridge").build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        Shell.init(this);
        handler.post(this::attempt);
        return START_NOT_STICKY;
    }

    private void attempt() {
        Shell.connect(this);
        IShell s = Shell.get();
        if (s != null) {
            Shell.async(sh -> {
                if (!sh.bridgeRunning()) sh.startBridge(Shell.bridgeExecutable(this));
                Thread.sleep(1500);
                Dolphin.refreshDevice(this, sh);
                Dolphin.setUpIfNeeded(this, sh);
                return true;
            }, ok -> stopSelf());
            return;
        }
        if (++tries < 60) handler.postDelayed(this::attempt, 5000);   // up to 5 minutes
        else stopSelf();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
