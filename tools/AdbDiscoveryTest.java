package dev.beampro.adb;

import android.content.Context;
import android.net.Network;
import android.net.nsd.*;
import android.os.*;
import java.util.*;

public final class AdbDiscoveryTest {
    private static int checks;
    private static final String CONNECT = "_adb-tls-connect._tcp.", PAIR = "_adb-tls-pairing._tcp.";
    private static void check(boolean v, String m) { checks++; if (!v) throw new AssertionError(m); }
    private static NsdServiceInfo info(String name, String type, int port) throws Exception {
        return new NsdServiceInfo(name, type, port, "127.0.0.1");
    }
    private static void announce(Context c, int listener, NsdServiceInfo info) {
        c.nsd.listeners.get(listener).onServiceFound(info);
    }
    private static void resolve(Context c, NsdServiceInfo info) {
        c.nsd.resolutions.get(c.nsd.resolutions.size() - 1).onServiceResolved(info);
    }
    public static void main(String[] args) throws Exception {
        Context c = new Context(); AdbDiscovery d = new AdbDiscovery(c);
        check(c.nsd.scoped == 2 && c.nsd.legacy == 0 && c.nsd.lastRequest.wifi && c.nsd.lastRequest.notVpn
                && c.nsd.lastRequest.cleared, "Scope Android 13+ discovery to physical Wi-Fi without requiring internet");
        check(c.connectivity.registrations == 1, "Observe physical and VPN network changes");
        NsdServiceInfo service = info("phone", CONNECT, 37001);
        announce(c, 1, service); resolve(c, service);
        check(d.ports(false).equals(Arrays.asList(37001)), "Accept same-phone listener");
        NsdManager.DiscoveryListener retired = c.nsd.listeners.get(1);
        NsdServiceInfo pending = info("old-pending", CONNECT, 37002);
        announce(c, 1, pending);
        NsdManager.ResolveListener retiredResolve = c.nsd.resolutions.get(c.nsd.resolutions.size() - 1);
        c.connectivity.networks = new Network[]{new Network(1), new Network(4)};
        c.connectivity.callback.onAvailable(c.connectivity.networks[1]); Handler.advance(300);
        check(c.nsd.scoped == 4 && c.nsd.stops == 2 && d.ports(false).isEmpty(), "VPN enable clears stale services and restarts discovery");
        check(c.nsd.cancelled == 1, "Cancel retired Android 14+ resolutions at the platform boundary");
        retired.onServiceFound(service); retiredResolve.onServiceResolved(pending);
        check(d.ports(false).isEmpty(), "Ignore late discovery and resolution callbacks from retired network generation");
        NsdServiceInfo replacement = info("phone", CONNECT, 37009);
        announce(c, 3, replacement); resolve(c, replacement);
        retired.onServiceLost(replacement);
        check(d.ports(false).equals(Arrays.asList(37009)), "Retired service loss cannot remove a new listener with the same name");

        NsdServiceInfo lost = info("lost-during-resolve", CONNECT, 37010);
        announce(c, 3, lost); c.nsd.listeners.get(3).onServiceLost(lost); resolve(c, lost);
        check(d.ports(false).equals(Arrays.asList(37009)), "A service lost during resolution cannot be resurrected");
        NsdServiceInfo pair = info("phone", PAIR, 37100);
        announce(c, 2, pair); resolve(c, pair);
        c.nsd.listeners.get(3).onServiceLost(replacement);
        check(d.ports(false).isEmpty() && d.ports(true).equals(Arrays.asList(37100)), "Pairing and connect services with the same name have independent lifetimes");

        NsdServiceInfo remote = new NsdServiceInfo("remote", CONNECT, 37001, "192.0.2.1");
        announce(c, 3, remote); resolve(c, remote);
        NsdServiceInfo invalid = info("invalid", CONNECT, 65536);
        announce(c, 3, invalid); resolve(c, invalid);
        check(d.ports(false).isEmpty(), "Reject remote hosts and invalid ports");

        NsdServiceInfo hung = info("hung", CONNECT, 37001), following = info("following", CONNECT, 37002);
        announce(c, 3, hung);
        NsdManager.ResolveListener late = c.nsd.resolutions.get(c.nsd.resolutions.size() - 1);
        announce(c, 3, following);
        int resolutions = c.nsd.resolutions.size(); Handler.advance(3000);
        check(c.nsd.resolutions.size() == resolutions + 1, "Bound a stalled resolution so another local service can resolve");
        check(c.nsd.cancelled == 2, "Timed-out resolution also releases its platform request");
        late.onServiceResolved(hung); resolve(c, following);
        check(d.ports(false).equals(Arrays.asList(37002)), "Late timed-out resolution cannot replace the current resolution");

        // Replace a VPN on the same Network, then remove it, without relying on callbacks.
        int starts = c.nsd.scoped;
        c.connectivity.link.routes.add("vpn replacement route"); d.ports(false);
        check(c.nsd.scoped == starts + 2 && d.ports(true).isEmpty(), "Poll catches route/VPN changes when callbacks are suppressed");
        c.connectivity.networks = new Network[]{new Network(1)}; d.ports(false);
        check(c.nsd.scoped == starts + 4, "VPN disable restarts discovery");
        int registrations = c.connectivity.unregistrations;
        d.close(); d.close(); Handler.advance(5000);
        check(c.connectivity.unregistrations == registrations + 1 && Handler.pending() == 0, "Close unregisters callbacks and cancels work exactly once");
        int stopped = c.nsd.stops; retired.onServiceFound(service); retiredResolve.onServiceResolved(pending);
        check(d.ports(false).isEmpty() && c.nsd.stops == stopped, "Closed discovery never restarts or publishes late results");

        c = new Context(); c.connectivity.denied = true; c.nsd.rejectScoped = true;
        d = new AdbDiscovery(c);
        check(c.nsd.legacy == 2 && c.connectivity.registrations == 0, "Denied network readback retains legacy discovery and manual-port recovery");
        d.close();
        c = new Context(); d = new AdbDiscovery(c);
        c.nsd.listeners.get(0).onStartDiscoveryFailed(PAIR, 3); Handler.advance(1000);
        check(c.nsd.legacy == 2, "Asynchronous scoped-discovery failure falls back to legacy discovery");
        d.close();
        Build.VERSION.SDK_INT = 30; c = new Context(); d = new AdbDiscovery(c);
        check(c.nsd.legacy == 2 && c.nsd.scoped == 0, "Android 11 retains supported discovery APIs");
        c.connectivity.networks = new Network[]{new Network(1), new Network(4)}; d.ports(false);
        check(c.nsd.legacy == 4, "Android 11 also refreshes discovery on VPN changes"); d.close();
        check(Handler.pending() == 0, "Every discovery owner releases scheduled work");
        System.out.println("PASS ADB discovery assertions: " + checks);
    }
}
