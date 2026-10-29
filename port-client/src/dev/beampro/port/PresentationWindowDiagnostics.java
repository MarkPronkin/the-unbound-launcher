package dev.beampro.port;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** Bounded, opt-in Presentation readback retained independently of logcat and disconnects. */
public final class PresentationWindowDiagnostics {
    private static final Object LOCK = new Object();
    private static final int SAMPLE_BYTES = 12_000;
    private static String previous;
    private static String status = "No capture attempted";

    static void scan(Activity activity) {
        int count = 0, presentations = 0;
        for (View root : WindowInspector.getGlobalWindowViews()) {
            count++;
            if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams)) continue;
            int type = ((WindowManager.LayoutParams) root.getLayoutParams()).type;
            if (type != 2037 && type != WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION) continue;
            if (!DisplayStartupCompat.xreal(root.getDisplay())) continue;
            if (++presentations <= 8) capture(root, "discovered");
        }
        if (presentations == 0)
            persist(activity, "No glasses Presentation roots visible to Window Inspector; roots=" + count + '\n', false);
    }

    static void capture(View root, String stage) {
        try { persist(root.getContext(), describe(root, stage), true); }
        catch (RuntimeException unavailable) { status = "Readback failed: " + unavailable; }
    }

    private static String describe(View root, String stage) {
        StringBuilder out = new StringBuilder("Stage: ").append(stage).append('\n');
        out.append("Root: ").append(root.getClass().getName()).append('@')
                .append(Integer.toHexString(System.identityHashCode(root))).append('\n');
        Display display = root.getDisplay();
        out.append("Display: ").append(display == null ? "none" : display.getDisplayId()).append('\n');
        out.append("Attached: ").append(root.isAttachedToWindow()).append("; shown: ").append(root.isShown())
                .append("; windowFocus: ").append(root.hasWindowFocus()).append('\n');
        out.append("Size: ").append(root.getWidth()).append('x').append(root.getHeight())
                .append("; systemUiVisibility=0x").append(Integer.toHexString(root.getSystemUiVisibility())).append('\n');
        if (root.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) root.getLayoutParams();
            out.append("Type: ").append(attrs.type).append("; flags=0x").append(Integer.toHexString(attrs.flags))
                    .append("; preferredMode=").append(attrs.preferredDisplayModeId)
                    .append("; fitInsetsTypes=").append(attrs.getFitInsetsTypes()).append('\n');
        } else out.append("No WindowManager.LayoutParams on root\n");
        WindowInsetsController controller = root.getWindowInsetsController();
        out.append("InsetsController: ").append(controller == null ? "none" : controller.getClass().getName()).append('\n');
        if (controller != null) {
            out.append("RequestedVisibleTypes: ").append(requestedTypes(controller)).append('\n');
            out.append("SystemBarsBehavior: ").append(controller.getSystemBarsBehavior()).append('\n');
        }
        WindowInsets insets = root.getRootWindowInsets();
        out.append("RootWindowInsets: ").append(insets == null ? "none"
                : "systemBarsVisible=" + insets.isVisible(WindowInsets.Type.systemBars())).append('\n');
        return out.toString();
    }

    private static String requestedTypes(WindowInsetsController controller) {
        try {
            Method method = controller.getClass().getMethod("getRequestedVisibleTypes");
            method.setAccessible(true);
            return String.valueOf(method.invoke(controller));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            try {
                Field field = controller.getClass().getDeclaredField("mRequestedVisibleTypes");
                field.setAccessible(true);
                return String.valueOf(field.getInt(controller));
            } catch (ReflectiveOperationException | RuntimeException denied) {
                return "unavailable: " + denied.getClass().getSimpleName();
            }
        }
    }

    private static void persist(Context context, String sample, boolean presentation) {
        try {
            File debug = new File(context.getFilesDir(), "debug");
            String token = read(new File(debug, "renderer/collecting"), 65).trim();
            if (token.isEmpty()) { status = "Collection gate unavailable"; return; }
            synchronized (LOCK) {
                File directory = new File(debug, "immersive");
                if (!directory.isDirectory() && !directory.mkdirs()) { status = "Storage unavailable"; return; }
                File marker = new File(directory, "collection"), first = new File(directory, "first.txt"), latest = new File(directory, "latest.txt");
                if (!token.equals(read(marker, 65).trim())) {
                    first.delete(); latest.delete(); previous = null;
                    write(marker, token);
                }
                if (sample.equals(previous) || !token.equals(read(new File(debug, "renderer/collecting"), 65).trim())) return;
                String bounded = bound(sample);
                String value = "Captured wallMs=" + System.currentTimeMillis() + "; uptimeMs=" + SystemClock.uptimeMillis()
                        + "; pid=" + android.os.Process.myPid() + '\n' + bounded;
                if (presentation && !first.isFile()) write(first, value);
                if (presentation || !latest.isFile()) write(latest, value);
                previous = sample;
                status = "Snapshot retained";
            }
        } catch (IOException | RuntimeException unavailable) { status = "Capture failed: " + unavailable; }
    }

    public static String report(File debug) throws IOException {
        StringBuilder out = new StringBuilder("Immersive Presentation diagnostics\n"
                + "Opt-in client readback, independent of logcat; snapshots survive later disconnects.\n"
                + "No screenshots or rendered app content. Client flags and requests do not prove system acceptance.\n"
                + "Diagnostic status: " + status + '\n');
        File directory = new File(debug, "immersive");
        String first = read(new File(directory, "first.txt"), SAMPLE_BYTES);
        String latest = read(new File(directory, "latest.txt"), SAMPLE_BYTES);
        if (first.isEmpty() && latest.isEmpty())
            out.append("No snapshot captured. Enable collection before entering AR Space and keep it enabled while reproducing the issue.\n");
        if (!first.isEmpty()) out.append("\n--- First connected Presentation ---\n").append(first);
        if (!latest.isEmpty()) out.append("\n--- Latest connected-window readback ---\n").append(latest);
        return out.toString();
    }

    private static String bound(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= SAMPLE_BYTES) return text;
        int end = SAMPLE_BYTES - 40;
        while (end > 0 && (bytes[end] & 0xc0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8) + "\n[window snapshot truncated]\n";
    }

    private static String read(File file, int limit) throws IOException {
        if (!file.isFile()) return "";
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count, remaining = limit;
            while (remaining > 0 && (count = input.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                out.write(buffer, 0, count); remaining -= count;
            }
            return out.toString("UTF-8");
        }
    }

    private static void write(File file, String value) throws IOException {
        File temporary = new File(file + ".tmp");
        try {
            try (OutputStream output = new FileOutputStream(temporary)) { output.write(value.getBytes(StandardCharsets.UTF_8)); }
            if (!temporary.renameTo(file)) throw new IOException("Cannot save immersive-window snapshot");
        } finally { temporary.delete(); }
    }

    private PresentationWindowDiagnostics() { }
}
