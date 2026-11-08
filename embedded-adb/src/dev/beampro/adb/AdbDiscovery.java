package dev.beampro.adb;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.net.*;
import java.util.*;

/** Discover only this phone's TLS services; every actual connection uses loopback. */
final class AdbDiscovery implements AutoCloseable {
    private final Context context;
    private final NsdManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
    private final Map<String, Integer> pairing = new LinkedHashMap<>(), connect = new LinkedHashMap<>();
    private final Map<String, Pending> services = new HashMap<>();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final Runnable refresh = this::refresh;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Pending resolving;
    private String signature;
    private long generation, retryAt;
    private boolean closed, legacyDiscovery;

    AdbDiscovery(Context source) {
        context = source.getApplicationContext();
        manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        try {
            connectivity = context.getSystemService(ConnectivityManager.class);
            if (connectivity != null) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    private void changed() {
                        synchronized (AdbDiscovery.this) {
                            if (closed) return;
                            main.removeCallbacks(refresh);
                            main.postDelayed(refresh, 300);
                        }
                    }
                    @Override public void onAvailable(Network network) { changed(); }
                    @Override public void onLost(Network network) { changed(); }
                    @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { changed(); }
                    @Override public void onLinkPropertiesChanged(Network n, LinkProperties p) { changed(); }
                };
                // Include VPNs as well as physical networks, including Wi-Fi without internet.
                connectivity.registerNetworkCallback(new NetworkRequest.Builder().clearCapabilities().build(), networkCallback);
            }
        } catch (RuntimeException unavailable) { networkCallback = null; }
        refresh();
    }

    synchronized List<Integer> ports(boolean pair) {
        // Also poll so discovery can recover on devices which suppress network callbacks.
        refresh();
        return new ArrayList<>((pair ? pairing : connect).values());
    }

    synchronized boolean isClosed() { return closed; }

    private synchronized void refresh() {
        if (closed) return;
        String current = AdbNetworkState.signature(context);
        if (current.equals(signature) && (retryAt == 0 || SystemClock.elapsedRealtime() < retryAt)) return;
        signature = current;
        generation++;
        stopDiscovery();
        pairing.clear(); connect.clear(); services.clear(); queue.clear();
        if (resolving != null && Build.VERSION.SDK_INT >= 34) {
            Pending previous = resolving; resolving = null;
            main.removeCallbacks(previous.timeout); stopResolution(previous);
        }
        retryAt = 0;
        if (manager == null) return;
        discover("_adb-tls-pairing._tcp."); discover("_adb-tls-connect._tcp.");
    }

    private void discover(String type) {
        final long epoch = generation;
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            private boolean current() { return !closed && epoch == generation; }
            public void onDiscoveryStarted(String t) {}
            public void onDiscoveryStopped(String t) {}
            public void onStartDiscoveryFailed(String t, int e) {
                synchronized (AdbDiscovery.this) {
                    if (!current()) return;
                    listeners.remove(this);
                    legacyDiscovery = true;
                    retryAt = SystemClock.elapsedRealtime() + 1000;
                    main.removeCallbacks(refresh); main.postDelayed(refresh, 1000);
                }
            }
            public void onStopDiscoveryFailed(String t, int e) {}
            public void onServiceLost(NsdServiceInfo info) {
                synchronized (AdbDiscovery.this) {
                    if (!current()) return;
                    String key = key(info, type);
                    services.remove(key); pairing.remove(key); connect.remove(key);
                    queue.removeIf(pending -> pending.key.equals(key));
                }
            }
            public void onServiceFound(NsdServiceInfo info) {
                synchronized (AdbDiscovery.this) {
                    if (!current() || services.size() >= 32 || queue.size() >= 16) return;
                    String key = key(info, type);
                    if (services.containsKey(key)) return;
                    Pending pending = new Pending(info, key, type.contains("pairing"), epoch);
                    services.put(key, pending); queue.add(pending); resolveNext();
                }
            }
        };
        listeners.add(listener);
        try {
            if (Build.VERSION.SDK_INT >= 33 && !legacyDiscovery) {
                NetworkRequest wifi = new NetworkRequest.Builder().clearCapabilities()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, wifi, main::post, listener);
            } else manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException unavailable) {
            // Older/vendor implementations may lack scoped discovery or deny network access.
            try { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener); }
            catch (RuntimeException failure) { listener.onStartDiscoveryFailed(type, 0); }
        }
    }

    private static String key(NsdServiceInfo info, String type) {
        return type + ":" + info.getServiceName() + ":" + (Build.VERSION.SDK_INT >= 33 ? info.getNetwork() : "");
    }

    private synchronized void resolveNext() {
        if (closed || resolving != null || queue.isEmpty()) return;
        Pending next = queue.remove(); resolving = next;
        next.timeout = () -> {
            synchronized (AdbDiscovery.this) {
                if (resolving != next) return;
                stopResolution(next); resolved(next, null);
            }
        };
        main.postDelayed(next.timeout, 3000);
        next.listener = new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo info, int error) { resolved(next, null); }
            public void onServiceResolved(NsdServiceInfo info) { resolved(next, info); }
        };
        try {
            manager.resolveService(next.info, next.listener);
        } catch (RuntimeException failure) { resolved(next, null); }
    }

    private synchronized void resolved(Pending pending, NsdServiceInfo info) {
        if (resolving != pending) return;
        main.removeCallbacks(pending.timeout);
        resolving = null;
        if (!closed && pending.epoch == generation && services.get(pending.key) == pending) {
            if (info != null && local(info.getHost()) && info.getPort() > 0 && info.getPort() <= 65535)
                (pending.pair ? pairing : connect).put(pending.key, info.getPort());
            else services.remove(pending.key); // Permit a later announcement to retry resolution.
        }
        resolveNext();
    }

    private static boolean local(InetAddress address) {
        if (address == null) return false;
        if (address.isLoopbackAddress()) return true;
        try { return NetworkInterface.getByInetAddress(address) != null; }
        catch (SocketException | SecurityException error) { return false; }
    }

    private void stopDiscovery() {
        for (NsdManager.DiscoveryListener listener : listeners) {
            try { manager.stopServiceDiscovery(listener); } catch (RuntimeException ignored) {}
        }
        listeners.clear();
    }

    private void stopResolution(Pending pending) {
        if (Build.VERSION.SDK_INT >= 34) {
            try { manager.stopServiceResolution(pending.listener); } catch (RuntimeException ignored) {}
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true; generation++;
        main.removeCallbacks(refresh);
        if (resolving != null) { main.removeCallbacks(resolving.timeout); stopResolution(resolving); }
        resolving = null; queue.clear(); services.clear(); pairing.clear(); connect.clear();
        stopDiscovery();
        if (networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) {}
            networkCallback = null;
        }
    }

    private static final class Pending {
        final NsdServiceInfo info;
        final String key;
        final boolean pair;
        final long epoch;
        Runnable timeout;
        NsdManager.ResolveListener listener;
        Pending(NsdServiceInfo info, String key, boolean pair, long epoch) {
            this.info = info; this.key = key; this.pair = pair; this.epoch = epoch;
        }
    }
}
