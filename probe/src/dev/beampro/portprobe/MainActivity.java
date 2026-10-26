package dev.beampro.portprobe;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Presentation;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.system.Os;
import android.system.OsConstants;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** Tests exact capabilities used by the extracted My Glasses 2.2.2 runtime. */
public class MainActivity extends Activity {
    private static final int OWN_DISPLAY = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
    // TRUSTED (1024) is not in the public SDK; 1485 is the extracted runtime's exact mask.
    private static final int[] FLAGS = {OWN_DISPLAY, OWN_DISPLAY, OWN_DISPLAY, OWN_DISPLAY | 1024,
            OWN_DISPLAY | DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE, 1485};
    private static final String[] CASES = {
        "public_own_presentation", "public_own_nonembedded", "public_own_embedded", "trusted_own_content",
        "secure_own_content", "myglasses_1485"
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONObject report;
    private TextView text;
    private Button runButton;
    private ImageReader activeReader;
    private VirtualDisplay activeDisplay;
    private Presentation activePresentation;
    private DisplayManager.DisplayListener activeListener;
    private boolean running;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("fixture", false)) {
            showBridgeFixture();
            return;
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("Beam Port Probe");
        title.setTextSize(26);
        layout.addView(title);
        TextView explanation = new TextView(this);
        explanation.setText("Compatibility diagnostics for the My Glasses port. This is not the spatial desktop. Tests render this app's own content only; no root, firmware changes, or network access.");
        explanation.setPadding(0, padding / 2, 0, padding / 2);
        layout.addView(explanation);
        runButton = new Button(this);
        runButton.setText("Run checks");
        runButton.setOnClickListener(view -> runChecks());
        layout.addView(runButton);
        Button copy = new Button(this);
        copy.setText("Copy report");
        copy.setOnClickListener(view -> {
            if (report != null) {
                ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                clipboard.setPrimaryClip(ClipData.newPlainText("Beam port compatibility", report.toString()));
            }
        });
        layout.addView(copy);
        ScrollView scroll = new ScrollView(this);
        text = new TextView(this);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextSize(12);
        text.setTextIsSelectable(true);
        text.setText("Connect the glasses, then run checks to include their display and USB descriptors. Checks also work without glasses.");
        scroll.addView(text);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        if (getIntent().getBooleanExtra("autorun", false)) handler.post(this::runChecks);
    }

    private void showBridgeFixture() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView fixture = new TextView(this);
        fixture.setText("Beam port helper test\nWaiting for a test tap");
        fixture.setTextSize(24);
        fixture.setTextColor(0xFFFFFFFF);
        fixture.setBackgroundColor(0xFF1C7D6E);
        fixture.setGravity(android.view.Gravity.CENTER);
        final int[] taps = {0};
        fixture.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                taps[0]++;
                fixture.setBackgroundColor(0xFFDB7C32);
                fixture.setText("Test tap received: " + taps[0]);
                JSONObject result = new JSONObject();
                int id = getWindowManager().getDefaultDisplay().getDisplayId();
                put(result, "display_id", id);
                put(result, "taps", taps[0]);
                put(result, "x", event.getX());
                put(result, "y", event.getY());
                try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "fixture-" + id + ".json"))) {
                    out.write(result.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Exception error) { android.util.Log.e("BeamPortProbe", "Fixture report failed", error); }
            }
            return true;
        });
        setContentView(fixture);
        handler.postDelayed(this::finish, 7000);
    }

    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (org.json.JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void runChecks() {
        if (running) return;
        running = true;
        runButton.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        report = new JSONObject();
        put(report, "schema_version", 1);
        put(report, "probe_version", "0.1");
        put(report, "source_runtime", "com.xreal.evapro.nebula 2.2.2 / 2873");
        put(report, "manufacturer", Build.MANUFACTURER);
        put(report, "model", Build.MODEL);
        put(report, "android", Build.VERSION.RELEASE);
        put(report, "sdk", Build.VERSION.SDK_INT);
        put(report, "security_patch", Build.VERSION.SECURITY_PATCH);
        put(report, "uid", android.os.Process.myUid());
        put(report, "root_status", "not_tested");
        put(report, "device_interactive", getSystemService(PowerManager.class).isInteractive());
        put(report, "device_locked", getSystemService(KeyguardManager.class).isKeyguardLocked());
        try { put(report, "memory_page_bytes", Os.sysconf(OsConstants._SC_PAGESIZE)); }
        catch (Exception error) { put(report, "memory_page_error", error.toString()); }
        JSONArray abi = new JSONArray();
        for (String value : Build.SUPPORTED_ABIS) abi.put(value);
        put(report, "abis", abi);
        JSONObject permissions = new JSONObject();
        for (String value : new String[]{"INJECT_EVENTS", "CAPTURE_VIDEO_OUTPUT", "CAPTURE_SECURE_VIDEO_OUTPUT",
                "ADD_TRUSTED_DISPLAY", "MANAGE_ACTIVITY_TASKS", "MANAGE_USB", "WRITE_SECURE_SETTINGS"}) {
            put(permissions, value, checkSelfPermission("android.permission." + value) == PackageManager.PERMISSION_GRANTED);
        }
        put(report, "permissions", permissions);
        JSONObject methods = new JSONObject();
        probeMethod(methods, "xreal_display_rotation", DisplayManager.class, "lockDisplayOrientation", int.class, int.class, boolean.class);
        probeMethod(methods, "xreal_window_rotation", WindowManager.class, "lockDisplayOrientation", int.class, int.class, boolean.class);
        probeMethod(methods, "xreal_record_display", MediaProjectionManager.class, "setDisplayToRecord", int.class, boolean.class);
        put(report, "reflection_checks", methods);
        put(report, "reflection_note", "Inaccessible reflection can mean a missing method or Android hidden-API enforcement. These methods are never invoked.");
        JSONArray displays = new JSONArray();
        DisplayManager manager = getSystemService(DisplayManager.class);
        for (Display display : manager.getDisplays()) {
            JSONObject entry = new JSONObject();
            put(entry, "id", display.getDisplayId());
            put(entry, "name", display.getName());
            put(entry, "flags", display.getFlags());
            JSONArray modes = new JSONArray();
            for (Display.Mode mode : display.getSupportedModes()) {
                JSONObject item = new JSONObject();
                put(item, "width", mode.getPhysicalWidth());
                put(item, "height", mode.getPhysicalHeight());
                put(item, "hz", mode.getRefreshRate());
                modes.put(item);
            }
            put(entry, "modes", modes);
            displays.put(entry);
        }
        put(report, "displays", displays);
        JSONArray usb = new JSONArray();
        try {
            UsbManager usbManager = getSystemService(UsbManager.class);
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                JSONObject entry = new JSONObject();
                put(entry, "vendor", device.getVendorId());
                put(entry, "product", device.getProductId());
                put(entry, "interfaces", device.getInterfaceCount());
                put(entry, "permission_already_granted", usbManager.hasPermission(device));
                usb.put(entry);
            }
        } catch (Exception error) { put(report, "usb_error", error.toString()); }
        put(report, "usb", usb);
        put(report, "virtual_display_tests", new JSONArray());
        runDisplayCase(0);
    }

    private static void probeMethod(JSONObject target, String key, Class<?> owner, String name, Class<?>... types) {
        JSONObject entry = new JSONObject();
        try {
            Method method = owner.getMethod(name, types);
            put(entry, "reflection_accessible", true);
            put(entry, "signature", method.toString());
        } catch (Exception error) {
            put(entry, "reflection_accessible", false);
            put(entry, "error", error.toString());
        }
        put(target, key, entry);
    }

    private void runDisplayCase(int index) {
        if (index == FLAGS.length) {
            running = false;
            runButton.setEnabled(true);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            put(report, "complete", true);
            saveReport();
            return;
        }
        JSONObject result = new JSONObject();
        put(result, "case", CASES[index]);
        put(result, "flags", FLAGS[index]);
        put(result, "fixture_allows_embedding", index == 2);
        boolean interactive = getSystemService(PowerManager.class).isInteractive();
        boolean locked = getSystemService(KeyguardManager.class).isKeyguardLocked();
        put(result, "device_interactive", interactive);
        put(result, "device_locked", locked);
        if (index < 3 && (!interactive || locked)) {
            put(result, "skipped", "Own-content rendering requires an awake, unlocked device. Run checks from the visible app.");
            recordDisplayResult(result);
            handler.post(() -> runDisplayCase(index + 1));
            return;
        }
        final int[] frames = {0};
        final boolean[] fixtureSeen = {false};
        try {
            activeReader = ImageReader.newInstance(800, 600, PixelFormat.RGBA_8888, 2);
            activeReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    if (image != null) {
                        frames[0]++;
                        Image.Plane plane = image.getPlanes()[0];
                        java.nio.ByteBuffer buffer = plane.getBuffer();
                        int offset = (image.getHeight() * 3 / 4) * plane.getRowStride()
                                + (image.getWidth() * 3 / 4) * plane.getPixelStride();
                        if (offset + 2 < buffer.limit()) {
                            fixtureSeen[0] |= (buffer.get(offset) & 255) == 28
                                    && (buffer.get(offset + 1) & 255) == 125
                                    && (buffer.get(offset + 2) & 255) == 110;
                        }
                    }
                } catch (IllegalStateException ignored) { /* Reader may already be released. */ }
            }, handler);
            DisplayManager manager = getSystemService(DisplayManager.class);
            put(result, "display_added_event_received", false);
            activeListener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) {
                    if (activeDisplay == null || activeDisplay.getDisplay().getDisplayId() != id) return;
                    put(result, "display_added_event_received", true);
                    handler.postDelayed(() -> {
                        if (activeDisplay != null && activeDisplay.getDisplay().getDisplayId() == id) {
                            showFixture(index, result);
                        }
                    }, 100);
                }
                @Override public void onDisplayRemoved(int id) {}
                @Override public void onDisplayChanged(int id) {}
            };
            manager.registerDisplayListener(activeListener, handler);
            activeDisplay = manager.createVirtualDisplay("beam-port-probe-" + CASES[index],
                    800, 600, 200, activeReader.getSurface(), FLAGS[index]);
            put(result, "created", activeDisplay != null);
            if (activeDisplay != null) {
                int id = activeDisplay.getDisplay().getDisplayId();
                put(result, "display_id", id);
            }
        } catch (Exception error) {
            put(result, "error", error.toString());
        }
        handler.postDelayed(() -> {
            put(result, "frames_received", frames[0]);
            put(result, "fixture_color_seen", fixtureSeen[0]);
            releaseDisplay();
            recordDisplayResult(result);
            saveReport();
            runDisplayCase(index + 1);
        }, 1600);
    }

    private void recordDisplayResult(JSONObject result) {
        try { report.getJSONArray("virtual_display_tests").put(result); }
        catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    private void showFixture(int index, JSONObject result) {
        put(result, "content_launch_attempted", true);
        try {
            if (index == 0) {
                activePresentation = new Presentation(this, activeDisplay.getDisplay());
                TextView fixture = new TextView(activePresentation.getContext());
                fixture.setText("Beam Port Probe\nOwn-content presentation");
                fixture.setTextColor(0xFFFFFFFF);
                fixture.setBackgroundColor(0xFF1C7D6E);
                fixture.setGravity(android.view.Gravity.CENTER);
                activePresentation.setContentView(fixture);
                activePresentation.show();
                put(result, "own_presentation_shown", true);
            } else {
                Intent fixture = new Intent(this, index == 2 ? EmbeddedFixtureActivity.class : FixtureActivity.class);
                fixture.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(activeDisplay.getDisplay().getDisplayId());
                startActivity(fixture, options.toBundle());
                put(result, "own_activity_launch_requested", true);
            }
        } catch (Exception error) { put(result, "content_error", error.toString()); }
    }

    private void releaseDisplay() {
        if (activeListener != null) {
            getSystemService(DisplayManager.class).unregisterDisplayListener(activeListener);
            activeListener = null;
        }
        if (activePresentation != null) { activePresentation.dismiss(); activePresentation = null; }
        if (activeDisplay != null) { activeDisplay.release(); activeDisplay = null; }
        if (activeReader != null) {
            activeReader.setOnImageAvailableListener(null, null);
            activeReader.close();
            activeReader = null;
        }
    }

    private void saveReport() {
        try {
            String value = report.toString(2);
            text.setText(value);
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "compatibility.json"))) {
                out.write(value.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) { text.setText("Could not save report: " + error); }
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseDisplay();
        super.onDestroy();
    }
}
