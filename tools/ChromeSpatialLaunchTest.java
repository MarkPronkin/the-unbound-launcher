package dev.beampro.bridge;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.view.Display;
import java.nio.ByteBuffer;

/** Device regression: a signed test Activity exercises the installed helper. */
public final class ChromeSpatialLaunchTest extends Activity {
    private static final String CONTRACT = "ai.nreal.nebula.service.ILaunchManager";
    private final ComponentName chrome = new ComponentName("com.android.chrome", "com.google.android.apps.chrome.Main");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayManager displays;
    private ImageReader reader;
    private IBinder bridge;
    private int cycle, display, frames, coloredFrames;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            android.widget.TextView label = new android.widget.TextView(this);
            label.setText("Checking repeated Chrome spatial launches…");
            setContentView(label);
            displays = getSystemService(DisplayManager.class);
            Bundle result = getContentResolver().call(Uri.parse("content://dev.beampro.portprobe.bridge"), "get", null, null);
            bridge = result == null ? null : result.getBinder("binder");
            if (bridge == null) throw new AssertionError("Helper unavailable");
            displays.registerDisplayListener(new DisplayManager.DisplayListener() {
                public void onDisplayAdded(int id) {
                    Display item = displays.getDisplay(id);
                    if (item != null && item.getName().startsWith("com.xreal.virtualdisplay-com.android.chrome/")) {
                        display = id;
                    }
                }
                public void onDisplayRemoved(int id) {}
                public void onDisplayChanged(int id) {}
            }, handler);
            next();
        } catch (Throwable error) { fail(error); }
    }

    private void next() {
        try {
            if (++cycle > 5) {
                log("PASS: five repeated Chrome spatial opens, rendering and closures");
                finishReport();
                return;
            }
            frames = coloredFrames = display = 0;
            reader = ImageReader.newInstance(960, 720, 1, 3);
            reader.setOnImageAvailableListener(source -> {
                try (Image frame = source.acquireLatestImage()) {
                    if (frame == null) return;
                    frames++;
                    Image.Plane plane = frame.getPlanes()[0];
                    ByteBuffer buffer = plane.getBuffer();
                    int colored = 0;
                    for (int y = 40; y < frame.getHeight(); y += 80) for (int x = 40; x < frame.getWidth(); x += 80) {
                        int at = y * plane.getRowStride() + x * plane.getPixelStride();
                        if ((buffer.get(at) & 255) + (buffer.get(at + 1) & 255) + (buffer.get(at + 2) & 255) > 40) colored++;
                    }
                    if (colored > 10) coloredFrames++;
                } catch (Exception error) { fail(error); }
            }, handler);
            Intent intent = new Intent(Intent.ACTION_VIEW).setComponent(chrome);
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTRACT);
                data.writeInt(1);
                int start = data.dataPosition();
                data.writeInt(0);
                data.writeInt(1); intent.writeToParcel(data, 0);
                data.writeInt(960); data.writeInt(720); data.writeInt(240);
                data.writeInt(1); reader.getSurface().writeToParcel(data, 0);
                data.writeFloat(60f); data.writeInt(0);
                int end = data.dataPosition();
                data.setDataPosition(start); data.writeInt(end - start); data.setDataPosition(end);
                if (!bridge.transact(15, data, reply, 0)) throw new AssertionError("Launch unsupported");
                reply.readException();
            } finally { data.recycle(); reply.recycle(); }
            handler.postDelayed(this::verify, 3000);
        } catch (Throwable error) { fail(error); }
    }

    private void verify() {
        try {
            if (display <= 0 || displays.getDisplay(display) == null || coloredFrames < 2) {
                throw new AssertionError("Chrome escaped spatial display: cycle=" + cycle + " display=" + display
                    + " frames=" + frames + " colored=" + coloredFrames);
            }
            log("CYCLE=" + cycle + " display=" + display + " frames=" + frames + " colored=" + coloredFrames);
            // Allow the host to inspect task placement before releasing this display.
            handler.postDelayed(() -> {
                try {
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(CONTRACT);
                        data.writeInt(1); chrome.writeToParcel(data, 0);
                        bridge.transact(2, data, reply, 0); reply.readException();
                    } finally { data.recycle(); reply.recycle(); }
                    handler.postDelayed(() -> {
                        if (displays.getDisplay(display) != null) { fail(new AssertionError("Display did not close")); return; }
                        reader.close(); next();
                    }, 600);
                } catch (Throwable error) { fail(error); }
            }, 1500);
        } catch (Throwable error) { fail(error); }
    }

    private void fail(Throwable error) { log("FAIL: " + error); finishReport(); }

    private void log(String text) {
        report.append(text).append('\n');
        android.util.Log.i("ChromeSpatialLaunchTest", text);
    }

    private void finishReport() {
        try (java.io.FileOutputStream file = openFileOutput("result.txt", 0)) {
            file.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception error) { android.util.Log.e("ChromeSpatialLaunchTest", "Cannot save report", error); }
        finish();
    }
}
