package dev.beampro.bridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.File;
import java.lang.reflect.Field;

/** Runs the production adapter as shell on the isolated settings preview. */
public final class AppClassificationDeviceChecks {
    static final String PACKAGE = "dev.beampro.settings.preview";
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        Context shell = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        Field op = shell.getClass().getDeclaredField("mOpPackageName");
        op.setAccessible(true); op.set(shell, "com.android.shell");
        PackageManager packages = shell.getPackageManager();
        int uid = packages.getApplicationInfo(PACKAGE, 0).uid;
        AppClassification policy = new AppClassification(packages, uid, new File("/data/local/tmp/unbound-category-device-check.bin"));
        if (args[0].equals("check")) {
            String installer = packages.getInstallerPackageName(PACKAGE);
            try {
                require(policy.request(uid, 1) == 0, "Android reports CATEGORY_GAME");
                require(policy.request(uid, -1) == 0, "fresh category query reports game");
                require(java.util.Objects.equals(installer, packages.getInstallerPackageName(PACKAGE)), "installer preserved");
                policy = new AppClassification(packages, uid, new File("/data/local/tmp/unbound-category-device-check.bin"));
                require(policy.request(uid, -1) == 0, "game category survives adapter restart");
                require(policy.request(uid, 0) == -1, "Android restores normal category");
                packages.setInstallerPackageName(PACKAGE, null);
                require(policy.request(uid, 1) == 0 && packages.getInstallerPackageName(PACKAGE) == null, "temporary installer restored to null");
                require(policy.request(uid, 0) == -1, "normal after temporary installer path");
                String systemInstaller = "com.google.android.packageinstaller";
                if (packages.checkSignatures("com.android.shell", systemInstaller) == PackageManager.SIGNATURE_MATCH) {
                    packages.setInstallerPackageName(PACKAGE, systemInstaller);
                    require(policy.request(uid, 1) == 0 && systemInstaller.equals(packages.getInstallerPackageName(PACKAGE)), "system installer restored after game hint");
                    require(policy.request(uid, 0) == -1, "normal after system installer path");
                }
                try { policy.request(2000, 1); throw new AssertionError("shell caller accepted"); }
                catch (SecurityException expected) { System.out.println("PASS foreign caller rejected"); }
            } finally { packages.setInstallerPackageName(PACKAGE, installer); }
            System.out.println("PASS Android app classification checks");
            return;
        }
        final AppClassification remote = policy;
        final int own = uid;
        IBinder binder = new Binder() {
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != AppClassification.TRANSACTION) return false;
                int caller = Binder.getCallingUid();
                data.enforceInterface(AppClassification.TOKEN);
                long identity = Binder.clearCallingIdentity();
                try {
                    int category = -1;
                    String error = null;
                    try { category = remote.request(caller, data.readInt()); }
                    catch (Exception failure) { error = failure.toString(); }
                    reply.writeNoException(); reply.writeString(error); reply.writeInt(category);
                    return true;
                } finally { Binder.restoreCallingIdentity(identity); }
            }
        };
        Handler main = new Handler(Looper.getMainLooper());
        Object activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        java.lang.reflect.Method acquire = Class.forName("android.app.IActivityManager").getMethod("getContentProviderExternal",
                String.class, int.class, IBinder.class, String.class);
        Object attribution = ProviderCallCompat.shellAttribution(2000);
        IBinder token = new Binder();
        main.post(new Runnable() {
            Object provider;
            public void run() {
                try {
                    if (provider == null) {
                        Object holder = acquire.invoke(activityManager, PACKAGE + ".unbound.bridge", 0, token, "unbound-category-fixture");
                        provider = holder.getClass().getField("provider").get(holder);
                    }
                    Bundle payload = new Bundle(); payload.putBinder("binder", binder);
                    ProviderCallCompat.publish(provider, Class.forName("android.content.IContentProvider"), attribution,
                            PACKAGE + ".unbound.bridge", payload);
                } catch (Exception error) { provider = null; System.err.println(error); }
                main.postDelayed(this, 1000);
            }
        });
        System.out.println("CLASSIFICATION_FIXTURE_READY uid=" + own);
        Looper.loop();
    }
    static void require(boolean value, String label) { if (!value) throw new AssertionError(label); System.out.println("PASS " + label); }
}
