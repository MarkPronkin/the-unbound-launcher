package dev.beampro.port;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Optional Bluetooth input discovery must tolerate an ungranted Nearby Devices permission. */
public final class BluetoothCompat {
    public static Set<BluetoothDevice> getBondedDevices(BluetoothAdapter adapter) {
        if (adapter == null) return Collections.emptySet();
        try {
            Set<BluetoothDevice> devices = adapter.getBondedDevices();
            return devices == null ? Collections.emptySet() : devices;
        } catch (SecurityException denied) {
            return Collections.emptySet();
        }
    }

    public static List<BluetoothDevice> getConnectedDevices(BluetoothManager manager, int profile) {
        if (manager == null) return Collections.emptyList();
        try {
            return manager.getConnectedDevices(profile);
        } catch (SecurityException denied) {
            return Collections.emptyList();
        }
    }
}
