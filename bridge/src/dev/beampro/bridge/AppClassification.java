package dev.beampro.bridge;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.io.*;
import java.lang.reflect.Method;
import java.util.Objects;

/** Changes only the launcher's Android category, with installer restoration on failure/restart. */
final class AppClassification {
    static final int TRANSACTION = 0x00FFFFF6;
    static final String TOKEN = "dev.beampro.bridge.APP_CLASSIFICATION";
    private static final String SHELL = "com.android.shell";
    interface Platform {
        int uid() throws Exception;
        int category() throws Exception;
        String installer() throws Exception;
        boolean platformInstaller(String installer) throws Exception;
        void installer(String installer) throws Exception;
        void category(int category) throws Exception;
    }
    private final Platform platform;
    private final int launcherUid;
    private final File journal;

    AppClassification(PackageManager packages, int uid, File journal) {
        this(new AndroidPlatform(packages, uid), uid, journal);
    }
    AppClassification(Platform platform, int uid, File journal) {
        this.platform = platform; launcherUid = uid; this.journal = journal;
        try { recover(); }
        catch (Exception error) { System.err.println("UNBOUND_CATEGORY_RECOVERY " + error); }
    }

    synchronized int request(int caller, int operation) throws Exception {
        if (caller != launcherUid || platform.uid() != launcherUid)
            throw new SecurityException("App detection is restricted to the installed launcher");
        if (operation < -1 || operation > 1) throw new IllegalArgumentException("Unknown app detection option");
        recover();
        if (operation == -1) return platform.category();
        int category = operation == 1 ? ApplicationInfo.CATEGORY_GAME : ApplicationInfo.CATEGORY_UNDEFINED;
        if (platform.category() == category) return category;
        String installer = platform.installer();
        if (!SHELL.equals(installer) && installer != null && !platform.platformInstaller(installer))
            throw new IllegalStateException("This installer does not allow changing app detection on this phone.");
        if (SHELL.equals(installer)) {
            platform.category(category);
        } else {
            // Android grants category hints only to the recorded installer. Shell can
            // temporarily take that role only for an absent or platform-signed installer.
            // Persist the original first, so even helper termination is recoverable.
            try (FileOutputStream file = new FileOutputStream(journal);
                 DataOutputStream out = new DataOutputStream(file)) {
                out.writeBoolean(installer != null);
                if (installer != null) out.writeUTF(installer);
                out.flush(); file.getFD().sync();
            }
            try {
                platform.installer(SHELL);
                if (!SHELL.equals(platform.installer())) throw new IOException("Android rejected the installer request");
                platform.category(category);
            } finally { recover(); }
        }
        int actual = platform.category();
        if (actual != category) throw new IOException("Android did not apply the requested app detection.");
        return actual;
    }

    private void recover() throws Exception {
        if (!journal.exists()) return;
        String original;
        try (DataInputStream in = new DataInputStream(new FileInputStream(journal))) {
            original = in.readBoolean() ? in.readUTF() : null;
        }
        String current = platform.installer();
        if (SHELL.equals(current)) {
            platform.installer(original);
            if (!Objects.equals(original, platform.installer())) throw new IOException("Could not restore the original installer");
        }
        // A different installer means an update happened; never overwrite its provenance.
        if (!journal.delete()) throw new IOException("Could not clear the installer recovery record");
    }

    private static final class AndroidPlatform implements Platform {
        final PackageManager packages;
        final String target;
        final Object service;
        final Class<?> api;
        AndroidPlatform(PackageManager packages, int uid) {
            this.packages = packages;
            String[] names = packages.getPackagesForUid(uid);
            if (names == null || names.length != 1) throw new IllegalStateException("Launcher package is ambiguous");
            target = names[0];
            try {
                service = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
                api = Class.forName("android.content.pm.IPackageManager");
            } catch (Exception error) { throw new IllegalStateException("Android package service unavailable", error); }
        }
        ApplicationInfo info() throws Exception {
            Method modern;
            try { modern = api.getMethod("getApplicationInfo", String.class, long.class, int.class); }
            catch (NoSuchMethodException older) { modern = null; }
            // Bypass ApplicationPackageManager's cache: this is the readback used by the UI.
            return (ApplicationInfo)(modern != null ? modern.invoke(service, target, 0L, 0)
                    : api.getMethod("getApplicationInfo", String.class, int.class, int.class).invoke(service, target, 0, 0));
        }
        public int uid() throws Exception { return info().uid; }
        public int category() throws Exception { return info().category; }
        public String installer() { return packages.getInstallerPackageName(target); }
        public boolean platformInstaller(String installer) { return packages.checkSignatures(SHELL, installer) == PackageManager.SIGNATURE_MATCH; }
        public void installer(String installer) { packages.setInstallerPackageName(target, installer); }
        public void category(int category) throws Exception {
            // systemMain's Context can still name "android" even after setting its
            // op-package. Supply the actual shell package to the Binder API directly.
            api.getMethod("setApplicationCategoryHint", String.class, int.class, String.class)
                    .invoke(service, target, category, SHELL);
        }
    }
}
