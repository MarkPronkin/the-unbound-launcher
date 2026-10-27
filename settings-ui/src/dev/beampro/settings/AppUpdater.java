package dev.beampro.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One process-wide updater. Workers never retain an Activity or install without a tap. */
final class AppUpdater {
    enum Phase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, ERROR }
    private static AppUpdater instance;
    private final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final UpdateClient client;
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    private WeakReference<Activity> foreground = new WeakReference<>(null);
    private boolean registered, startupChecked;
    private volatile int generation;
    private AlertDialog offer;
    Phase phase = Phase.IDLE;
    UpdateClient.Release release;
    String message = "Check GitHub for a newer version.";

    static synchronized AppUpdater get(Context context) {
        if (instance == null) instance = new AppUpdater(context.getApplicationContext());
        return instance;
    }

    private AppUpdater(Context context) {
        this(context, new UpdateClient());
    }

    AppUpdater(Context context, UpdateClient client) {
        this.context = context;
        this.client = client;
        preferences = context.getSharedPreferences("unbound_app_updates", Context.MODE_PRIVATE);
        if (readyFile(context).isFile()) {
            phase = Phase.READY;
            message = "Update downloaded. Tap Install update to continue.";
        }
        worker.execute(() -> {
            File[] files = updateDirectory(context).listFiles();
            if (files != null) for (File file : files) if (file.getName().endsWith(".part")) file.delete();
            if (!readyFile(context).isFile()) return;
            try { verifyApk(context, readyFile(context)); }
            catch (Exception obsolete) {
                readyFile(context).delete();
                main.post(() -> {
                    if (generation == 0 && phase == Phase.READY) {
                        phase = Phase.IDLE; message = "Check GitHub for a newer version."; changed();
                    }
                });
            }
        });
    }

    static void initialize(Application application) {
        if (!application.getPackageName().equals(Application.getProcessName())) return;
        AppUpdater updater = get(application);
        if (updater.registered) return;
        updater.registered = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity activity) {
                // Wait for setup and splash redirects to finish before offering an app update.
                if (!activity.getClass().getName().equals("ai.nreal.nebula.activity.setting.SettingActivity")) return;
                updater.foreground = new WeakReference<>(activity);
                updater.main.postDelayed(() -> {
                    if (!updater.startupChecked && updater.enabled() && updater.onStartup()
                            && updater.foreground.get() == activity && usable(activity)) {
                        updater.startupChecked = true;
                        updater.check(activity, true);
                    }
                }, 1500);
            }
            public void onActivityPaused(Activity activity) {
                if (updater.foreground.get() == activity) updater.foreground.clear();
                if (updater.offer != null && updater.offer.getOwnerActivity() == activity) updater.offer.dismiss();
            }
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) {}
        });
    }

    boolean enabled() { return preferences.getBoolean("enabled", true); }
    boolean onStartup() { return preferences.getBoolean("check_on_startup", true); }
    void setEnabled(boolean enabled) {
        preferences.edit().putBoolean("enabled", enabled).apply();
        if (!enabled) {
            generation++;
            if (offer != null) offer.dismiss();
            phase = readyFile(context).isFile() ? Phase.READY : Phase.IDLE;
            message = "App update checks are turned off.";
        } else message = phase == Phase.READY ? "Update downloaded. Tap Install update to continue." : "Check GitHub for a newer version.";
        changed();
    }
    void setOnStartup(boolean enabled) { preferences.edit().putBoolean("check_on_startup", enabled).apply(); changed(); }
    void observe(Runnable listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    void remove(Runnable listener) { listeners.remove(listener); }
    private void changed() { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }
    boolean busy() { return phase == Phase.CHECKING || phase == Phase.DOWNLOADING; }
    String lastChecked() {
        long time = preferences.getLong("last_checked", 0);
        return time == 0 ? "" : "Last checked " + DateFormat.getMediumDateFormat(context).format(time)
                + " at " + DateFormat.getTimeFormat(context).format(time);
    }

    void check(Activity activity, boolean startup) {
        if (!enabled() || busy()) return;
        if (phase == Phase.READY) { if (startup) prompt(activity); return; }
        phase = Phase.CHECKING; message = "Checking GitHub releases…"; changed();
        int token = ++generation;
        WeakReference<Activity> owner = new WeakReference<>(activity);
        worker.execute(() -> {
            try {
                PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                UpdateClient.Release found = client.latest(installed.versionName);
                main.post(() -> {
                    if (token != generation || !enabled()) return;
                    release = found;
                    preferences.edit().putLong("last_checked", System.currentTimeMillis()).apply();
                    phase = found == null ? Phase.IDLE : Phase.AVAILABLE;
                    message = found == null ? "You’re up to date." : found.tag + " is available.";
                    changed();
                    Activity target = owner.get();
                    if (found != null && usable(target) && (!startup || foreground.get() == target)) prompt(target);
                });
            } catch (Exception error) { fail(token, error, "Could not check for updates. Check your connection and try again."); }
        });
    }

    void prompt(Activity activity) {
        if (!enabled() || !usable(activity) || busy() || (offer != null && offer.isShowing())) return;
        if (phase == Phase.READY) {
            offer = new AlertDialog.Builder(activity).setTitle("Update ready to install")
                    .setMessage("Android will ask you to confirm the installation. Your settings will be kept.")
                    .setPositiveButton("Install update", (dialog, which) -> install(activity))
                    .setNegativeButton("Later", null).create();
        } else if (release != null) {
            String notes = release.notes.isEmpty() ? "A newer version is available on GitHub." : release.notes;
            if (notes.length() > 2400) notes = notes.substring(0, 2400) + "…";
            offer = new AlertDialog.Builder(activity).setTitle("Update available · " + release.tag)
                    .setMessage(notes + String.format(java.util.Locale.ROOT, "\n\nDownload: %.1f MB. Android will ask you to confirm installation.", release.size / 1048576.0))
                    .setPositiveButton("Download and install", (dialog, which) -> download())
                    .setNegativeButton("Later", null).create();
        } else return;
        offer.setOwnerActivity(activity);
        offer.setOnDismissListener(dialog -> { if (offer == dialog) offer = null; });
        offer.show();
    }

    void download() {
        if (!enabled() || release == null || busy()) return;
        UpdateClient.Release selected = release;
        int token = ++generation;
        phase = Phase.DOWNLOADING; message = "Downloading update · 0%"; changed();
        worker.execute(() -> {
            File partial = new File(updateDirectory(context), "download-" + token + ".part");
            try {
                if (!partial.getParentFile().isDirectory() && !partial.getParentFile().mkdirs()) throw new IOException("Could not create the update folder.");
                final int[] percent = {-1};
                client.download(selected, partial, (received, total) -> {
                    if (generation != token) throw new IOException("Download cancelled.");
                    int value = (int) (received * 100 / total);
                    if (value != percent[0]) {
                        percent[0] = value;
                        main.post(() -> { if (token == generation) { message = "Downloading update · " + value + "%"; changed(); } });
                    }
                });
                PackageInfo candidate = verifyApk(context, partial);
                ReleaseVersion downloaded = ReleaseVersion.parse(candidate.versionName);
                if (downloaded == null || downloaded.compareTo(ReleaseVersion.parse(selected.tag)) != 0)
                    throw new IOException("The downloaded APK version does not match the GitHub release.");
                main.post(() -> {
                    if (token != generation || !enabled()) { partial.delete(); return; }
                    if (!partial.renameTo(readyFile(context))) { partial.delete(); fail(token, new IOException("Could not save the downloaded update."), ""); return; }
                    phase = Phase.READY; message = "Update downloaded. Tap Install update to continue."; changed();
                    Activity target = foreground.get();
                    if (usable(target)) install(target);
                });
            } catch (Exception error) { partial.delete(); fail(token, error, "Could not download the update. Check your connection and try again."); }
        });
    }

    void cancel() {
        if (phase != Phase.DOWNLOADING) return;
        generation++; phase = Phase.AVAILABLE; message = "Download cancelled. You can try again."; changed();
    }

    private void fail(int token, Exception error, String fallback) {
        main.post(() -> {
            if (token != generation) return;
            phase = Phase.ERROR;
            message = error instanceof IOException && error.getMessage() != null ? error.getMessage() : fallback;
            changed();
        });
    }

    void install(Activity activity) {
        if (!enabled() || !usable(activity)) return;
        try { activity.startActivity(new Intent(activity, UpdateInstallActivity.class)); }
        catch (RuntimeException error) { Toast.makeText(activity, "Could not open the Android installer.", Toast.LENGTH_LONG).show(); }
    }

    static File updateDirectory(Context context) { return new File(context.getFilesDir(), "app-updates"); }
    static File readyFile(Context context) { return new File(updateDirectory(context), "update.apk"); }
    private static boolean usable(Activity activity) { return activity != null && !activity.isFinishing() && !activity.isDestroyed(); }

    static PackageInfo verifyApk(Context context, File file) throws Exception {
        PackageManager manager = context.getPackageManager();
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        // Android 13's archive parser collects certificates only when GET_SIGNATURES
        // is also present. Keep reading modern SigningInfo after forcing collection.
        PackageInfo update = manager.getPackageArchiveInfo(file.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES);
        if (update == null || !installed.packageName.equals(update.packageName)) throw new IOException("The download is not an update for this app.");
        if (update.getLongVersionCode() <= installed.getLongVersionCode()) throw new IOException("The downloaded APK is not newer than the installed app.");
        if (update.applicationInfo == null || update.applicationInfo.minSdkVersion > android.os.Build.VERSION.SDK_INT)
            throw new IOException("This update needs a newer Android version.");
        if (installed.signingInfo == null || update.signingInfo == null) throw new IOException("The update has no valid signing certificate.");
        Set<Signature> expected = new HashSet<>(Arrays.asList(installed.signingInfo.getApkContentsSigners()));
        Set<Signature> actual = new HashSet<>(Arrays.asList(update.signingInfo.getApkContentsSigners()));
        if (expected.isEmpty() || !expected.equals(actual)) throw new IOException("The update's signing certificate does not match this app.");
        return update;
    }
}
