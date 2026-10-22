package dev.zenitsu.motebridge;

import android.app.Application;

/**
 * Shizuku wakes this app (through its provider) whenever Shizuku starts; connecting here means the bridge
 * comes back by itself after Shizuku is restarted, with no screen opened.
 */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Shell.init(this);
    }
}
