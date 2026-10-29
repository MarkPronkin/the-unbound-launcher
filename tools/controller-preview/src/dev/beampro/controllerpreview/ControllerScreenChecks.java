package dev.beampro.controllerpreview;

import android.app.Instrumentation;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.TextView;
import ai.nreal.nebula.space.LaunchSpaceAcrivity;
import dev.beampro.port.PhoneScreenControl;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Check real resource geometry and input routing through the production screen-off session. */
public final class ControllerScreenChecks extends Instrumentation {
    private static final String[] MODES = {"air_mouse_fragment", "touchpad_portrait_layout", "touchpad_landscape_layout"};
    private int passed;
    private LaunchSpaceAcrivity activity;
    private File output;
    private final JSONArray renders = new JSONArray();
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        JSONObject report = new JSONObject();
        try {
            setInTouchMode(true);
            getUiAutomation().adoptShellPermissionIdentity();
            activity = (LaunchSpaceAcrivity) startActivitySync(new Intent(getTargetContext(), LaunchSpaceAcrivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            output = new File(activity.getExternalFilesDir(null), "controller"); output.mkdirs();
            settle();
            for (String mode : MODES) {
                boolean wide = mode.equals(MODES[2]);
                runOnMainSync(() -> activity.setRequestedOrientation(wide ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
                settle();
                runOnMainSync(() -> activity.showMode(mode));
                settle();
                Rect target = new Rect();
                runOnMainSync(() -> {
                    PhoneScreenControl button = activity.findViewById(PhoneScreenControl.ID);
                    require(button != null && button.isShown(), mode + " has a visible native control");
                    require(button.getParent() == activity.findViewById(0x7f09009e), "control belongs to native content");
                    require(button.touchBounds(activity.getWindow().getDecorView(), target), "native action has an unobscured touch target");
                    require(count(activity.controller, PhoneScreenControl.ID) == 1, "one native control after switching modes");
                    View proxy = proxy();
                    require(proxy != null, "legacy IME compatibility target is present");
                    WindowManager.LayoutParams params = (WindowManager.LayoutParams) proxy.getLayoutParams();
                    require(params.x == target.left && params.y == target.top && params.width == target.width()
                            && params.height == target.height(), "input target exactly follows native bounds");
                    require(proxy.getBackground() == null, "compatibility target draws no floating UI");
                });
                runOnMainSync(() -> redraw(activity.controller));
                settle();
                Bitmap screenshot = getUiAutomation().takeScreenshot();
                require(screenshot != null, "live controller window screenshot available");
                try (FileOutputStream stream = new FileOutputStream(new File(output, mode + "-screen.png"))) {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream);
                }
                screenshot.recycle();
                tap(target.centerX(), target.centerY());
                settle();
                runOnMainSync(() -> {
                    require(text("Turn off phone screen?", null) != null, "real tap through touch-modal IME opens production confirmation");
                    require(proxy() == null, "input target removed while confirmation is open");
                    require(text("AR Space will keep running", null) != null, "confirmation preserves AR and volume recovery explanation");
                    View cancel = findWindowView(android.R.id.button2);
                    require(cancel != null, "confirmation can be cancelled");
                    cancel.performClick();
                });
                settle();
                runOnMainSync(() -> require(proxy() != null, "Cancel restores the integrated action"));
            }
            checkGamepadFocus();
            // Drawer and original lock UI must take precedence over the invisible compatibility target.
            runOnMainSync(() -> {
                View drawer = activity.controller.findViewById(0x7f0900c6);
                try { activity.controller.getClass().getMethod("openDrawer", View.class, boolean.class).invoke(activity.controller, drawer, false); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            settle();
            runOnMainSync(() -> require(proxy() == null, "drawer suppresses screen-off hit target"));
            runOnMainSync(() -> activity.showMode(MODES[0]));
            settle();
            runOnMainSync(() -> require(proxy() != null, "new mode reattaches the hit target"));
            runOnMainSync(activity::disconnect);
            settle();
            runOnMainSync(() -> {
                require(proxy() == null, "disconnect removes the hit target");
                require(activity.findViewById(PhoneScreenControl.ID).getVisibility() == View.INVISIBLE, "disconnect hides native action");
            });
            runOnMainSync(activity::finish);
            settle();
            runOnMainSync(() -> require(proxy() == null, "destroy leaves no input overlay"));
            for (String mode : MODES) {
                boolean wide = mode.equals(MODES[2]);
                runOnMainSync(() -> render(mode, wide ? 852 : 392, wide ? 392 : 852, 1, mode));
                runOnMainSync(() -> render(mode, wide ? 640 : 320, wide ? 360 : 640, 1.5f, mode + "-compact"));
            }
            report.put("success", true); report.put("passed", passed); report.put("renders", renders);
            result.putString("stream", "Controller screen checks passed: " + passed + "\n");
            write(report);
            finish(-1, result);
        } catch (Throwable error) {
            try { report.put("success", false); report.put("passed", passed); report.put("error", android.util.Log.getStackTraceString(error)); write(report); }
            catch (Exception ignored) {}
            if (activity != null) runOnMainSync(activity::finish);
            result.putString("stream", "Controller screen checks failed after " + passed + ": " + android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }

    private void checkGamepadFocus() throws Exception {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(activity.glasses.getDisplay().getDisplayId());
        runOnMainSync(() -> activity.startActivity(new Intent(activity, GamepadActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK), options.toBundle()));
        settle();
        require(GamepadActivity.focused, "spatial game owns global input focus");
        gamepad();
        int losses = GamepadActivity.focusLosses;
        Rect target = new Rect();
        runOnMainSync(() -> require(activity.<PhoneScreenControl>findViewById(PhoneScreenControl.ID)
                .touchBounds(activity.getWindow().getDecorView(), target), "screen-off action available beside a focused game"));
        tap(target.centerX(), target.centerY()); settle();
        runOnMainSync(() -> {
            View cancel = findWindowView(android.R.id.button2);
            require(cancel != null, "confirmation opens while game is focused");
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) cancel.getRootView().getLayoutParams();
            require((params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0, "confirmation cannot take gamepad focus");
        });
        require(GamepadActivity.focused && GamepadActivity.focusLosses == losses, "opening confirmation preserves game focus");
        gamepad();
        runOnMainSync(() -> findWindowView(android.R.id.button2).performClick()); settle();

        // Exercise the production touch guard without powering down emulator displays
        // or requiring the native Unity pointer/helper in this isolated fixture.
        Class<?> type = Class.forName("dev.beampro.port.PhoneScreenCompat$Session");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(android.app.Activity.class);
        constructor.setAccessible(true);
        Object[] session = new Object[1];
        runOnMainSync(() -> {
            try { session[0] = constructor.newInstance(activity); invoke(session[0], "block"); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        try {
            settle();
            require(GamepadActivity.focused && GamepadActivity.focusLosses == losses, "screen-off guard preserves game focus");
            gamepad();
            tap(200, 200); settle();
            require(GamepadActivity.focused && GamepadActivity.focusLosses == losses, "blocked phone touches leave the game focused");
            gamepad();
            java.lang.reflect.Field blocked = type.getDeclaredField("blockedTouches"); blocked.setAccessible(true);
            require(blocked.getInt(session[0]) >= 2, "non-focusable guard still consumes phone down/up events");
        } finally {
            runOnMainSync(() -> { invoke(session[0], "unblock"); invoke(session[0], "close"); });
            runOnMainSync(() -> GamepadActivity.instance.finish()); settle();
        }
    }

    private void gamepad() {
        int before = GamepadActivity.releases;
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent event = new KeyEvent(now, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BUTTON_A,
                    0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD);
            require(getUiAutomation().injectInputEvent(event, true), "global gamepad button injected");
        }
        settle();
        require(GamepadActivity.releases == before + 1, "global gamepad button reaches the spatial game");
        int motions = GamepadActivity.motions;
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties(); properties.id = 0;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.setAxisValue(MotionEvent.AXIS_X, .5f); coordinates.setAxisValue(MotionEvent.AXIS_Y, -.5f);
        MotionEvent joystick = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 1,
                new MotionEvent.PointerProperties[]{properties}, new MotionEvent.PointerCoords[]{coordinates},
                0, 0, 1, 1, -1, 0, InputDevice.SOURCE_JOYSTICK, 0);
        try {
            MotionEvent.class.getMethod("setDisplayId", int.class).invoke(joystick, -1);
            require(getUiAutomation().injectInputEvent(joystick, true), "global joystick motion injected");
        } catch (Exception error) { throw new IllegalStateException(error); }
        finally { joystick.recycle(); }
        settle();
        require(GamepadActivity.motions == motions + 1, "global joystick motion reaches the spatial game");
    }

    private void invoke(Object target, String name) {
        try {
            java.lang.reflect.Method method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true); method.invoke(target);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private void render(String mode, int widthDp, int heightDp, float font, String filename) {
        try {
            View root = activity.inflate(mode, widthDp, heightDp, font);
            int width = widthDp * 2, height = heightDp * 2;
            layout(root, width, height);
            Rect before = relative(root.findViewById(0x7f090140), root);
            PhoneScreenControl button = PhoneScreenControl.attach(root, v -> {});
            layout(root, width, height); layout(root, width, height);
            Rect action = relative(button, root), menu = relative(root.findViewById(0x7f0900c7), root);
            Rect modeBounds = relative(root.findViewById(0x7f0900a0), root);
            require(before.equals(relative(root.findViewById(0x7f090140), root)), "original pad geometry retained: " + filename);
            require(action.top == menu.top && action.bottom == menu.bottom, "action aligns with native toolbar: " + filename);
            require(!Rect.intersects(action, menu) && !Rect.intersects(action, modeBounds), "header actions do not overlap: " + filename);
            require(action.left >= 0 && action.top >= 0 && action.right <= width && action.bottom <= height, "control stays in viewport: " + filename);
            require(action.width() >= 96 && action.height() >= 96, "control retains a 48dp minimum touch target: " + filename);
            require(PhoneScreenControl.attach(root, v -> {}) == button && count(root, PhoneScreenControl.ID) == 1, "reattach is idempotent: " + filename);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            redraw(root);
            root.draw(new Canvas(bitmap));
            try (FileOutputStream stream = new FileOutputStream(new File(output, filename + ".png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            bitmap.recycle();
            JSONObject info = new JSONObject();
            info.put("mode", mode); info.put("file", filename + ".png"); info.put("width_dp", widthDp); info.put("height_dp", heightDp);
            info.put("font_scale", font); info.put("button", action.toShortString()); info.put("mode_selector", modeBounds.toShortString());
            info.put("icon", relative(button.getChildAt(0), root).toShortString());
            renders.put(info);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private void layout(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }
    private void redraw(View view) {
        view.invalidate(); view.jumpDrawablesToCurrentState();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) redraw(((ViewGroup) view).getChildAt(i));
    }
    private Rect relative(View view, View root) {
        Rect bounds = new Rect(0, 0, view.getWidth(), view.getHeight());
        ((ViewGroup) root).offsetDescendantRectToMyCoords(view, bounds);
        return bounds;
    }
    private int count(View view, int id) {
        int n = view.getId() == id ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) n += count(((ViewGroup) view).getChildAt(i), id);
        return n;
    }
    private View proxy() {
        for (View view : WindowInspector.getGlobalWindowViews()) if (view.getLayoutParams() instanceof WindowManager.LayoutParams
                && "Unbound controller screen-off touch target".contentEquals(((WindowManager.LayoutParams) view.getLayoutParams()).getTitle())) return view;
        return null;
    }
    private TextView text(String startsWith, View view) {
        if (view == null) { for (View root : WindowInspector.getGlobalWindowViews()) { TextView found = text(startsWith, root); if (found != null) return found; } return null; }
        if (view instanceof TextView && ((TextView) view).getText().toString().startsWith(startsWith)) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = text(startsWith, ((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private View findWindowView(int id) {
        for (View root : WindowInspector.getGlobalWindowViews()) { View found = root.findViewById(id); if (found != null) return found; }
        return null;
    }
    private void tap(int x, int y) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try { require(getUiAutomation().injectInputEvent(event, true), "physical tap injected"); }
            finally { event.recycle(); }
        }
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(350); waitForIdleSync(); }
    private void require(boolean value, String name) { if (!value) throw new AssertionError(name); passed++; }
    private void write(JSONObject report) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(output, "verification.json"))) {
            stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
}
