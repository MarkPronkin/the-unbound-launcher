package dev.beampro.adb;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.Collections;

/** Read-only startup hints. A live helper Binder remains authoritative on every network. */
final class AdbNetworkState {
    static final String USB_REQUIRED = "Enable USB debugging in Developer options, then tap Connect and start. Keep it enabled to use AR without Wi-Fi.";

    private AdbNetworkState() {}

    static int usbDebugging(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, -1); }
        catch (RuntimeException unavailable) { return -1; }
    }

    /** Unknown must still permit discovery. Wi-Fi need not be the default or have internet. */
    static Boolean wifiConnected(Context context) {
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            if (manager == null) return null;
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
            return false;
        } catch (RuntimeException unavailable) { return null; }
    }

    static boolean vpnActive(Context context) {
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            if (manager != null) for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException unavailable) { /* Keep the normal recovery path. */ }
        return false;
    }

    /** Stable across enumeration order and signal changes; changes when routes or VPNs change. */
    static String signature(Context context) {
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            if (manager == null) return "unknown";
            ArrayList<String> networks = new ArrayList<>();
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                LinkProperties link = manager.getLinkProperties(network);
                StringBuilder value = new StringBuilder(network.toString());
                for (int type : new int[]{NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN,
                        NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_ETHERNET})
                    value.append(':').append(caps != null && caps.hasTransport(type));
                if (link != null) value.append(':').append(link.getInterfaceName())
                        .append(':').append(sorted(link.getLinkAddresses())).append(':').append(sorted(link.getRoutes()));
                networks.add(value.toString());
            }
            Collections.sort(networks);
            return manager.getBoundNetworkForProcess() + ":" + networks;
        } catch (RuntimeException unavailable) { return "unknown"; }
    }

    private static String sorted(java.util.List<?> values) {
        ArrayList<String> result = new ArrayList<>();
        for (Object value : values) result.add(String.valueOf(value));
        Collections.sort(result);
        return result.toString();
    }

    static int bootCount(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (RuntimeException unavailable) { return -1; }
    }

    static boolean restarted(Context context) {
        int previous = context.getSharedPreferences("unbound-adb", 0).getInt("helper-boot-count", -1);
        int current = bootCount(context);
        return previous >= 0 && current >= 0 && previous != current;
    }

    static String recovery(Context context) {
        return (restarted(context) ? "Phone restarted. " : "AR app windows are not running. ")
                + "Connect to Wi-Fi, enable USB debugging and Wireless debugging, then tap Connect and start. Pair again only if Android forgot Unbound.";
    }

    static String mobileStatus(Context context, boolean ready) {
        if (!ready) return "Start AR app windows on Wi-Fi first. Once ready, you can use mobile data with USB debugging left on. A phone restart or stopped helper needs Wi-Fi to reconnect.";
        int usb = usbDebugging(context);
        if (usb == 0) return "Turn on USB debugging before leaving Wi-Fi so Android can keep AR app windows running.";
        if (usb != 1) return "Check that USB debugging is enabled before leaving Wi-Fi. Android has not made its status available.";
        return "Ready to use without Wi-Fi. Keep USB debugging enabled. Reconnect on Wi-Fi after a phone restart or if AR app windows stop.";
    }
}
