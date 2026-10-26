package dev.beampro.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import java.net.*;
import java.util.*;

/** Discover only this phone's TLS services; every actual connection uses loopback. */
final class AdbDiscovery implements AutoCloseable {
    private final NsdManager manager;
    private final List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
    private final Map<String, Integer> pairing = new LinkedHashMap<>(), connect = new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private boolean resolving, closed;
    AdbDiscovery(Context context) {
        manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        discover("_adb-tls-pairing._tcp."); discover("_adb-tls-connect._tcp.");
    }
    synchronized List<Integer> ports(boolean pair) {
        return new ArrayList<>((pair ? pairing : connect).values());
    }
    private void discover(String type) {
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String t) {}
            public void onDiscoveryStopped(String t) {}
            public void onStartDiscoveryFailed(String t, int e) {}
            public void onStopDiscoveryFailed(String t, int e) {}
            public void onServiceLost(NsdServiceInfo info) {
                synchronized (AdbDiscovery.this) { pairing.remove(info.getServiceName()); connect.remove(info.getServiceName()); }
            }
            public void onServiceFound(NsdServiceInfo info) {
                synchronized (AdbDiscovery.this) {
                    if (closed || queue.size() >= 16) return;
                    queue.add(info); resolveNext();
                }
            }
        };
        try { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener); listeners.add(listener); }
        catch (RuntimeException ignored) { /* Manual ports remain available. */ }
    }
    private synchronized void resolveNext() {
        if (closed || resolving || queue.isEmpty()) return;
        NsdServiceInfo next = queue.remove(); resolving = true;
        try {
            manager.resolveService(next, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo info, int error) { done(null); }
                public void onServiceResolved(NsdServiceInfo info) { done(info); }
                private void done(NsdServiceInfo info) {
                    synchronized (AdbDiscovery.this) {
                        resolving = false;
                        if (!closed && info != null && local(info.getHost()) && info.getPort() > 0 && info.getPort() <= 65535) {
                            Map<String, Integer> target = info.getServiceType().contains("pairing") ? pairing : connect;
                            target.put(info.getServiceName(), info.getPort());
                        }
                        resolveNext();
                    }
                }
            });
        } catch (RuntimeException failure) { resolving = false; resolveNext(); }
    }
    private static boolean local(InetAddress address) {
        if (address == null) return false;
        if (address.isLoopbackAddress()) return true;
        try { return NetworkInterface.getByInetAddress(address) != null; }
        catch (SocketException error) { return false; }
    }
    @Override public synchronized void close() {
        closed = true; queue.clear(); pairing.clear(); connect.clear();
        for (NsdManager.DiscoveryListener listener : listeners) {
            try { manager.stopServiceDiscovery(listener); } catch (RuntimeException ignored) {}
        }
        listeners.clear();
    }
}
