package dev.beampro.port;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Optional Bluetooth discovery tolerates denied permission and unsupported profiles. */
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
        } catch (SecurityException | IllegalArgumentException unavailable) {
            // The stock helper also asks for HID_HOST (7); standard Android's
            // BluetoothManager only supports GATT/GATT_SERVER here. Keep the
            // helper's existing bonded-device results when this extra query fails.
            return Collections.emptyList();
        }
    }
}
