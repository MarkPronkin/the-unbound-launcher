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

/** Select the glasses network while each process opens its native control sockets. */
public final class GlassesNetworkCompat {
    private static final String TAG = "UnboundEthernet";
    private static final Object ROUTING = new Object();
    private static final long NETWORK_WAIT_MS = 3000;

    /** Shared selection for the synchronous SDK callback and asynchronous renderer startup. */
    static Lease select(Context context) throws Exception {
        return select(context, NETWORK_WAIT_MS);
    }

    /** A zero wait lets UI lifecycle callbacks retry through their Handler instead of sleeping. */
    static Lease select(Context context, long waitMs) throws Exception {
        Class<?> api = Class.forName("com.xreal.glassesdisplayplugevent.GlassesInitSetting");
        Object settings = api.getMethod("getInstance").invoke(null);
        // Air/H-series startup does not use the Ethernet control channel.
        if (!Boolean.TRUE.equals(api.getMethod("isEthernetSeriesGlasses").invoke(settings))) return null;
        if (context == null) context = (Context) api.getMethod("getContext").invoke(settings);
        if (context == null) throw new IOException("Glasses context is unavailable");
        synchronized (ROUTING) {
            UsbManager usb = context.getSystemService(UsbManager.class);
            UsbDevice device = attachedGlasses(usb);
            if (device == null) throw new IOException("Glasses USB access is unavailable or ambiguous");
            ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
            if (connectivity == null) throw new IOException("Android connectivity service is unavailable");
            Network selected;
            long deadline = SystemClock.uptimeMillis() + waitMs;
            do {
                requireAttachment(usb, device);
                selected = controlNetwork(connectivity);
                if (selected != null) break;
                long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) throw new NetworkPendingException();
                SystemClock.sleep(Math.min(100, remaining));
            } while (true);
            Network previous = connectivity.getBoundNetworkForProcess();
            Lease lease = new Lease(usb, device, connectivity, selected, previous);
            try {
                if (!selected.equals(previous) && !connectivity.bindProcessToNetwork(selected))
                    throw new IOException("Android rejected the glasses control network");
                lease.validate();
                return lease;
            } catch (Exception error) { lease.close(); throw error; }
        }
    }

    static final class NetworkPendingException extends IOException {
        NetworkPendingException() { super("Android has not published the glasses control network"); }
    }

    /** Restores only the process binding owned by this selection; closing is idempotent. */
    static final class Lease implements AutoCloseable {
        final UsbManager usb; final UsbDevice device;
        final ConnectivityManager connectivity; final Network selected, previous;
        private boolean closed;
        Lease(UsbManager usb, UsbDevice device, ConnectivityManager connectivity, Network selected, Network previous) {
            this.usb=usb; this.device=device; this.connectivity=connectivity; this.selected=selected; this.previous=previous;
        }
        void validate() throws IOException {
            requireAttachment(usb, device);
            if (!selected.equals(controlNetwork(connectivity)) || !selected.equals(connectivity.getBoundNetworkForProcess()))
                throw new IOException("Glasses control network changed during startup");
        }
        public void close() {
            synchronized (ROUTING) {
                if (closed) return;
                closed=true;
                if (!selected.equals(previous)) restore(connectivity, selected, previous);
            }
        }
    }

    /** The guarded ping callback synchronously runs nativeInitService/startService/glassesInit. */
    static void deliver(Method result, Object callback, boolean success) throws Exception {
        if (!success) { result.invoke(callback, false); return; }
        synchronized (ROUTING) {
            Lease lease = null;
            boolean ready = false;
            try {
                try {
                    lease = select(null);
                    if (lease != null) Log.i(TAG, "Native startup network selected: " + lease.selected + "; previous=" + lease.previous);
                    ready = true;
                } catch (Exception error) {
                    Log.w(TAG, "Cannot select the glasses control network; native startup is stopped", error);
                }
                // Invoke exactly once. An exception from native startup must not trigger a second callback.
                result.invoke(callback, ready);
            } finally {
                if (lease != null) lease.close();
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
