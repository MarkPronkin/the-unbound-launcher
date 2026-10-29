package dev.beampro.port;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.SystemClock;
import android.util.Log;
import dev.beampro.compat.GlassesIdentity;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;

/** Select the glasses network only while the SDK's synchronous native startup opens its sockets. */
public final class GlassesNetworkCompat {
    private static final String TAG = "UnboundEthernet";
    private static final Object ROUTING = new Object();
    private static final long NETWORK_WAIT_MS = 3000;

    /** The guarded ping callback synchronously runs nativeInitService/startService/glassesInit. */
    static void deliver(Method result, Object callback, boolean success) throws Exception {
        if (!success) { result.invoke(callback, false); return; }
        synchronized (ROUTING) {
            ConnectivityManager connectivity = null;
            Network previous = null, selected = null;
            boolean attemptedBinding = false, ready = false;
            try {
                try {
                    Class<?> api = Class.forName("com.xreal.glassesdisplayplugevent.GlassesInitSetting");
                    Object settings = api.getMethod("getInstance").invoke(null);
                    // Air/H-series startup does not use the Ethernet control channel.
                    if (!Boolean.TRUE.equals(api.getMethod("isEthernetSeriesGlasses").invoke(settings))) {
                        ready = true;
                    } else {
                        Context context = (Context) api.getMethod("getContext").invoke(settings);
                        if (context == null) throw new IOException("Glasses context is unavailable");
                        UsbManager usb = context.getSystemService(UsbManager.class);
                        UsbDevice device = attachedGlasses(usb);
                        if (device == null) throw new IOException("Glasses USB access is unavailable or ambiguous");
                        connectivity = context.getSystemService(ConnectivityManager.class);
                        if (connectivity == null) throw new IOException("Android connectivity service is unavailable");
                        long deadline = SystemClock.uptimeMillis() + NETWORK_WAIT_MS;
                        do {
                            requireAttachment(usb, device);
                            selected = controlNetwork(connectivity);
                            if (selected != null) break;
                            if (SystemClock.uptimeMillis() >= deadline)
                                throw new IOException("Android has not published the glasses control network");
                            SystemClock.sleep(100);
                        } while (true);
                        previous = connectivity.getBoundNetworkForProcess();
                        if (!selected.equals(previous)) {
                            attemptedBinding = true;
                            if (!connectivity.bindProcessToNetwork(selected))
                                throw new IOException("Android rejected the glasses control network");
                        }
                        requireAttachment(usb, device);
                        Log.i(TAG, "Native startup network selected: " + selected + "; previous=" + previous);
                        ready = true;
                    }
                } catch (Exception error) {
                    Log.w(TAG, "Cannot select the glasses control network; native startup is stopped", error);
                }
                // Invoke exactly once. An exception from native startup must not trigger a second callback.
                result.invoke(callback, ready);
            } finally {
                if (attemptedBinding && connectivity != null) restore(connectivity, selected, previous);
            }
        }
    }

    private static void restore(ConnectivityManager connectivity, Network selected, Network previous) {
        try {
            // Preserve a binding changed by another owner while native startup was running.
            if (!selected.equals(connectivity.getBoundNetworkForProcess())) {
                Log.w(TAG, "Native startup network changed by another owner; leaving it intact");
                return;
            }
            boolean restored = false;
            try { restored = connectivity.bindProcessToNetwork(previous); }
            catch (RuntimeException error) { Log.w(TAG, "Previous startup network cannot be restored", error); }
            if (!restored) {
                // The previous network can disappear during the native handshake. Clear the temporary binding.
                if (!connectivity.bindProcessToNetwork(null))
                    throw new IOException("Android could not clear the temporary glasses network");
                Log.w(TAG, "Previous startup network disappeared; restored default process routing");
            } else Log.i(TAG, "Native startup network restored: " + previous);
        } catch (Exception error) {
            Log.e(TAG, "Cannot restore process routing after glasses startup", error);
        }
    }

    static Network controlNetwork(ConnectivityManager connectivity) throws IOException {
        Network selected = null;
        InetAddress peer = InetAddress.getByName("169.254.2.1");
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            LinkProperties link = connectivity.getLinkProperties(network);
            if (capabilities == null || link == null
                    || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) continue;
            boolean address = false, route = false;
            for (LinkAddress local : link.getLinkAddresses())
                if (local.getPrefixLength() == 24 && "169.254.2.10".equals(local.getAddress().getHostAddress())) address = true;
            for (RouteInfo path : link.getRoutes())
                if (!path.isDefaultRoute() && path.getDestination().getPrefixLength() == 24
                        && path.getDestination().contains(peer) && !path.hasGateway()
                        && link.getInterfaceName() != null && link.getInterfaceName().equals(path.getInterface())) route = true;
            if (!address || !route) continue;
            if (selected != null && !selected.equals(network)) throw new IOException("Multiple glasses control networks are available");
            selected = network;
        }
        return selected;
    }

    private static UsbDevice attachedGlasses(UsbManager usb) {
        if (usb == null) return null;
        UsbDevice selected = null;
        for (UsbDevice device : usb.getDeviceList().values()) {
            if (!GlassesIdentity.usb(device.getVendorId(), device.getProductId()) || !usb.hasPermission(device)) continue;
            if (selected != null) return null;
            selected = device;
        }
        return selected;
    }

    private static void requireAttachment(UsbManager usb, UsbDevice expected) throws IOException {
        UsbDevice live = attachedGlasses(usb);
        if (live == null || !live.getDeviceName().equals(expected.getDeviceName())
                || live.getDeviceId() != expected.getDeviceId() || live.getVendorId() != expected.getVendorId()
                || live.getProductId() != expected.getProductId()) throw new IOException("Glasses changed during network selection");
    }

    private GlassesNetworkCompat() { }
}
