package dev.beampro.adb;

import android.content.Context;
import android.net.Network;
import android.os.SystemClock;
import java.lang.reflect.*;
import java.util.*;

public final class VpnAdbTest {
    private static int checks;
    private static void check(boolean v, String m) { checks++; if (!v) throw new AssertionError(m); }
    private static Context context() {
        Context c = new Context(); c.resolver.settings.put("adb_enabled", 1);
        c.getSharedPreferences("unbound-adb", 0).edit().putBoolean("paired", true).putInt("connect-port", 37001).apply();
        AdbDiscovery.found = Collections.emptyList(); SystemClock.tick = null; SystemClock.now = 0;
        return c;
    }
    private static void start(Context c, LocalAdb adb, int port) throws Exception {
        start(c, adb, port, null);
    }
    private static void start(Context c, LocalAdb adb, int port, AdbDiscovery discovery) throws Exception {
        Method m = AdbBootstrap.class.getDeclaredMethod("start", Context.class, LocalAdb.class, int.class, AdbDiscovery.class);
        m.setAccessible(true);
        try { m.invoke(null, c, adb, port, discovery); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    private static void pair(Context c, LocalAdb adb) throws Exception {
        Method m = AdbBootstrap.class.getDeclaredMethod("pairLocal", Context.class, LocalAdb.class, int.class, String.class);
        m.setAccessible(true);
        try { m.invoke(null, c, adb, 37100, "123456"); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    private static void tests() throws Exception {
        Context c = context();
        c.connectivity.networks = new Network[]{new Network(4, 1)};
        check(Boolean.FALSE.equals(AdbNetworkState.wifiConnected(c)), "VPN inheriting Wi-Fi transport is not physical Wi-Fi");
        Network wifi = new Network(1), vpn = new Network(4, 1);
        c.connectivity.networks = new Network[]{wifi, vpn};
        check(Boolean.TRUE.equals(AdbNetworkState.wifiConnected(c)), "Find physical Wi-Fi behind VPN");
        String signature = AdbNetworkState.signature(c);
        c.connectivity.networks = new Network[]{vpn, wifi};
        check(signature.equals(AdbNetworkState.signature(c)), "Enumeration order cannot restart discovery");
        c.connectivity.link.routes.add("new route");
        check(!signature.equals(AdbNetworkState.signature(c)), "Route changes invalidate discovery even on the same Network");

        // Enable, disable, and replace a VPN between failed TCP attempts.
        for (int transition = 0; transition < 3; transition++) {
            c = context(); final Context source = c;
            c.connectivity.networks = transition == 0 ? new Network[]{new Network(1)} : new Network[]{new Network(1), new Network(4)};
            LocalAdb adb = new LocalAdb(c); adb.accepted = 1;
            final int mode = transition;
            SystemClock.tick = () -> {
                if (SystemClock.now < 150) return;
                Network next = new Network(4); next.name = "replacement-vpn";
                source.connectivity.networks = mode == 1 ? new Network[]{new Network(1)} : new Network[]{new Network(1), next};
                adb.accepted = 37001;
            };
            int closed = AdbDiscovery.closed;
            start(c, adb, 0);
            check(AdbBootstrap.ready(c) && adb.starts == 1 && adb.attempts.equals(Arrays.asList(37001, 37001)),
                    "VPN transition " + transition + " recovers the saved loopback listener");
            check(AdbDiscovery.closed == closed + 1 && adb.disconnects == 1, "Release discovery and the failed transport");
            int attempts = adb.attempts.size();
            c.connectivity.networks = new Network[0];
            start(c, adb, 0);
            check(adb.attempts.size() == attempts && adb.starts == 1, "Ready Binder survives later network/VPN changes");
        }

        c = context();
        LocalAdb adb = new LocalAdb(c); adb.accepted = 1;
        SystemClock.tick = () -> { if (SystemClock.now >= 750) adb.accepted = 37001; };
        start(c, adb, 0);
        check(AdbBootstrap.ready(c) && adb.attempts.size() == 3, "Transient listener failure retries without a network callback");

        c = context(); LocalAdb fresh = new LocalAdb(c); fresh.accepted = 37009;
        SystemClock.tick = () -> {
            if (SystemClock.now >= 900) AdbDiscovery.found = Arrays.asList(37001, 37009);
            else AdbDiscovery.found = Arrays.asList(37001);
        };
        start(c, fresh, 0);
        check(AdbBootstrap.ready(c) && fresh.attempts.contains(37009), "Continue discovery after stale ports fail to find a late listener");

        c = context(); final Context changing = c;
        LocalAdb interruptedShell = new LocalAdb(c) {
            private boolean failed;
            @Override public io.github.muntashirakon.adb.AdbStream openStream(String cmd) throws java.io.IOException {
                if (!failed) {
                    failed = true; changing.connectivity.networks = new Network[]{new Network(1), new Network(4)};
                    throw new java.io.IOException("VPN switched after ADB authentication");
                }
                return super.openStream(cmd);
            }
        };
        start(c, interruptedShell, 0);
        check(AdbBootstrap.ready(c) && interruptedShell.starts == 1 && interruptedShell.attempts.size() == 2,
                "A route change during shell startup rebuilds the transport and launches one helper");

        c = context();
        LocalAdb lostReply = new LocalAdb(c) {
            @Override public io.github.muntashirakon.adb.AdbStream openStream(String cmd) throws java.io.IOException {
                io.github.muntashirakon.adb.AdbStream stream = super.openStream(cmd);
                if (cmd.startsWith("shell:mkdir")) throw new java.io.IOException("Lost reply after helper published Binder");
                return stream;
            }
        };
        start(c, lostReply, 0);
        check(AdbBootstrap.ready(c) && lostReply.starts == 1 && lostReply.attempts.size() == 1,
                "A live Binder wins over a lost shell reply without launching a duplicate helper");

        c = context(); final Context pairing = c;
        LocalAdb interruptedPair = new LocalAdb(c) {
            int calls;
            @Override public boolean pair(String host, int port, String code) throws Exception {
                if (++calls == 1) {
                    pairing.connectivity.networks = new Network[]{new Network(1), new Network(4)};
                    throw new java.io.IOException("VPN switched during pairing");
                }
                return true;
            }
        };
        pair(c, interruptedPair);
        check(interruptedPair.disconnects == 1, "Pairing retries once after a route change without discarding the identity");
        LocalAdb rejectedPair = new LocalAdb(c) {
            @Override public boolean pair(String host, int port, String code) throws Exception {
                throw new java.io.IOException("Code rejected");
            }
        };
        try { pair(c, rejectedPair); throw new AssertionError("Rejected code accepted"); }
        catch (java.io.IOException expected) { check(rejectedPair.disconnects == 0, "Do not automatically resubmit a rejected code on a stable network"); }

        c = context(); AdbDiscovery closedOwner = new AdbDiscovery(c); closedOwner.close();
        AdbDiscovery.found = Arrays.asList(37009); LocalAdb replacement = new LocalAdb(c); replacement.accepted = 37009;
        int created = AdbDiscovery.created;
        start(c, replacement, 0, closedOwner);
        check(AdbBootstrap.ready(c) && AdbDiscovery.created == created + 1, "Replace discovery closed by the setup screen during a connection attempt");

        c = context(); c.connectivity.networks = new Network[]{new Network(1), new Network(4)};
        LocalAdb blocked = new LocalAdb(c); blocked.accepted = 1;
        int closed = AdbDiscovery.closed;
        try { start(c, blocked, 0); throw new AssertionError("Blocked listener accepted"); }
        catch (java.io.IOException expected) {
            check(expected.getMessage().contains("VPN") && expected.getMessage().contains("local"), "VPN block explains local-access recovery");
        }
        check(blocked.attempts.size() == 3 && SystemClock.now <= 20200, "Bound retries and total recovery wait");
        check(AdbDiscovery.closed == closed + 1 && !AdbBootstrap.ready(c), "Failed recovery closes owned discovery without claiming Ready");

        c = context(); final Context flapping = c;
        LocalAdb unavailable = new LocalAdb(c); unavailable.accepted = 1;
        SystemClock.tick = () -> {
            Network n = new Network(4); n.name = "vpn-" + SystemClock.now;
            flapping.connectivity.networks = new Network[]{new Network(1), n};
        };
        try { start(c, unavailable, 0); throw new AssertionError("Flapping listener accepted"); }
        catch (java.io.IOException expected) { check(unavailable.attempts.size() == 12, "Continuous VPN switching cannot create an infinite retry loop"); }

        c = context(); LocalAdb ignored = new LocalAdb(c);
        Thread.currentThread().interrupt();
        try { start(c, ignored, 0); throw new AssertionError("Interrupt ignored"); }
        catch (InterruptedException expected) { check(ignored.attempts.isEmpty(), "Cancellation stops connection work"); }
        finally { Thread.interrupted(); }
    }
    public static void main(String[] args) throws Exception {
        try { tests(); System.out.println("PASS VPN ADB assertions: " + checks); }
        finally {
            SystemClock.tick = null;
            for (String name : new String[]{"WORK", "WATCHDOG"}) {
                Field f = AdbBootstrap.class.getDeclaredField(name); f.setAccessible(true);
                ((java.util.concurrent.ExecutorService) f.get(null)).shutdownNow();
            }
        }
    }
}
