package dev.beampro.settings;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Opt-in app-UID log capture; no shell privileges, network uploads or screenshots. */
final class DebugData {
    private static DebugData instance;
    final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService files = Executors.newSingleThreadExecutor();
    private final List<Runnable> observers = new ArrayList<>();
    private DebugArchive archive;
    private Thread collector;
    private Process logcat;
    private volatile int session;
    private boolean lifecycleInstalled;
    volatile String error;

    static synchronized DebugData get(Context context) {
        if (instance == null) instance = new DebugData(context.getApplicationContext());
        return instance;
    }

    static void initialize(Application application) {
        if (!application.getPackageName().equals(Application.getProcessName())) return;
        DebugData data = get(application);
        if (data.lifecycleInstalled) return;
        data.lifecycleInstalled = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private void event(Activity activity, String event) { data.event(event + " " + activity.getClass().getName()); }
            public void onActivityCreated(Activity a, Bundle b) { event(a, "created"); }
            public void onActivityStarted(Activity a) { event(a, "started"); }
            public void onActivityResumed(Activity a) { event(a, "resumed"); }
            public void onActivityPaused(Activity a) { event(a, "paused"); }
            public void onActivityStopped(Activity a) { event(a, "stopped"); }
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) { event(a, "destroyed"); }
        });
    }

    private DebugData(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("unbound_debug", Context.MODE_PRIVATE);
        if (enabled() && collecting()) start();
    }

    boolean enabled() { return preferences.getBoolean("enabled", false); }
    boolean collecting() { return preferences.getBoolean("collecting", false); }
    synchronized long bytes() {
        try { return archive().bytes(); }
        catch (IOException failure) { error = failure.getMessage(); return 0; }
    }
    private synchronized DebugArchive archive() throws IOException {
        if (archive == null) archive = new DebugArchive(new File(context.getFilesDir(), "debug"));
        return archive;
    }
    void observe(Runnable observer) { observers.add(observer); }
    void remove(Runnable observer) { observers.remove(observer); }
    private void changed() { main.post(() -> { for (Runnable observer : new ArrayList<>(observers)) observer.run(); }); }

    void setEnabled(boolean enabled) {
        preferences.edit().putBoolean("enabled", enabled).apply();
        if (!enabled) setCollecting(false);
        event("Debug mode enabled"); changed();
    }

    void setCollecting(boolean collecting) {
        collecting = collecting && enabled();
        preferences.edit().putBoolean("collecting", collecting).apply();
        if (collecting) start(); else stop();
        changed();
    }

    private synchronized void stop() {
        session++;
        if (logcat != null) logcat.destroy();
        logcat = null; collector = null;
        main.removeCallbacks(snapshot);
    }

    private synchronized void start() {
        if (collector != null) return;
        error = null;
        final int capture = ++session;
        collector = new Thread(() -> {
            Process process = null;
            try {
                synchronized (this) {
                    if (capture != session) return;
                    byte[] header = ("\n--- Collection started " + new Date() + " ---\n" + report()).getBytes(StandardCharsets.UTF_8);
                    archive().append(header, header.length);
                    // Android filters this to our UID, including our app's other processes.
                    process = new ProcessBuilder("logcat", "--uid=" + android.os.Process.myUid(),
                            "-v", "threadtime", "-T", "1", "*:V").redirectErrorStream(true).start();
                    logcat = process;
                    changed();
                }
                try (InputStream input = process.getInputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) synchronized (this) {
                        if (capture != session) return;
                        archive().append(buffer, count);
                    }
                }
                if (capture == session) throw new IOException("App log collection stopped. Turn collection on to retry.");
            } catch (IOException | RuntimeException failure) {
                synchronized (this) {
                    if (capture == session) {
                        error = failure.getMessage();
                        preferences.edit().putBoolean("collecting", false).apply();
                        collector = null; logcat = null;
                        main.removeCallbacks(snapshot);
                    }
                }
                changed();
            } finally { if (process != null) process.destroy(); }
        }, "unbound-debug-logs");
        collector.start();
        main.post(snapshot);
    }

    private final Runnable snapshot = new Runnable() {
        public void run() {
            if (!collecting()) return;
            event(report()); main.postDelayed(this, 5000);
        }
    };

    void event(String message) { if (enabled()) Log.d("UnboundDebug", message); }

    String report() {
        StringBuilder report = new StringBuilder("The Unbound Launcher debug report\n");
        report.append("Created: ").append(new Date()).append('\n');
        report.append("App: ").append(AppVersion.NAME).append(" (base ").append(AppVersion.BASE_NAME).append(")\n");
        report.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        report.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("Debug mode: ").append(enabled()).append("; collecting: ").append(collecting()).append('\n');
        report.append("Logs cover this app's UID only. Oldest data rotates at 12 MB. ZIP is limited to 15 MB.\n");
        report.append("No pairing codes, app preferences, screenshots or device serial numbers are added by this report.\n");
        try {
            DisplayManager manager = context.getSystemService(DisplayManager.class);
            for (Display display : manager.getDisplays()) {
                report.append("Display ").append(display.getDisplayId()).append(": state=").append(display.getState())
                        .append("; mode=").append(display.getMode()).append('\n');
            }
        } catch (RuntimeException unavailable) { report.append("Display information unavailable\n"); }
        return report.toString();
    }

    File export() throws IOException { return archive().export(report()); }

    static Intent share(Context context, File file) throws IOException {
        validate(file);
        Uri uri = DebugFileProvider.uri(context, file);
        Intent intent = new Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Debug data", uri));
        return intent;
    }

    Uri saveToDownloads(File file) throws IOException {
        validate(file);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, file.getName());
        values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Could not create a file in Downloads.");
        boolean complete = false;
        try {
            try (InputStream input = new FileInputStream(file);
                 OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IOException("Could not open Downloads.");
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, values, null, null) != 1)
                throw new IOException("Could not finish saving to Downloads.");
            complete = true; return uri;
        } finally { if (!complete) context.getContentResolver().delete(uri, null, null); }
    }

    static void validate(File file) throws IOException {
        if (!file.isFile() || file.length() <= 0 || file.length() > DebugArchive.MAX_EXPORT_BYTES)
            throw new IOException("Create a debug file of up to 15 MB first.");
    }
}
