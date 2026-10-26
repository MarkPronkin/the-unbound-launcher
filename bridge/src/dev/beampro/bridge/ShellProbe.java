package dev.beampro.bridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/** Temporary ADB-shell test of trusted display hosting and input on our own fixture. */
public final class ShellProbe {
    private final JSONObject report = new JSONObject();
    private final JSONArray cases = new JSONArray();
    private final int[] flags = {1033, 1481, 1485};
    private Handler handler;
    private DisplayManager manager;
    private VirtualDisplay display;
    private ImageReader reader;
    private DisplayManager.DisplayListener listener;

    private static void put(JSONObject object, String name, Object value) {
        try { object.put(name, value); } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    public static void main(String[] args) {
        ShellProbe test = new ShellProbe();
        try {
            Looper.prepareMainLooper();
            test.handler = new Handler(Looper.getMainLooper());
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("systemMain").invoke(null);
            Context system = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
            Context shell = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
            put(test.report, "uid", android.os.Process.myUid());
            put(test.report, "context_package", shell.getPackageName());
            put(test.report, "op_package", shell.getOpPackageName());
            JSONObject permissions = new JSONObject();
            for (String name : new String[]{"INJECT_EVENTS", "ADD_TRUSTED_DISPLAY", "CAPTURE_VIDEO_OUTPUT", "CAPTURE_SECURE_VIDEO_OUTPUT"}) {
                put(permissions, name, shell.checkSelfPermission("android.permission." + name) == PackageManager.PERMISSION_GRANTED);
            }
            put(test.report, "permissions", permissions);
            put(test.report, "tests", test.cases);
            test.manager = shell.getSystemService(DisplayManager.class);
            test.runCase(0);
            Looper.loop();
        } catch (Throwable error) {
            put(test.report, "fatal_error", error.toString());
            error.printStackTrace(System.err);
            test.emit();
            System.exit(1);
        }
    }

    private void runCase(int index) {
        if (index == flags.length) {
            put(report, "complete", true);
            emit();
            System.exit(0);
            return;
        }
        JSONObject result = new JSONObject();
        put(result, "flags", flags[index]);
        final int[] frames = {0};
        final boolean[] tealSeen = {false};
        final boolean[] orangeSeen = {false};
        try {
            reader = ImageReader.newInstance(800, 600, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(source -> {
                try (Image frame = source.acquireLatestImage()) {
                    if (frame == null) return;
                    frames[0]++;
                    Image.Plane plane = frame.getPlanes()[0];
                    ByteBuffer buffer = plane.getBuffer();
                    int offset = frame.getHeight() * 3 / 4 * plane.getRowStride()
                            + frame.getWidth() * 3 / 4 * plane.getPixelStride();
                    if (offset + 2 < buffer.limit()) {
                        int r = buffer.get(offset) & 255;
                        int g = buffer.get(offset + 1) & 255;
                        int b = buffer.get(offset + 2) & 255;
                        tealSeen[0] |= r == 28 && g == 125 && b == 110;
                        orangeSeen[0] |= r == 219 && g == 124 && b == 50;
                    }
                } catch (Exception error) { put(result, "frame_error", error.toString()); }
            }, handler);
            listener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) {
                    if (display == null || display.getDisplay().getDisplayId() != id) return;
                    put(result, "display_added", true);
                    handler.postDelayed(() -> launchFixture(id, result), 250);
                }
                @Override public void onDisplayRemoved(int id) {}
                @Override public void onDisplayChanged(int id) {}
            };
            manager.registerDisplayListener(listener, handler);
            display = manager.createVirtualDisplay("beam-shell-probe-" + flags[index],
                    800, 600, 200, reader.getSurface(), flags[index]);
            put(result, "created", display != null);
            if (display != null) put(result, "display_id", display.getDisplay().getDisplayId());
        } catch (Throwable error) { put(result, "create_error", error.toString()); }
        handler.postDelayed(() -> {
            put(result, "frames", frames[0]);
            put(result, "fixture_rendered", tealSeen[0]);
            put(result, "tap_feedback_rendered", orangeSeen[0]);
            release();
            cases.put(result);
            runCase(index + 1);
        }, display == null ? 100 : 4500);
    }

    private void launchFixture(int id, JSONObject result) {
        if (display == null || display.getDisplay().getDisplayId() != id) return;
        put(result, "launch", command("am", "start", "-W", "--user", "0", "--display", Integer.toString(id),
                "-f", "0x18000000", "-n", "dev.beampro.portprobe/.MainActivity", "--ez", "fixture", "true"));
        handler.postDelayed(() -> {
            if (display != null && display.getDisplay().getDisplayId() == id) {
                put(result, "input", command("input", "-d", Integer.toString(id), "tap", "240", "200"));
            }
        }, 800);
    }

    private static JSONObject command(String... args) {
        JSONObject result = new JSONObject();
        try {
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                put(result, "error", "Command timed out");
                return result;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            put(result, "exit_code", process.exitValue());
            put(result, "output", bytes.toString("UTF-8"));
        } catch (Exception error) { put(result, "error", error.toString()); }
        return result;
    }

    private void release() {
        if (listener != null) { manager.unregisterDisplayListener(listener); listener = null; }
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.setOnImageAvailableListener(null, null); reader.close(); reader = null; }
    }

    private void emit() {
        System.out.println("BEAM_SHELL_RESULT=" + report.toString());
        System.out.flush();
    }
}
