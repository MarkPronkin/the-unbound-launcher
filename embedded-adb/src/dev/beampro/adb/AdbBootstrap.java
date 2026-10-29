package dev.beampro.adb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import io.github.muntashirakon.adb.AdbStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import dev.beampro.settings.SetupNavigation;

/** Starts only the helper shipped in this APK, on this same device, after system pairing. */
public final class AdbBootstrap {
    public static final String AUTHORITY = "dev.markpronkin.unbound.unbound.bridge";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor();
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;
    private static volatile String status = "Pair this phone to enable AR app windows.";
    private static boolean attempted;

    public static void open(Activity activity) { dev.beampro.settings.MenuTransitions.open(activity, new Intent(activity, AdbPairingActivity.class)); }
    public static boolean needsSetup(Context context) {
        return !context.getSharedPreferences("unbound-adb", 0).getBoolean("setup-complete", false);
    }
    public static void setup(Activity activity) {
        activity.startActivity(SetupNavigation.forward(activity, AdbPairingActivity.class)
                .putExtra(AdbPairingActivity.SETUP, true));
    }
    public static void completed(Activity activity) {
        Context context = activity.getApplicationContext();
        MAIN.postDelayed(() -> AutoGlassesReceiver.helperReady(context), 1000);
    }
    public static boolean allowLaunch(Activity activity) {
        if (ready(activity)) return true;
        open(activity); return false;
    }
    public static void install(Activity activity) {
        if (attempted || Build.VERSION.SDK_INT < 30) return;
        attempted = true;
        if (paired(activity) && !ready(activity)) connect(activity, 0, null);
    }
    static boolean paired(Context context) {
        return context.getSharedPreferences("unbound-adb", 0).getBoolean("paired", false);
    }
    static boolean ready(Context context) {
        try {
            Bundle result = context.getContentResolver().call(Uri.parse("content://" + AUTHORITY), "get", null, null);
            IBinder binder = result == null ? null : result.getBinder("binder");
            return binder != null && binder.isBinderAlive() && binder.pingBinder();
        } catch (Exception absent) { return false; }
    }
    static boolean busy() { return busy; }
    static String status(Context context) {
        if (ready(context)) return "Ready · AR app windows are enabled";
        if (paired(context) && status.equals("Pair this phone to enable AR app windows.")) return "Paired · Enable Wireless debugging, then tap Connect and start.";
        return status;
    }
    static synchronized void pair(Context source, String code, int pairPort, int connectPort, AdbDiscovery discovery, Runnable done) {
        pair(source, code, pairPort, connectPort, discovery, null, done);
    }
    static synchronized void pair(Context source, String code, int pairPort, int connectPort, AdbDiscovery discovery, Runnable accepted, Runnable done) {
        if (busy) return;
        if (!code.matches("[0-9]{6}")) { status = "Enter Android's six-digit pairing code."; return; }
        int chosen = pairPort;
        if (chosen == 0 && discovery != null) {
            List<Integer> found = discovery.ports(true);
            if (found.size() == 1) chosen = found.get(0);
        }
        if (chosen < 1 || chosen > 65535) {
            status = "Enter the pairing port shown in Android's pairing-code dialog."; return;
        }
        final int port = chosen;
        final Context context = source.getApplicationContext();
        busy = true; status = "Pairing with this phone…";
        WORK.execute(() -> {
            LocalAdb adb = null;
            boolean pairedNow = false;
            try {
                adb = new LocalAdb(context);
                if (!adb.pair("127.0.0.1", port, code)) throw new IOException("Android rejected pairing");
                pairedNow = true;
                context.getSharedPreferences("unbound-adb", 0).edit().putBoolean("paired", true).apply();
                status = "Paired. Starting AR app windows…";
                if (accepted != null) MAIN.post(accepted);
                start(context, adb, connectPort, discovery);
            } catch (Throwable error) { failure(context, pairedNow ? "Connection after pairing" : "Pairing", error); }
            finally { disconnect(adb); busy = false; if (done != null && pairedNow) MAIN.post(done); }
        });
    }
    static synchronized void connect(Context source, int port, AdbDiscovery discovery) {
        if (busy) return;
        final Context context = source.getApplicationContext();
        busy = true; status = "Connecting to this phone…";
        WORK.execute(() -> {
            LocalAdb adb = null;
            AdbDiscovery own = null;
            try {
                if (ready(context)) return;
                adb = new LocalAdb(context);
                if (discovery == null) own = new AdbDiscovery(context);
                start(context, adb, port, discovery == null ? own : discovery);
            } catch (Throwable error) { failure(context, "Connection", error); }
            finally { disconnect(adb); if (own != null) own.close(); busy = false; }
        });
    }
    private static void start(Context context, LocalAdb adb, int manualPort, AdbDiscovery discovery) throws Exception {
        if (ready(context)) return;
        LinkedHashSet<Integer> ports = new LinkedHashSet<>();
        if (manualPort > 0) ports.add(manualPort);
        if (discovery != null) {
            long until = SystemClock.elapsedRealtime() + 5000;
            while (discovery.ports(false).isEmpty() && SystemClock.elapsedRealtime() < until) Thread.sleep(150);
            ports.addAll(discovery.ports(false));
        }
        int saved = context.getSharedPreferences("unbound-adb", 0).getInt("connect-port", 0);
        if (saved > 0) ports.add(saved);
        if (ports.isEmpty()) throw new StartupFailure("Connection port not found. Enable Wireless debugging, or enter the port from its main screen under Other ways to pair → Connection port.");
        Exception last = null;
        int connectedPort = 0;
        for (int candidate : ports) {
            if (candidate < 1 || candidate > 65535) continue;
            try {
                if (adb.connect("127.0.0.1", candidate)) { connectedPort = candidate; break; }
                throw new IOException("Android did not authorize this connection");
            } catch (Exception error) { last = error; adb.disconnect(); }
        }
        if (connectedPort == 0) throw new StartupFailure("ADB connection failed. Check Wireless debugging and its current connection port. Pair again if Android forgot this app.", last);
        if (!"2000".equals(shell(adb, "id -u").trim())) throw new SecurityException("ADB did not provide Android shell access");
        String apk = quote(context.getApplicationInfo().sourceDir);
        String dir = "/data/local/tmp/" + context.getPackageName();
        String jar = dir + "/helper-" + HelperPayload.SHA256 + ".jar";
        String command = "mkdir -p " + dir + " && chmod 700 " + dir
                + " && rm -f " + jar + ".new"
                + " && unzip -p " + apk + " assets/unbound-shell.jar > " + jar + ".new"
                + " && [ \"$(sha256sum " + jar + ".new | cut -d' ' -f1)\" = " + quote(HelperPayload.SHA256) + " ]"
                + " && chmod 444 " + jar + ".new && mv -f " + jar + ".new " + jar
                + " && (CLASSPATH=" + jar + " /system/bin/nohup /system/bin/setsid /system/bin/app_process / dev.beampro.bridge.LaunchServer --embedded"
                + " > " + dir + "/helper.log 2>&1 < /dev/null & sleep 0.3) && echo UNBOUND_HELPER_STARTED";
        status = "Starting the bundled launch helper…";
        String output = shell(adb, command);
        if (!output.contains("UNBOUND_HELPER_STARTED")) {
            android.util.Log.e("UnboundADB", "Helper extraction/start failed: " + output);
            throw new StartupFailure("ADB connected, but the bundled helper could not be installed or started. Retry Connect and start. If it still fails, update Unbound.");
        }
        long until = SystemClock.elapsedRealtime() + 12000;
        while (!ready(context) && SystemClock.elapsedRealtime() < until) Thread.sleep(200);
        if (!ready(context)) {
            String log = shell(adb, "tail -c 3000 " + dir + "/helper.log");
            android.util.Log.e("UnboundADB", "Bundled helper failed: " + log);
            throw new StartupFailure("ADB connected, but the AR launch helper did not become ready. Retry Connect and start. If it still fails, update Unbound.");
        }
        context.getSharedPreferences("unbound-adb", 0).edit().putInt("connect-port", connectedPort).apply();
        status = "Ready · AR app windows are enabled";
        AutoGlassesReceiver.helperReady(context);
    }
    private static String shell(LocalAdb adb, String command) throws Exception {
        ScheduledFuture<?> timeout = WATCHDOG.schedule(() -> disconnect(adb), 20, TimeUnit.SECONDS);
        try (AdbStream stream = adb.openStream("shell:" + command); InputStream in = stream.openInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 32768) throw new IOException("Unexpected helper output");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally { timeout.cancel(false); }
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static void disconnect(LocalAdb adb) {
        if (adb != null) try { adb.disconnect(); } catch (Exception ignored) {}
    }
    private static void failure(Context context, String action, Throwable error) {
        android.util.Log.w("UnboundADB", action + " failed", error);
        if (error instanceof StartupFailure) { status = error.getMessage(); return; }
        status = action + " failed. " + (paired(context)
                ? "Enable Wireless debugging, then tap Connect and start. If Android forgot this app, pair again."
                : "Keep Android's pairing-code dialog open, check the code and pairing port, and try again.");
    }
    private static final class StartupFailure extends IOException {
        StartupFailure(String message) { super(message); }
        StartupFailure(String message, Throwable cause) { super(message, cause); }
    }
}
