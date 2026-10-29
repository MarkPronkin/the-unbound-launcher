package dev.beampro.settings;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Display;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional sampled readback on Unity's own thread; bounded persistence runs on a worker. */
public final class RendererDiagnostics implements Runnable {
    private static RendererDiagnostics current;
    private final WeakReference<Activity> owner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "unbound-renderer-evidence"); t.setDaemon(true); return t; });
    private final AtomicBoolean pending = new AtomicBoolean();
    private final RendererTrace trace;
    private final String session = String.format(Locale.US, "%020d-%d", System.currentTimeMillis(), Process.myPid());
    private String previous = "", previousToken = "";
    private long lastWrite, queuedAt;
    private boolean timeoutReported;
    private volatile boolean closed;
    private RendererDiagnostics(Activity activity) {
        owner = new WeakReference<>(activity); trace = new RendererTrace(new File(activity.getFilesDir(), "debug"));
    }
    /** Invoked reflectively when the existing Presentation tracker starts, including reconnects. */
    public static synchronized void track(Activity activity) {
        if (activity == null || (current != null && current.owner.get() == activity)) return;
        if (current != null) { current.closed = true; current.main.removeCallbacks(current); current.worker.shutdown(); }
        current = new RendererDiagnostics(activity); current.main.post(current);
    }
    @Override public void run() {
        Activity activity = owner.get();
        if (closed || activity == null || activity.isDestroyed() || activity.isFinishing()) { closed = true; worker.shutdown(); return; }
        // One worker request and one Unity job maximum. A stalled Unity queue cannot grow indefinitely.
        if (pending.compareAndSet(false, true)) {
            queuedAt = SystemClock.uptimeMillis(); timeoutReported = false;
            worker.execute(() -> prepare(activity));
        } else if (!timeoutReported && SystemClock.uptimeMillis() - queuedAt > 5000) {
            timeoutReported = true;
            worker.execute(() -> { try { persist(trace.token(), "Unity job has not responded for 5 seconds; no additional jobs queued.\n"); } catch (Exception ignored) {} });
        }
        main.postDelayed(this, 2000);
    }
    private void prepare(Activity activity) {
        try {
            String token = trace.token();
            if (closed || token.isEmpty()) { pending.set(false); return; }
            Field field = null;
            for (Class<?> type = activity.getClass(); type != null && field == null; type = type.getSuperclass())
                try { field = type.getDeclaredField("mUnityPlayer"); } catch (NoSuchFieldException absent) {}
            if (field == null) throw new IllegalStateException("Unity player field unavailable");
            field.setAccessible(true); Object player = field.get(activity);
            if (player == null) throw new IllegalStateException("Unity player is not ready");
            Method queue = player.getClass().getDeclaredMethod("queueGLThreadEvent", Runnable.class); queue.setAccessible(true);
            String display = displays(activity);
            queue.invoke(player, (Runnable) () -> {
                String sample;
                try {
                    // Recheck the cross-process opt-in before touching Unity after a queued delay.
                    if (closed || !token.equals(trace.token())) { pending.set(false); return; }
                    sample = "Unity sample wallMs=" + System.currentTimeMillis() + "; uptimeMs=" + SystemClock.uptimeMillis() + "\n" + Native.capture();
                } catch (Throwable unavailable) { sample = "Unity readback unavailable: " + unavailable.getClass().getSimpleName() + "\n"; }
                final String value = display + "Force 3D preference=" + ExperimentalStereo.enabled(activity) + "\n" + sample;
                if (closed) { pending.set(false); return; }
                try { worker.execute(() -> { try { persist(token, value); } catch (Exception ignored) {} finally { pending.set(false); } }); }
                catch (java.util.concurrent.RejectedExecutionException stopped) { pending.set(false); }
            });
        } catch (Exception unavailable) {
            try { persist(trace.token(), "Renderer sampling unavailable: " + unavailable.getClass().getSimpleName() + "\n"); } catch (Exception ignored) {}
            pending.set(false);
        }
    }
    private void persist(String token, String value) throws Exception {
        if (closed || token.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        String geometry = value.replaceAll("Unity sample wallMs=[^\\n]*\\n", "");
        if (token.equals(previousToken) && geometry.equals(previous) && now - lastWrite < 30000) return;
        trace.append(token, session, "\n--- Renderer sample wallMs=" + System.currentTimeMillis() + "; uptimeMs=" + now + "; pid=" + Process.myPid() + " ---\n" + value);
        previous = geometry; previousToken = token; lastWrite = now;
    }
    private static String displays(Activity activity) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (Display display : activity.getSystemService(DisplayManager.class).getDisplays()) if (display.getDisplayId() != 0 && count++ < 8) {
            Display.Mode mode = display.getMode();
            out.append("Android display=").append(display.getDisplayId()).append(' ').append(mode.getPhysicalWidth()).append('x').append(mode.getPhysicalHeight()).append('@').append(mode.getRefreshRate()).append('\n');
        }
        return out.toString();
    }
    private static native String nativeCapture();
    private static final class Native {
        static { System.loadLibrary("UnboundRendererDiagnostics"); }
        static synchronized String capture() { return nativeCapture(); }
    }
}
