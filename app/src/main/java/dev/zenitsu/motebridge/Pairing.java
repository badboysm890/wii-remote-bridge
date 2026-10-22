package dev.zenitsu.motebridge;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.util.function.Consumer;

/**
 * Pairs a Wii Remote, which Android's Bluetooth settings cannot: its PIN is a Bluetooth address written
 * backwards as six raw bytes. With the red SYNC button the PIN is this device's address and the pairing is
 * permanent (any button reconnects later); with 1+2 it is the remote's own address.
 */
@SuppressLint("MissingPermission")
final class Pairing {
    private final Context context;
    private final BluetoothAdapter adapter;
    private final String hostAddress;
    private final Consumer<String> status;
    private BluetoothDevice target;
    private int attempt;
    private boolean active = true;
    private boolean rebond;   // the remote was paired before: forget that pairing first, then pair again

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                if (target == null && active) adapter.startDiscovery();   // keep looking until it shows up
                return;
            }
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (device == null) return;
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                if (target == null && isWiimote(device)) {
                    target = device;
                    adapter.cancelDiscovery();
                    status.accept("Found " + device.getName() + ", pairing…");
                    if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                        rebond = true;
                        try { device.getClass().getMethod("removeBond").invoke(device); } catch (Exception ignored) { }
                    } else {
                        device.createBond();
                    }
                }
            } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                if (!device.equals(target)) return;
                String source = attempt++ == 0 && hostAddress != null ? hostAddress : device.getAddress();
                String[] p = source.split(":");
                byte[] pin = new byte[6];
                for (int i = 0; i < 6; i++) pin[i] = (byte) Integer.parseInt(p[5 - i], 16);
                device.setPin(pin);
                Log.i("MoteBridge", "pin from " + (source.equals(device.getAddress()) ? "remote" : "host"));
                try { abortBroadcast(); } catch (Exception ignored) { }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                if (!device.equals(target)) return;
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                if (state == BluetoothDevice.BOND_BONDED) {
                    status.accept("Paired. Press any button on the Wii Remote to connect.");
                    stop();
                } else if (state == BluetoothDevice.BOND_NONE) {
                    if (rebond) { rebond = false; device.createBond(); }
                    else if (attempt == 1) device.createBond();
                    else {
                        status.accept("Pairing failed. Press SYNC again.");
                        target = null;
                        attempt = 0;
                        adapter.startDiscovery();
                    }
                }
            }
        }
    };

    Pairing(Context c, String hostAddress, Consumer<String> status) {
        this.context = c;
        this.hostAddress = hostAddress;
        this.status = status;
        adapter = c.getSystemService(BluetoothManager.class).getAdapter();
    }

    void start() {
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        // Remotes paired earlier stay paired: this adds one (players 2 to 4 pair the same way).
        status.accept("Open the Wii Remote's battery cover and press the red SYNC button.");
        adapter.cancelDiscovery();
        adapter.startDiscovery();
    }

    void stop() {
        active = false;
        try { context.unregisterReceiver(receiver); } catch (Exception ignored) { }
        try { adapter.cancelDiscovery(); } catch (Exception ignored) { }
    }

    static boolean isWiimote(BluetoothDevice d) {
        String n = d.getName();
        return n != null && n.startsWith("Nintendo RVL");
    }

    static boolean paired(Context c) {
        BluetoothAdapter a = c.getSystemService(BluetoothManager.class).getAdapter();
        if (a == null || !a.isEnabled()) return false;
        for (BluetoothDevice d : a.getBondedDevices()) if (isWiimote(d)) return true;
        return false;
    }
}
