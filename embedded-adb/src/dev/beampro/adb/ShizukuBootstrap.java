package dev.beampro.adb;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

/** Permission consent stays in Shizuku; normal app/overlay permissions stay in Android. */
final class ShizukuBootstrap {
    private static final int REQUEST = 7319;
    private static final java.util.concurrent.ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static Context app;
    private static boolean initialized, permissionPending;
    private static volatile boolean busy;
    private static volatile String message;
    private static ServiceConnection connection;
    private static Shizuku.UserServiceArgs arguments;
    private static int attempt;

    static boolean busy() { return busy || permissionPending; }
    static void initialize(Context source) {
        Context context = source.getApplicationContext();
        AdbBootstrap.MAIN.post(() -> {
            if (initialized) return;
            initialized = true; app = context;
            Shizuku.addRequestPermissionResultListener((request, grant) -> {
                if (request != REQUEST) return;
                permissionPending = false;
                if (grant == PackageManager.PERMISSION_GRANTED && AdbBootstrap.usesShizuku(app)) start(app);
                else message = "Shizuku access denied. Allow Unbound in Shizuku > Authorized applications, then try again.";
            });
            Shizuku.addBinderDeadListener(() -> {
                permissionPending = false;
                finish(attempt, "Shizuku stopped. Start it again, then tap Allow Shizuku and start.");
            });
            Shizuku.addBinderReceivedListenerSticky(() -> {
                if (AdbBootstrap.usesShizuku(app) && !AdbBootstrap.needsSetup(app) && authorized()) start(app);
            });
        });
    }
    private static boolean authorized() {
        try { return Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.getVersion() >= 13
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException unavailable) { return false; }
    }
    static String status() {
        if (message != null) return message;
        if (!Shizuku.pingBinder()) return "Install and start Shizuku, then allow Unbound to use it.";
        return authorized() ? "Shizuku is available. Tap Allow Shizuku and start." : "Tap Allow Shizuku and start, then approve the permission request.";
    }
    static void request(Context context) {
        initialize(context);
        AdbBootstrap.MAIN.post(() -> {
            if (busy() || AdbBootstrap.busy() || AdbBootstrap.ready(context)) return;
            try {
                if (!Shizuku.pingBinder()) { message = "Shizuku is not running. Open Shizuku and start it first."; return; }
                if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) { message = "Update Shizuku: version 13 or newer is required."; return; }
                if (!supportedIdentity()) { message = "Shizuku must run as root or Android shell. Restart Shizuku and try again."; return; }
                if (authorized()) { start(context); return; }
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    message = "Allow Unbound in Shizuku > Authorized applications, then try again."; return;
                }
                permissionPending = true; message = "Waiting for Shizuku permission...";
                Shizuku.requestPermission(REQUEST);
            } catch (RuntimeException error) { permissionPending = false; message = "Shizuku permission request failed. Restart Shizuku and try again."; }
        });
    }
    static void connect(Context context) {
        initialize(context);
        AdbBootstrap.MAIN.post(() -> { if (authorized()) start(context); });
    }
    private static void start(Context source) {
        if (!AdbBootstrap.usesShizuku(source) || busy() || AdbBootstrap.busy() || AdbBootstrap.ready(source)) return;
        if (!authorized()) return;
        if (!supportedIdentity()) { message = "Shizuku must run as root or Android shell."; return; }
        Context context = source.getApplicationContext();
        busy = true; message = "Starting AR app windows through Shizuku...";
        final int current = ++attempt;
        arguments = new Shizuku.UserServiceArgs(new ComponentName(context, ShizukuStarterService.class))
                .tag("unbound-helper-starter").daemon(false).processNameSuffix("unbound_starter")
                .debuggable(false).version(dev.beampro.settings.AppVersion.CODE);
        connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (current != attempt || !busy) return;
                WORK.execute(() -> {
                    String error = null;
                    try {
                        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                        try {
                            data.writeInterfaceToken(ShizukuStarterService.TOKEN);
                            if (!binder.transact(ShizukuStarterService.START, data, reply, 0)) throw new IllegalStateException("Starter unavailable");
                            reply.readException();
                        } finally { data.recycle(); reply.recycle(); }
                        long until = SystemClock.elapsedRealtime() + 12000;
                        while (!AdbBootstrap.ready(context) && SystemClock.elapsedRealtime() < until) Thread.sleep(200);
                        if (!AdbBootstrap.ready(context)) throw new IllegalStateException("AR helper did not become ready");
                    } catch (Exception failure) {
                        android.util.Log.w("UnboundShizuku", "Helper startup failed", failure);
                        error = "Shizuku could not start the AR helper. " + failure.getMessage();
                    }
                    final String result = error;
                    AdbBootstrap.MAIN.post(() -> {
                        if (current != attempt || !busy) return;
                        finish(current, result);
                        if (result == null) AutoGlassesReceiver.helperReady(context);
                    });
                });
            }
            public void onServiceDisconnected(ComponentName name) {
                if (busy) finish(current, "Shizuku starter disconnected. Start Shizuku and try again.");
            }
        };
        AdbBootstrap.MAIN.postDelayed(() -> {
            if (busy && current == attempt) finish(current, "Shizuku startup timed out. Restart Shizuku and try again.");
        }, 45000);
        try { Shizuku.bindUserService(arguments, connection); }
        catch (RuntimeException error) { finish(current, "Shizuku starter could not connect. Restart Shizuku and try again."); }
    }
    private static void finish(int current, String result) {
        if (current != attempt) return;
        busy = false; message = result;
        ServiceConnection old = connection; connection = null;
        Shizuku.UserServiceArgs args = arguments; arguments = null;
        if (old != null) try { Shizuku.unbindUserService(args, old, true); } catch (RuntimeException ignored) {}
    }
    private static boolean supportedIdentity() {
        int uid = Shizuku.getUid();
        return uid == 0 || uid == 2000;
    }
    private ShizukuBootstrap() {}
}
