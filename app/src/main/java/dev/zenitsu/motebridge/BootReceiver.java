package dev.zenitsu.motebridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the bridge after boot or an update, if the user turned that on. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (c.getSharedPreferences("motebridge", Context.MODE_PRIVATE).getBoolean("autostart", true)) {
            c.startForegroundService(new Intent(c, AutoStartService.class));
        }
    }
}
