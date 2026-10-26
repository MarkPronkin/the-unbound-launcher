package dev.beampro.portprobe;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Exercises the extracted 2.2.2 AIDL contract from an ordinary application UID. */
public final class BridgeCheckActivity extends Activity {
    private static final String DESCRIPTOR = "ai.nreal.nebula.service.ILaunchManager";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONObject report = new JSONObject();
    private ImageReader reader;
    private IBinder bridge;
    private DisplayManager.DisplayListener listener;
    private int displayId = -1;
    private int frames;
    private boolean teal;
    private boolean orange;

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView label = new TextView(this);
        label.setText("Testing the original My Glasses window interface through the ADB helper…");
        label.setPadding(30, 60, 30, 30);
        setContentView(label);
        put(report, "client_uid", android.os.Process.myUid());
        put(report, "contract", "My Glasses 2.2.2 / ILaunchManager");
        try {
            Bundle result = getContentResolver().call(Uri.parse("content://dev.beampro.portprobe.bridge"), "get", null, null);
            bridge = result == null ? null : result.getBinder("binder");
            if (bridge == null) throw new IllegalStateException("No running helper");
            put(report, "descriptor", bridge.getInterfaceDescriptor());
            put(report, "primary_display_rejected", rejectsNullInput(0));
            reader = ImageReader.newInstance(800, 600, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(source -> {
                try (Image image = source.acquireLatestImage()) {
                    if (image == null) return;
                    frames++;
                    Image.Plane plane = image.getPlanes()[0];
                    ByteBuffer buffer = plane.getBuffer();
                    int offset = image.getHeight() * 3 / 4 * plane.getRowStride() + image.getWidth() * 3 / 4 * plane.getPixelStride();
                    if (offset + 2 < buffer.limit()) {
                        int r = buffer.get(offset) & 255, g = buffer.get(offset + 1) & 255, b = buffer.get(offset + 2) & 255;
                        teal |= r == 28 && g == 125 && b == 110;
                        orange |= r == 219 && g == 124 && b == 50;
                    }
                } catch (Exception error) { put(report, "frame_error", error.toString()); }
            }, handler);
            DisplayManager manager = getSystemService(DisplayManager.class);
            for (android.view.Display display : manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
                if (display.getDisplayId() > 0 && !display.getName().startsWith("com.xreal.virtualdisplay-")) {
                    put(report, "unmanaged_display_id", display.getDisplayId());
                    long started = SystemClock.elapsedRealtimeNanos();
                    put(report, "unmanaged_display_rejected_without_exception", rejectsNullInput(display.getDisplayId()));
                    put(report, "unmanaged_input_round_trip_ms", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0);
                    break;
                }
            }
            listener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) {
                    android.view.Display display = manager.getDisplay(id);
                    if (display == null || !display.getName().startsWith("com.xreal.virtualdisplay-")) return;
                    displayId = id;
                    put(report, "display_id", id);
                    handler.postDelayed(() -> sendTap(id), 1200);
                }
                @Override public void onDisplayRemoved(int id) {}
                @Override public void onDisplayChanged(int id) {}
            };
            manager.registerDisplayListener(listener, handler);
            requestFixture();
            put(report, "launch_request_sent", true);
        } catch (Exception error) { put(report, "error", error.toString()); }
        handler.postDelayed(() -> {
            put(report, "frames", frames);
            put(report, "fixture_rendered", teal);
            put(report, "tap_feedback_rendered", orange);
            try {
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(1);
                    new ComponentName(this, MainActivity.class).writeToParcel(data, 0);
                    bridge.transact(2, data, reply, 0);
                    reply.readException();
                    put(report, "close_request_sent", true);
                } finally { data.recycle(); reply.recycle(); }
            } catch (Exception error) { put(report, "close_error", error.toString()); }
            handler.postDelayed(() -> {
                try {
                    put(report, "closed_display_removed", displayId > 0 && getSystemService(DisplayManager.class).getDisplay(displayId) == null);
                    long started = SystemClock.elapsedRealtimeNanos();
                    put(report, "late_input_rejected_without_exception", displayId > 0 && rejectsNullInput(displayId));
                    put(report, "late_input_round_trip_ms", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0);
                } catch (Exception error) { put(report, "late_input_error", error.toString()); }
                finishReport(label);
            }, 500);
        }, 5000);
    }

    private boolean rejectsNullInput(int target) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(0); // Null event: even an unexpected acceptance cannot tap another display.
            data.writeInt(target);
            if (!bridge.transact(3, data, reply, 0)) throw new IllegalStateException("Input transaction unsupported");
            reply.readException();
            return reply.readInt() == 0;
        } finally { data.recycle(); reply.recycle(); }
    }

    private void finishReport(TextView label) {
        put(report, "complete", true);
        try {
            String value = report.toString(2);
            try (FileOutputStream stream = new FileOutputStream(new File(getFilesDir(), "bridge-check.json"))) {
                stream.write(value.getBytes(StandardCharsets.UTF_8));
            }
            label.setText(value);
        } catch (Exception error) { label.setText(error.toString()); }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void requestFixture() throws Exception {
        Intent intent = new Intent(this, MainActivity.class).putExtra("fixture", true);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(1); // Non-null LaunchConfig.
            int start = data.dataPosition();
            data.writeInt(0); // Sized parcelable header, filled below.
            data.writeInt(1);
            intent.writeToParcel(data, 0);
            data.writeInt(800);
            data.writeInt(600);
            data.writeInt(200);
            data.writeInt(1);
            reader.getSurface().writeToParcel(data, 0);
            data.writeFloat(60f);
            data.writeInt(0);
            int end = data.dataPosition();
            data.setDataPosition(start);
            data.writeInt(end - start);
            data.setDataPosition(end);
            if (!bridge.transact(15, data, reply, 0)) throw new IllegalStateException("LaunchConfig transaction unsupported");
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    private void sendTap(int display) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, 240, 200, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(1);
                event.writeToParcel(data, 0);
                data.writeInt(display);
                if (!bridge.transact(3, data, reply, 0)) throw new IllegalStateException("Input transaction unsupported");
                reply.readException();
                reply.readInt(); // Original implementation reports asynchronously.
                put(report, "input_requests_sent", true);
            } catch (Exception error) { put(report, "input_error", error.toString()); }
            finally { event.recycle(); data.recycle(); reply.recycle(); }
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (listener != null) getSystemService(DisplayManager.class).unregisterDisplayListener(listener);
        if (reader != null) { reader.setOnImageAvailableListener(null, null); reader.close(); }
        super.onDestroy();
    }
}
