package dev.beampro.settings;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in app logs and bounded, read-only network/display diagnostics; no uploads or screenshots. */
final class DebugData {
    private static DebugData instance;
    final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService files = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor startupEvents = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(64), task -> {
                Thread thread = new Thread(task, "unbound-startup-events"); thread.setDaemon(true); return thread;
            });
    private final ExecutorService controlCaptures = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "unbound-control-snapshots"); thread.setDaemon(true); return thread;
    });
    private final ExecutorService traceCaptures = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "unbound-control-traces"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean traceRunning = new AtomicBoolean();
    private volatile String traceAttempt;
    private volatile Future<?> traceTask;
    private final Object controlLock = new Object();
    private boolean controlPending;
    private String pendingControlFailure, controlStartAttempt;
    private boolean traceInitialized, resetTrace;
    private volatile int traceEpoch;
    private final List<Runnable> observers = new ArrayList<>();
    private DebugArchive archive;
    private Thread collector;
    private Process logcat;
    private volatile int session;
    private boolean lifecycleInstalled;
    private boolean diagnosticsPending;
    private String pendingFailure;
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
        if (collecting && !collecting()) synchronized (this) {
            resetTrace = true; traceInitialized = false; traceEpoch++;
        }
        preferences.edit().putBoolean("collecting", collecting).apply();
        if (collecting) start(); else stop();
        changed();
    }

    private synchronized void stop() {
        try { new RendererTrace(new File(context.getFilesDir(), "debug")).stop(); }
        catch (IOException failure) { error = failure.getMessage(); }
        session++;
        pendingFailure = null;
        synchronized (controlLock) { pendingControlFailure = null; controlStartAttempt = null; }
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
                    startupArchive();
                    new RendererTrace(new File(context.getFilesDir(), "debug")).start(false);
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
                        if (archive().startupLog(buffer, count))
                            queueControl("First SDK startup/control failure in this attempt; see glasses-startup.txt.", true);
                        archive().append(buffer, count);
                    }
                }
                if (capture == session) throw new IOException("App log collection stopped. Turn collection on to retry.");
            } catch (IOException | RuntimeException failure) {
                synchronized (this) {
                    if (capture == session) {
                        error = failure.getMessage();
                        preferences.edit().putBoolean("collecting", false).apply();
                        try { new RendererTrace(new File(context.getFilesDir(), "debug")).stop(); }
                        catch (IOException ignored) {}
                        collector = null; logcat = null;
                        main.removeCallbacks(snapshot);
                    }
                }
                changed();
            } finally { if (process != null) process.destroy(); }
        }, "unbound-debug-logs");
        collector.start();
        queueDiagnostics("Debug collection started; baseline before reproducing the connection problem.", false);
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
        StringBuilder report = new StringBuilder(SettingsUi.APP_NAME + " debug report\n");
        report.append("Created: ").append(new Date()).append('\n');
        report.append("App: ").append(AppVersion.NAME).append(" (base ").append(AppVersion.BASE_NAME).append(")\n");
        try { report.append("Build: ").append(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode()).append('\n'); }
        catch (Exception unavailable) { report.append("Build: unavailable\n"); }
        report.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        report.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("Debug mode: ").append(enabled()).append("; collecting: ").append(collecting()).append('\n');
        report.append("app.log covers this app's UID only. Oldest data rotates at 12 MB. ZIP is limited to 15 MB.\n");
        report.append("No pairing codes, app preferences or screenshots are added by this report.\n");
        report.append("Network diagnostics include interface IP addresses, routes, Ethernet status and glasses ping output.\n");
        report.append("Display and SurfaceFlinger dumps may include display identifiers and app/window names.\n");
        report.append("Display-link diagnostics include USB port state, selected system display logs, available kernel/link messages and connector EDID. Android may deny some sources.\n");
        report.append("Glasses startup diagnostics retain the first failure, recent SDK/control events and connection-attempt IDs. Control snapshots include fixed-peer socket state, routes and interface counters where Android permits them; they do not establish packet delivery.\n");
        report.append("Control traces sample kernel socket state for the fixed glasses peers every ").append(ControlTrace.INTERVAL_MS)
                .append(" ms for ").append(ControlTrace.DURATION_MS / 1000).append(" s after an SDK start and record only changes. Startup events record the glasses display mode.\n");
        report.append("App UID: ").append(android.os.Process.myUid()).append(" (owner of this app's sockets in control traces)\n");
        report.append("renderer-geometry.txt retains sampled Unity eye layout, render-target dimensions, viewports and projection matrices directly, independently of Android log throttling.\n");
        report.append("SDK/UI glasses type: ").append(GlassesArtwork.connectedType()).append('\n');
        try {
            UsbManager usb = context.getSystemService(UsbManager.class);
            if (usb != null) for (UsbDevice device : usb.getDeviceList().values()) {
                report.append(String.format(java.util.Locale.ROOT, "USB: %04x:%04x; permission=%s; interfaces=%d\n",
                        device.getVendorId(), device.getProductId(), usb.hasPermission(device), device.getInterfaceCount()));
            }
        } catch (RuntimeException unavailable) { report.append("USB information unavailable\n"); }
        try {
            DisplayManager manager = context.getSystemService(DisplayManager.class);
            for (Display display : manager.getDisplays()) {
                report.append("Display ").append(display.getDisplayId()).append(": state=").append(display.getState())
                        .append("; mode=").append(display.getMode()).append('\n');
                if (Build.VERSION.SDK_INT >= 31 && display.getDeviceProductInfo() != null) {
                    android.hardware.display.DeviceProductInfo product = display.getDeviceProductInfo();
                    report.append("  EDID: ").append(product.getManufacturerPnpId()).append(':').append(product.getProductId())
                            .append("; name=").append(product.getName()).append('\n');
                }
            }
        } catch (RuntimeException unavailable) { report.append("Display information unavailable\n"); }
        return report.toString();
    }

    /** Called reflectively by the connection adapter, without coupling the renderer to settings. */
    public static void connectionFailure(Context context, String reason) {
        DebugData data = get(context);
        data.queueControl("Connection failure: " + reason, true);
        data.queueDiagnostics("Connection failure: " + reason, true);
    }

    /** Passive Java state only. The connection thread never waits for files or diagnostic IPC. */
    public static void connectionEvent(Context context, String attempt, String event, String detail) {
        DebugData data = get(context);
        if (!data.enabled() || !data.collecting()) return;
        final int epoch = data.traceEpoch;
        final String id = bounded(attempt, 128), kind = bounded(event, 64);
        final String prefix = "wallMs=" + System.currentTimeMillis() + "; uptimeMs=" + SystemClock.uptimeMillis()
                + "; pid=" + android.os.Process.myPid() + "; ", text = prefix + bounded(detail, 2048);
        // The display query is IPC, so it runs on the event worker rather than the connection thread.
        final boolean displays = kind.equals("sdk_start") || kind.equals("sdk_result") || kind.equals("failure");
        Log.d("UnboundStartupTrace", "attempt=" + id + "; event=" + kind + "; " + text);
        try {
            data.startupEvents.execute(() -> {
                String retained = text;
                if (displays) {
                    String summary = externalDisplays(data.context);
                    Log.d("UnboundStartupTrace", "attempt=" + id + "; event=" + kind + "; displays=[" + summary + "]");
                    // Ahead of the long connection detail so the event's length limit cannot cut it off.
                    retained = prefix + "displays=[" + summary + "]; " + bounded(detail, 2048);
                }
                data.retainEvent(epoch, id, kind, retained);
            });
        } catch (RejectedExecutionException overloaded) {
            Log.w("UnboundDebug", "Startup event storage is busy; event remains in app.log");
        }
        if (kind.equals("sdk_start")) {
            boolean first;
            synchronized (data.controlLock) {
                first = !id.equals(data.controlStartAttempt); data.controlStartAttempt = id;
            }
            if (first) {
                data.queueControl("Before SDK startup; attempt=" + id + "; " + text, false);
                data.queueTrace(id, epoch);
            }
        } else if (kind.equals("cancel")) data.queueControl("Connection cancelled; attempt=" + id + "; " + text, true);
    }

    private void retainEvent(int epoch, String id, String kind, String text) {
        try { synchronized (this) {
            if (epoch == traceEpoch) startupArchive().startupEvent(id, kind, text);
        } }
        catch (IOException error) { Log.w("UnboundDebug", "Cannot retain startup event", error); }
    }

    /** One 12 s helper sampler at a time; a retry inside its window is already covered by it. */
    private void queueTrace(String attempt, int epoch) {
        if (!enabled() || !collecting()) return;
        final int capture = session;
        String note;
        if (!traceRunning.compareAndSet(false, true)) note = "Already sampling since attempt " + traceAttempt + "; this attempt is inside that trace";
        else {
            traceAttempt = attempt;
            final String request = "Requested wallMs=" + System.currentTimeMillis() + "; uptimeMs=" + SystemClock.uptimeMillis()
                    + "; attempt=" + attempt + "; started with the attempt's first SDK start";
            try {
                traceTask = traceCaptures.submit(() -> {
                    try {
                        if (!enabled() || !collecting() || capture != session) return;
                        String result = request + "\n" + DebugDisplay.captureTrace(context);
                        synchronized (this) {
                            if (!enabled() || !collecting() || capture != session) return;
                            startupArchive().startupTrace(result);
                        }
                    } catch (IOException error) { Log.w("UnboundDebug", "Cannot retain control-channel trace", error); }
                    finally { traceRunning.set(false); }
                });
                note = "Sampling glasses control sockets for " + ControlTrace.DURATION_MS + " ms; see glasses-control-trace.txt";
            } catch (RejectedExecutionException unavailable) { traceRunning.set(false); note = "Control trace unavailable: " + unavailable; }
        }
        final String text = note;
        try { startupEvents.execute(() -> retainEvent(epoch, attempt, "control_trace", text)); }
        catch (RejectedExecutionException overloaded) { Log.w("UnboundDebug", "Startup event storage is busy; " + text); }
    }

    /** Physical external displays only; the app's own virtual displays carry no EDID product data. */
    static String externalDisplays(Context context) {
        try {
            StringBuilder out = new StringBuilder(); int count = 0;
            for (Display display : context.getSystemService(DisplayManager.class).getDisplays()) {
                if (display.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
                boolean physical = Build.VERSION.SDK_INT >= 31 ? display.getDeviceProductInfo() != null
                        : (display.getFlags() & Display.FLAG_PRESENTATION) != 0;
                if (!physical) continue;
                if (++count > 3) { out.append("; [more omitted]"); break; }
                Display.Mode mode = display.getMode();
                Map<String, StringBuilder> rates = new LinkedHashMap<>();
                for (Display.Mode supported : display.getSupportedModes()) {
                    StringBuilder list = rates.computeIfAbsent(supported.getPhysicalWidth() + "x" + supported.getPhysicalHeight(), size -> new StringBuilder());
                    list.append(list.length() == 0 ? "" : "/").append(String.format(Locale.ROOT, "%.1f", supported.getRefreshRate()));
                }
                StringBuilder supported = new StringBuilder();
                for (Map.Entry<String, StringBuilder> entry : rates.entrySet())
                    supported.append(supported.length() == 0 ? "" : ",").append(entry.getKey()).append('@').append(entry.getValue());
                out.append(count == 1 ? "" : "; ").append(display.getDisplayId()).append(" \"").append(display.getName()).append("\" ")
                        .append(mode.getPhysicalWidth()).append('x').append(mode.getPhysicalHeight()).append('@')
                        .append(String.format(Locale.ROOT, "%.1f", mode.getRefreshRate())).append(" mode=").append(mode.getModeId())
                        .append(" state=").append(display.getState()).append(" supported=").append(supported);
            }
            return count == 0 ? "no external physical display" : out.toString();
        } catch (RuntimeException unavailable) { return "unavailable (" + unavailable + ")"; }
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "unavailable";
        return value.length() <= limit ? value : value.substring(0, limit) + " [truncated]";
    }

    private synchronized DebugArchive startupArchive() throws IOException {
        DebugArchive result = archive();
        if (!traceInitialized) {
            if (resetTrace) new RendererTrace(new File(context.getFilesDir(), "debug")).reset();
            result.startupBegin(resetTrace); resetTrace = false; traceInitialized = true;
        }
        return result;
    }

    /** Separate from slow display dumps; coalesce to one pending failure instead of growing a queue. */
    private void queueControl(String reason, boolean failure) {
        if (!enabled() || !collecting()) return;
        final int capture = session;
        final String request = "Requested wallMs=" + System.currentTimeMillis() + "; uptimeMs=" + SystemClock.uptimeMillis()
                + "\n" + bounded(reason, 3000);
        synchronized (controlLock) {
            if (controlPending) { if (failure) pendingControlFailure = request; return; }
            controlPending = true;
        }
        controlCaptures.execute(() -> {
            try {
                if (!enabled() || !collecting() || capture != session) return;
                String result = request + "\n" + DebugDisplay.captureControl(context);
                synchronized (this) {
                    if (!enabled() || !collecting() || capture != session) return;
                    startupArchive().startupSnapshot(result);
                }
            } catch (IOException error) { Log.w("UnboundDebug", "Cannot retain control-channel snapshot", error); }
            finally {
                String next;
                synchronized (controlLock) { controlPending = false; next = pendingControlFailure; pendingControlFailure = null; }
                if (next != null) queueControl(next, true);
            }
        });
    }

    private void queueDiagnostics(String reason, boolean network) {
        final int capture;
        synchronized (this) {
            if (!enabled() || !collecting()) return;
            if (diagnosticsPending) { if (network) pendingFailure = reason; return; }
            diagnosticsPending = true;
            capture = session;
        }
        files.execute(() -> {
            try {
                if (!enabled() || !collecting() || capture != session) return;
                // Preserve live network state before the slower display/link dumps.
                if (network) captureNetwork(reason);
                String link = reason + "\n" + DebugDisplay.captureLink(context);
                if (!enabled() || !collecting() || capture != session) return;
                archive().link(link);
            }
            catch (IOException error) { Log.w("UnboundDebug", "Cannot retain connection diagnostics", error); }
            finally {
                String next;
                synchronized (this) { diagnosticsPending = false; next = pendingFailure; pendingFailure = null; }
                if (next != null) queueDiagnostics(next, true); // Coalesce failures during the baseline; never grow a request queue.
            }
        });
    }

    private void captureNetwork(String reason) throws IOException {
        archive().network(reason + "\n" + DebugNetwork.capture(context));
    }

    File export() throws IOException {
        try { startupEvents.submit(() -> {}).get(2000, TimeUnit.MILLISECONDS); }
        catch (Exception pending) { Log.w("UnboundDebug", "Some startup events are still pending at export", pending); }
        // An export right after a failure would otherwise miss the trace that is still sampling it.
        Future<?> trace = traceTask;
        if (trace != null && traceRunning.get()) try { trace.get(ControlTrace.DURATION_MS + 9000, TimeUnit.MILLISECONDS); }
        catch (Exception pending) { Log.w("UnboundDebug", "Control trace did not finish before export", pending); }
        if (enabled()) startupArchive().startupSnapshot("Debug export requested; current control state.\n" + DebugDisplay.captureControl(context));
        if (enabled()) archive().link("Debug export requested; current connection state.\n" + DebugDisplay.captureLink(context));
        String[] displays = enabled() ? DebugDisplay.capture(context) : null;
        if (enabled()) captureNetwork("Debug export requested; keep the glasses connected until this finishes.");
        File result = archive().export(report(), displays == null ? null : displays[0], displays == null ? null : displays[1],
                new RendererTrace(new File(context.getFilesDir(), "debug")).report(), immersiveWindows());
        changed();
        return result;
    }

    private String immersiveWindows() {
        if (!enabled()) return null;
        try { return (String) Class.forName("dev.beampro.port.PresentationWindowDiagnostics")
                .getMethod("report", File.class).invoke(null, new File(context.getFilesDir(), "debug")); }
        catch (ReflectiveOperationException failure) { return "Immersive-window diagnostics unavailable: " + failure + '\n'; }
    }

    File latestExport() {
        File[] exports = new File(context.getFilesDir(), "debug/exports")
                .listFiles((dir, name) -> DebugArchive.validName(name));
        if (exports == null || exports.length == 0) return null;
        java.util.Arrays.sort(exports, java.util.Comparator.comparingLong(File::lastModified).reversed());
        return exports[0];
    }

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
