package dev.beampro.port;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/** Host regression against the production adapter, with controllable framework failures. */
public final class BluetoothCompatTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        BluetoothDevice device = new BluetoothDevice();
        BluetoothManager manager = new BluetoothManager();
        BluetoothAdapter adapter = new BluetoothAdapter();

        // The stock helper asks for HID_HOST (7). Standard Android rejects this
        // supplementary query even when the bonded-device pass has succeeded.
        adapter.devices = new HashSet<>(Arrays.asList(device));
        manager.failure = new IllegalArgumentException("Profile not supported: 7");
        check(BluetoothCompat.getBondedDevices(adapter).contains(device), "bonded input remains available");
        check(BluetoothCompat.getConnectedDevices(manager, 7).isEmpty(), "unsupported HID query is optional");
        check(manager.profile == 7, "original profile is forwarded");
        check(BluetoothCompat.getBondedDevices(adapter).contains(device), "HID rejection preserves bonded input");

        // Retain vendor support for profile 7 rather than rejecting it in advance.
        manager.failure = null;
        manager.devices = Arrays.asList(device);
        check(BluetoothCompat.getConnectedDevices(manager, 7) == manager.devices, "vendor HID results survive");
        check(BluetoothCompat.getConnectedDevices(manager, 8) == manager.devices, "GATT results survive");
        check(manager.profile == 8, "GATT profile is forwarded");
        check(BluetoothCompat.getConnectedDevices(manager, 9) == manager.devices, "GATT server results survive");
        check(manager.profile == 9, "GATT server profile is forwarded");
        manager.devices = Collections.emptyList();
        check(BluetoothCompat.getConnectedDevices(manager, 8).isEmpty(), "empty success stays empty");

        manager.failure = new SecurityException("Nearby devices denied");
        check(BluetoothCompat.getConnectedDevices(manager, 7).isEmpty(), "denied connection query is optional");
        adapter.failure = new SecurityException("Nearby devices denied");
        check(BluetoothCompat.getBondedDevices(adapter).isEmpty(), "denied bonded query is optional");
        adapter.failure = null;
        adapter.devices = null;
        check(BluetoothCompat.getBondedDevices(adapter).isEmpty(), "null bonded result stays empty");
        check(BluetoothCompat.getBondedDevices(null).isEmpty(), "absent adapter is optional");
        check(BluetoothCompat.getConnectedDevices(null, 7).isEmpty(), "absent manager is optional");

        manager.failure = new IllegalStateException("Unexpected framework failure");
        try {
            BluetoothCompat.getConnectedDevices(manager, 8);
            throw new AssertionError("unrelated failures must remain visible");
        } catch (IllegalStateException expected) {
            check(expected == manager.failure, "unrelated failure is not suppressed");
        }
        System.out.println("PASS Bluetooth enumeration checks: " + checks);
    }
}
