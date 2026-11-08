package dev.beampro.adb;

import android.content.Context;
import android.net.Network;
import android.os.IBinder;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;

/** Real bootstrap behavior with deterministic platform and ADB transport boundaries. */
public final class MobileAdbTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static Context context() {
        Context c = new Context(); c.resolver.settings.put("adb_enabled", 1); c.resolver.settings.put("boot_count", 4);
        c.getSharedPreferences("unbound-adb", 0).edit().putBoolean("paired", true).apply();
        return c;
    }
    private static void start(Context c, LocalAdb adb, int port, AdbDiscovery discovery) throws Exception {
        Method method = AdbBootstrap.class.getDeclaredMethod("start", Context.class, LocalAdb.class, int.class, AdbDiscovery.class);
        method.setAccessible(true);
        try { method.invoke(null, c, adb, port, discovery); }
        catch (InvocationTargetException error) { throw (Exception) error.getCause(); }
    }
    private static void fails(Context c, LocalAdb adb, String message) throws Exception {
        try { start(c, adb, 37001, null); throw new AssertionError("Unexpected bootstrap success"); }
        catch (java.io.IOException expected) { check(expected.getMessage().contains(message), "Actionable startup failure: " + expected); }
        check(adb.starts == 0 && !AdbBootstrap.ready(c), "Failed prerequisite cannot publish Ready");
    }
    private static void tests() throws Exception {
        Context c = context();
        c.connectivity.networks = new Network[]{new Network(0), new Network(3), new Network(4)};
        check(Boolean.FALSE.equals(AdbNetworkState.wifiConnected(c)), "Cellular, VPN and glasses Ethernet do not satisfy Wireless debugging");
        c.connectivity.networks = new Network[]{new Network(0), new Network(1)};
        check(Boolean.TRUE.equals(AdbNetworkState.wifiConnected(c)), "Wi-Fi is usable when cellular is also present; no validation requirement");
        c.connectivity.denied = true;
        check(AdbNetworkState.wifiConnected(c) == null, "Denied connectivity readback permits fallback discovery");
        c.connectivity.denied = false;
        c.resolver.denied = true;
        check(AdbNetworkState.usbDebugging(c) == -1 && AdbNetworkState.bootCount(c) == -1, "Unknown platform settings never claim mobile readiness");
        c.resolver.denied = false;
        check(!AdbNetworkState.restarted(c), "No previous helper is not evidence of a reboot");
        c.getSharedPreferences("unbound-adb", 0).edit().putInt("helper-boot-count", 3).apply();
        check(AdbNetworkState.restarted(c) && AdbNetworkState.recovery(c).startsWith("Phone restarted."), "Reboot has a specific recovery path");
        c.resolver.settings.remove("boot_count");
        check(!AdbNetworkState.restarted(c), "Unknown boot count does not report a reboot");

        c = context(); LocalAdb adb = new LocalAdb(c); adb.usb = "0";
        fails(c, adb, "Enable USB debugging");
        check(adb.commands.equals(Arrays.asList("shell:id -u", "shell:settings get global adb_enabled")), "Check retention before helper extraction; never change debugging settings");
        adb = new LocalAdb(c); adb.usb = "null"; fails(c, adb, "Enable USB debugging");
        adb = new LocalAdb(c); adb.uid = "10000";
        try { start(c, adb, 37001, null); throw new AssertionError("Wrong UID accepted"); }
        catch (SecurityException expected) { check(adb.commands.size() == 1 && adb.starts == 0, "Verify shell UID before checking USB debugging"); }

        c = context(); c.resolver.denied = true; adb = new LocalAdb(c);
        int discoveries = AdbDiscovery.created;
        start(c, adb, 37001, null);
        check(AdbBootstrap.ready(c) && adb.starts == 1, "Shell verification works when app settings readback is denied");
        check(AdbDiscovery.created == discoveries, "Manual local listener bypasses discovery");
        check(adb.commands.get(2).contains("nohup /system/bin/setsid") && adb.commands.get(2).contains("sha256sum"), "Preserve detached and digest-checked helper launch");

        c = context(); adb = new LocalAdb(c); c.getSharedPreferences("unbound-adb", 0).edit().putInt("connect-port", 37001).apply();
        c.connectivity.networks = new Network[]{new Network(0)};
        start(c, adb, 0, null);
        check(AdbBootstrap.ready(c) && AdbDiscovery.created == discoveries, "Saved loopback listener starts helper without Wi-Fi or mDNS");
        check(c.getSharedPreferences("unbound-adb", 0).getInt("helper-boot-count", -1) == 4, "Remember the successful helper's boot");
        check(AdbNetworkState.mobileStatus(c, true).startsWith("Ready to use without Wi-Fi"), "Mobile status is prepared only with live helper and USB debugging");
        check(AdbBootstrap.status(c).startsWith("Ready"), "Cellular-only connection retains Ready");
        int starts = adb.starts, connections = adb.attempts.size();
        c.connectivity.denied = true; c.resolver.settings.put("adb_enabled", 0);
        start(c, adb, 0, null);
        check(adb.starts == starts && adb.attempts.size() == connections && AdbBootstrap.ready(c), "A live Binder survives loss of discovery and changed debugging settings");
        check(AdbNetworkState.mobileStatus(c, true).startsWith("Turn on USB debugging"), "Warn before disconnecting Wi-Fi if USB debugging was disabled");
        c.resolver.binder = null; c.connectivity.denied = false; c.connectivity.networks = new Network[]{new Network(1)};
        check(!AdbBootstrap.status(c).startsWith("Ready") && AdbBootstrap.status(c).contains("not running"),
                "Helper death on Wi-Fi cannot retain a stale Ready message");

        c = context(); c.connectivity.networks = new Network[]{new Network(0)}; adb = new LocalAdb(c);
        fails(c, new LocalAdb(c) {{ accepted = 37002; }}, "Connect to Wi-Fi");
        check(AdbDiscovery.created == discoveries && !AdbBootstrap.ready(c), "Offline failure does not wait for mDNS or claim Ready from pairing");
        check(AdbBootstrap.status(c).contains("Connect to Wi-Fi"), "Paired identity alone cannot provide offline privileges");
        c.resolver.binder = new IBinder() {public boolean isBinderAlive(){return true;}public boolean pingBinder(){return false;}};
        check(!AdbBootstrap.ready(c), "Unresponsive Binder is not Ready");

        c = context(); adb = new LocalAdb(c); adb.accepted = 37002;
        c.getSharedPreferences("unbound-adb", 0).edit().putInt("connect-port", 37001).apply();
        int closed = AdbDiscovery.closed;
        start(c, adb, 0, null);
        check(adb.attempts.equals(Arrays.asList(37001, 37002)), "Failed cached port falls back to fresh discovery");
        check(AdbDiscovery.created == discoveries + 1 && AdbDiscovery.closed == closed + 1, "Own discovery closes after successful bootstrap");

        c = context(); adb = new LocalAdb(c); adb.accepted = 37002;
        c.getSharedPreferences("unbound-adb", 0).edit().putInt("connect-port", 37001).putInt("helper-boot-count", 3).apply();
        AdbDiscovery supplied = new AdbDiscovery(c); closed = AdbDiscovery.closed;
        start(c, adb, 0, supplied);
        check(adb.attempts.equals(Arrays.asList(37002)), "Never reuse a remembered port from a previous boot");
        check(AdbDiscovery.closed == closed, "Caller owns supplied discovery");
        check(!AdbNetworkState.restarted(c), "Successful bootstrap clears reboot recovery state");

        c = context(); adb = new LocalAdb(c); adb.accepted = 1;
        AdbDiscovery.found = Arrays.asList(37001, 37001, -1, 65536);
        closed = AdbDiscovery.closed;
        try { start(c, adb, 37001, null); throw new AssertionError("Unavailable listener accepted"); }
        catch (java.io.IOException expected) {
            check(adb.attempts.equals(Arrays.asList(37001, 37001, 37001)), "Reject invalid ports and bound retries of duplicate announcements");
            check(AdbDiscovery.closed == closed + 1, "Own discovery closes after connection failure");
        }
    }
    public static void main(String[] args) throws Exception {
        try { tests(); System.out.println("PASS mobile ADB assertions: " + checks); }
        finally {
            for (String name : new String[]{"WORK", "WATCHDOG"}) {
                java.lang.reflect.Field field = AdbBootstrap.class.getDeclaredField(name); field.setAccessible(true);
                ((ExecutorService) field.get(null)).shutdownNow();
            }
        }
    }
}
