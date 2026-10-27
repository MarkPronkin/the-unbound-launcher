package dev.beampro.bridge;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import dev.beampro.settings.NetworkDiagnostics;
import dev.beampro.settings.DisplayDiagnostics;

/** Hosts the original launch Binder under shell UID with signature-checked access. */
public final class LaunchServer {
    private static final boolean ENABLE_DISPLAY_MANAGEMENT = false;
    private static final IBinder PROVIDER_TOKEN = new Binder();
    private static final java.util.Set<Integer> DROPPED_INPUT_DISPLAYS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());
    private static Object externalProvider;
    private static final ExecutorService LAUNCHES = Executors.newSingleThreadExecutor();
    private static RecentAppsStore recentApps;
    private static RecentAppPreviews recentPreviews;
    private static final java.util.concurrent.ScheduledExecutorService PREVIEWS = Executors.newSingleThreadScheduledExecutor();
    public static void main(String[] args) {
        try { run(); }
        catch (Throwable error) { error.printStackTrace(System.err); System.exit(1); }
    }

    private static void run() throws Exception {
        System.out.println("BEAM_LAUNCH_SERVER_START pid=" + android.os.Process.myPid());
        if (android.os.Process.myUid() != 2000) throw new SecurityException("Start this helper through ADB shell");
        recentApps = new RecentAppsStore(new java.io.File("/data/local/tmp/beampro-recent-apps.bin"));
        Looper.prepareMainLooper();
        Context shell = createShellContext();
        Class<?> managerType = Class.forName("ai.nreal.nebula.service.LaunchManager");
        Object manager = managerType.getMethod("getInstance").invoke(null);
        Method init = managerType.getDeclaredMethod("init", Context.class);
        init.setAccessible(true);
        if (!Boolean.TRUE.equals(init.invoke(manager, new WindowContext(shell)))) throw new IllegalStateException("LaunchManager initialization failed");
        System.out.println("BEAM_LAUNCH_SERVER_INITIALIZED");
        recentPreviews = new RecentAppPreviews(shell, manager, recentApps, new java.io.File("/data/local/tmp"));
        recentPreviews.prune();
        IBinder raw = (IBinder) manager;
        PackageManager packages = shell.getPackageManager();
        int probeUid = packages.getApplicationInfo("dev.beampro.portprobe", 0).uid;
        XrealUsbPermissions usb = ENABLE_DISPLAY_MANAGEMENT ? new XrealUsbPermissions(packages, probeUid) : null;
        AutoArEntry autoEntry = ENABLE_DISPLAY_MANAGEMENT ? new AutoArEntry(packages, probeUid, usb) : null;
        PhoneScreenPower phoneScreen = new PhoneScreenPower(shell);
        GlassesNetworkProbe glassesNetwork = new GlassesNetworkProbe(probeUid);
        DisplayDumpPipe displayDumps = new DisplayDumpPipe(probeUid);
        AppClassification classification = new AppClassification(packages, probeUid,
                new java.io.File("/data/local/tmp/unbound-category-" + probeUid + ".bin"));
        IBinder guarded = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                int caller = Binder.getCallingUid();
                if (caller != 2000 && caller != probeUid && packages.checkSignatures(caller, probeUid) != PackageManager.SIGNATURE_MATCH) {
                    throw new SecurityException("Caller does not share the local port signing certificate");
                }
                long identity = Binder.clearCallingIdentity();
                try {
                    if (code == DisplayDiagnostics.TRANSACTION) {
                        data.enforceInterface(DisplayDiagnostics.TOKEN);
                        try (ParcelFileDescriptor pipe = displayDumps.open(caller, data.readInt())) {
                            reply.writeNoException(); pipe.writeToParcel(reply, 0);
                        } catch (SecurityException denied) { throw denied; }
                        catch (java.io.IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
                        return true;
                    }
                    if (code == GlassesNetworkProbe.TRANSACTION) {
                        data.enforceInterface(GlassesNetworkProbe.TOKEN);
                        ShellCommand.Result result;
                        try { result=glassesNetwork.ping(caller); }
                        catch (SecurityException denied) { throw denied; }
                        catch (Exception error) { result=new ShellCommand.Result(-1,false,error.toString()); }
                        reply.writeNoException();reply.writeInt(result.exitCode);reply.writeInt(result.timedOut?1:0);
                        reply.writeString(result.output.substring(0,Math.min(2048,result.output.length())));
                        return true;
                    }
                    if (code == NetworkDiagnostics.TRANSACTION) {
                        data.enforceInterface(NetworkDiagnostics.TOKEN);
                        if (caller != probeUid) throw new SecurityException("Network diagnostics are restricted to the installed launcher");
                        String report = NetworkDiagnostics.capture(true);
                        reply.writeNoException(); reply.writeString(report);
                        return true;
                    }
                    if (code == AppClassification.TRANSACTION) {
                        data.enforceInterface(AppClassification.TOKEN);
                        int category = -1;
                        String error = null;
                        try { category = classification.request(caller, data.readInt()); }
                        catch (Exception failure) { error = failure.getMessage() == null ? failure.toString() : failure.getMessage(); }
                        reply.writeNoException(); reply.writeString(error); reply.writeInt(category);
                        return true;
                    }
                    if (code == AutoArEntry.TRANSACTION && autoEntry != null) {
                        data.enforceInterface(AutoArEntry.TOKEN);
                        boolean attached=data.readInt()!=0;
                        AutoArEntry.Key key=new AutoArEntry.Key(data.readString(),data.readInt(),data.readInt(),data.readInt());
                        String error=null;
                        try { autoEntry.request(caller,key,attached); }
                        catch(Exception failure) { error=failure.toString(); }
                        reply.writeNoException();reply.writeString(error);
                        return true;
                    }
                    if (code == XrealUsbPermissions.TRANSACTION && usb != null) {
                        data.enforceInterface(XrealUsbPermissions.TOKEN);
                        String name=data.readString();
                        int vendor=data.readInt(),product=data.readInt(),deviceId=data.readInt();
                        boolean granted=false;
                        String error=null;
                        try { granted=usb.grant(caller, name, vendor, product, deviceId); }
                        catch (Exception failure) { failure.printStackTrace(System.err);error="XREAL USB access: "+failure; }
                        reply.writeNoException();reply.writeString(error);reply.writeInt(granted?1:0);
                        return true;
                    }
                    if (code == PhoneScreenPower.TRANSACTION) {
                        data.enforceInterface(PhoneScreenPower.TOKEN);
                        try {
                            phoneScreen.request(data.readInt(), data.readStrongBinder(), data.readInt());
                            reply.writeNoException();
                            reply.writeString(null);
                        } catch (Exception error) {
                            reply.writeNoException();
                            reply.writeString(error.getMessage() == null ? error.toString() : error.getMessage());
                        }
                        return true;
                    }
                    if (code == 0x00FFFFF9) {
                        data.enforceInterface("dev.beampro.bridge.RECENTS");
                        byte[] preview = recentPreviews.get(data.readInt());
                        reply.writeNoException();
                        reply.writeByteArray(preview);
                        return true;
                    }
                    if (code == 0x00FFFFFA) {
                        data.enforceInterface("dev.beampro.bridge.RECENTS");
                        java.util.List<RecentAppsStore.Entry> entries = recentApps.list();
                        reply.writeNoException();
                        reply.writeInt(entries.size());
                        for (RecentAppsStore.Entry entry : entries) {
                            reply.writeInt(entry.id);
                            reply.writeString(entry.packageName);
                            reply.writeLong(entry.lastOpened);
                        }
                        return true;
                    }
                    if (code == 0x00FFFFFB) {
                        data.enforceInterface("dev.beampro.bridge.RECENTS");
                        int id = data.readInt();
                        try { recentApps.remove(id); }
                        catch (java.io.IOException error) { System.err.println("BEAM_RECENTS_SAVE_FAILED " + error); }
                        recentPreviews.prune();
                        reply.writeNoException();
                        return true;
                    }
                    if (code == 0x00FFFFFC) {
                        data.enforceInterface("dev.beampro.bridge.RADIO");
                        android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) shell.getSystemService(Context.WIFI_SERVICE);
                        android.net.wifi.WifiInfo info = wifi == null || !wifi.isWifiEnabled() ? null : wifi.getConnectionInfo();
                        reply.writeNoException();
                        reply.writeString(info == null || info.getNetworkId() < 0 ? null : info.getSSID());
                        return true;
                    }
                    if (code == 0x00FFFFFD) {
                        data.enforceInterface("dev.beampro.bridge.RADIO");
                        int radio = data.readInt();
                        if (radio != 1 && radio != 2) throw new IllegalArgumentException("Unknown settings tile");
                        // Only the two settings actions are accepted, and only an existing
                        // helper-owned window can receive the new Intent. Initial launches
                        // still go through Unity's normal window creation and app limit.
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                Intent target = new Intent(radio == 1 ? android.provider.Settings.ACTION_WIFI_SETTINGS
                                        : android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                                android.content.ComponentName component = target.resolveActivity(packages);
                                if (component == null) return;
                                Method find = managerType.getDeclaredMethod("findActivityContent", android.content.ComponentName.class);
                                find.setAccessible(true);
                                Object content = find.invoke(manager, component);
                                if (content == null) return;
                                Method getDisplay = content.getClass().getDeclaredMethod("getDisplayId");
                                getDisplay.setAccessible(true);
                                int display = (Integer) getDisplay.invoke(content);
                                if (display < 1) return;
                                Intent proxy = new Intent();
                                proxy.putExtra("target_intent", target);
                                proxy.putExtra("target_display", display);
                                new WindowContext(shell).startActivity(proxy, null);
                                System.out.println("BEAM_SETTINGS_REOPEN radio=" + radio + " display=" + display);
                            } catch (Exception error) { error.printStackTrace(System.err); }
                        });
                        reply.writeNoException();
                        return true;
                    }
                    if (code == 0x00FFFFFE) {
                        data.enforceInterface("dev.beampro.bridge.CONTROL");
                        recentPreviews.captureAll();
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                managerType.getMethod("unregisterLaunchListener", Class.forName("ai.nreal.nebula.service.ILaunchListener"))
                                        .invoke(manager, new Object[]{null});
                                Method close = managerType.getDeclaredMethod("requestCloseAll");
                                close.setAccessible(true);
                                close.invoke(manager);
                                System.out.println("BEAM_WINDOW_CLEANUP complete");
                            } catch (Exception error) { error.printStackTrace(System.err); }
                        });
                        reply.writeNoException();
                        return true;
                    }
                    if (code == 2) {
                        int position = data.dataPosition();
                        data.enforceInterface("ai.nreal.nebula.service.ILaunchManager");
                        android.content.ComponentName component = data.readInt() == 0 ? null
                                : android.content.ComponentName.CREATOR.createFromParcel(data);
                        data.setDataPosition(position);
                        if (component != null) recentPreviews.capturePackage(component.getPackageName());
                    }
                    if (code == 3) {
                        int position = data.dataPosition();
                        data.enforceInterface("ai.nreal.nebula.service.ILaunchManager");
                        data.setDataPosition(data.dataSize() - 4);
                        int targetDisplay = data.readInt();
                        data.setDataPosition(position);
                        try {
                            // findActivityContent retries missing displays for ~500 ms.
                            // findApp checks the same ownership map immediately.
                            Method find = managerType.getDeclaredMethod("findApp", int.class);
                            find.setAccessible(true);
                            if (targetDisplay <= 0 || find.invoke(manager, targetDisplay) == null) {
                                // Closing a window can leave a queued focus/touch event for
                                // the physical glasses display or a retired virtual display.
                                // The original boolean contract permits rejecting it normally;
                                // a Java exception crossing Unity's JNI boundary aborts Unity.
                                if (DROPPED_INPUT_DISPLAYS.add(targetDisplay)) {
                                    System.out.println("BEAM_INPUT_DROPPED unmanaged_display=" + targetDisplay);
                                }
                                reply.writeNoException();
                                reply.writeInt(0);
                                return true;
                            }
                        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                    }
                    return raw.transact(code, data, reply, flags);
                }
                finally { Binder.restoreCallingIdentity(identity); }
            }
        };
        Bundle payload = new Bundle();
        payload.putBinder("binder", guarded);
        System.out.println("BEAM_LAUNCH_SERVER_PUBLISHING");
        Object activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Method acquire = Class.forName("android.app.IActivityManager").getMethod("getContentProviderExternal",
                String.class, int.class, IBinder.class, String.class);
        Object holder = acquire.invoke(activityManager, "dev.beampro.portprobe.bridge", 0, PROVIDER_TOKEN, "beampro-launch-helper");
        if (holder == null) throw new IllegalStateException("Bridge provider unavailable");
        externalProvider = holder.getClass().getField("provider").get(holder);
        Bundle result = ProviderCallCompat.publish(externalProvider, Class.forName("android.content.IContentProvider"),
                ProviderCallCompat.shellAttribution(android.os.Process.myUid()), "dev.beampro.portprobe.bridge", payload);
        if (result == null || !result.getBoolean("published")) throw new IllegalStateException("Binder publication failed");
        System.out.println("BEAM_LAUNCH_SERVER_READY pid=" + android.os.Process.myPid() + " uid=" + android.os.Process.myUid());
        System.out.flush();
        if (ENABLE_DISPLAY_MANAGEMENT) ManagedDisplay.start(shell);
        Looper.loop();
    }

    static Context createShellContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context shell = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        Field opPackage = shell.getClass().getDeclaredField("mOpPackageName");
        opPackage.setAccessible(true); opPackage.set(shell, "com.android.shell");
        Object source = ProviderCallCompat.shellAttribution(android.os.Process.myUid());
        if (source != null) {
            setField(shell, "mAttributionSource", source);
            setField(shell.getContentResolver(), "mAttributionSource", source);
        }
        setField(shell.getContentResolver(), "mPackageName", "com.android.shell");
        System.out.println("BEAM_LAUNCH_SERVER_CONTEXT " + shell.getPackageName() + " " + shell.getOpPackageName());
        return shell;
    }

    private static void setField(Object owner, String name, Object value) throws IllegalAccessException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(owner, value);
                return;
            } catch (NoSuchFieldException ignored) {}
        }
        System.out.println("BEAM_OPTIONAL_FIELD_ABSENT " + name);
    }

    private static final class WindowContext extends ContextWrapper {
        WindowContext(Context shell) { super(shell); }
        @Override public String getPackageName() { return "dev.markpronkin.unbound"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public Context getApplicationContext() { return this; }

        @Override public void startActivity(Intent proxy, Bundle options) {
            Intent target = proxy.getParcelableExtra("target_intent");
            int display = proxy.getIntExtra("target_display", -1);
            if (target == null || display < 1) throw new IllegalArgumentException("Expected a spatial app launch on a helper display");
            Intent launch = SpatialLaunchIntent.prepare(target, getPackageManager());
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            // Context.startActivity normally returns before an app draws. Waiting with
            // am -W on the display-event Looper killed the entire helper on slow apps.
            LAUNCHES.execute(() -> {
                try {
                    android.hardware.display.DisplayManager displays = getSystemService(android.hardware.display.DisplayManager.class);
                    if (displays.getDisplay(display) == null) return; // Window closed while queued.
                    ShellCommand.Result result = ShellCommand.run(10000, "am", "start", "--user", "0", "--display",
                            Integer.toString(display), launch.toUri(Intent.URI_INTENT_SCHEME));
                    System.out.println("BEAM_WINDOW_LAUNCH " + display + " exit=" + result.exitCode
                            + " timeout=" + result.timedOut + " " + result.output.replace('\n', ' '));
                    if (!result.succeeded()) closeFailedLaunch(display);
                    else recordRecentApp(launch);
                } catch (Exception error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    System.err.println("BEAM_WINDOW_LAUNCH_FAILED display=" + display + " " + error);
                    closeFailedLaunch(display);
                }
            });
        }

        private void recordRecentApp(Intent launch) {
            try {
                android.content.ComponentName component = launch.resolveActivity(getPackageManager());
                if (component == null) return;
                String name = component.getPackageName();
                if (name.equals("dev.markpronkin.unbound") || name.equals("dev.beampro.portprobe")
                        || getPackageManager().getLaunchIntentForPackage(name) == null) return;
                recentApps.record(name, System.currentTimeMillis());
                recentPreviews.prune();
                PREVIEWS.schedule(() -> recentPreviews.capturePackage(name), 1500, java.util.concurrent.TimeUnit.MILLISECONDS);
                System.out.println("BEAM_RECENTS_RECORDED " + name);
            } catch (Exception error) { System.err.println("BEAM_RECENTS_SAVE_FAILED " + error); }
        }

        private void closeFailedLaunch(int display) {
            // Failure belongs to this window, never to the helper's main Looper.
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Class<?> type = Class.forName("ai.nreal.nebula.service.LaunchManager");
                    Object manager = type.getMethod("getInstance").invoke(null);
                    Method findApp = type.getDeclaredMethod("findApp", int.class);
                    findApp.setAccessible(true);
                    if (findApp.invoke(manager, display) == null) return;
                    Method findContent = type.getDeclaredMethod("findActivityContent", int.class);
                    findContent.setAccessible(true);
                    Object content = findContent.invoke(manager, display);
                    if (content == null) return;
                    Method getComponent = content.getClass().getDeclaredMethod("getComponent");
                    getComponent.setAccessible(true);
                    android.content.ComponentName component = (android.content.ComponentName) getComponent.invoke(content);
                    type.getMethod("requestCloseActivity", android.content.ComponentName.class).invoke(manager, component);
                    System.out.println("BEAM_WINDOW_LAUNCH_FAILED_CLOSED display=" + display);
                } catch (Exception error) {
                    System.err.println("BEAM_WINDOW_LAUNCH_CLEANUP_FAILED display=" + display + " " + error);
                }
            });
        }
    }
}
