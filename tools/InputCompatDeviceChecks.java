package dev.beampro.inputcompatchecks;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import dev.beampro.port.SettingsCompat;
import java.lang.reflect.*;
import java.util.Objects;

/** Execute the APK's stock input listener, callback state machine and pointer helper. */
public final class InputCompatDeviceChecks extends Instrumentation {
    private static final String FLAG = "xreal_hide_pointicon";
    private int checks;
    private boolean expectBug;
    private HostActivity activity;
    private Object helper;
    private volatile int shown, hidden;
    private final Binder callbackBinder = new Binder();
    public static final class HostActivity extends Activity {
        EditText editor;
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            editor = new EditText(this);
            setContentView(editor);
        }
    }
    private static final class GeometryView extends View {
        int top, gap;
        WindowInsets insets;
        GeometryView(Context context) { super(context); layout(0, 0, 1140, 2616); }
        @Override public void getWindowVisibleDisplayFrame(Rect frame) { frame.set(0, top, 1140, 2616 - gap); }
        @Override public WindowInsets getRootWindowInsets() { return insets; }
        void state(int top, int gap, boolean ime) {
            this.top = top; this.gap = gap;
            insets = new WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 45))
                    .setVisible(WindowInsets.Type.navigationBars(), true)
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, ime ? 800 : 0))
                    .setVisible(WindowInsets.Type.ime(), ime).build();
        }
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); expectBug = Boolean.parseBoolean(args.getString("expect_bug")); start(); }
    private void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }
    private Object construct(String name, Object... args) throws Exception {
        Constructor<?> constructor = Class.forName(name).getDeclaredConstructors()[0];
        constructor.setAccessible(true); return constructor.newInstance(args);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ViewTreeObserver.OnGlobalLayoutListener listener(View root) throws Exception {
        String name = "ai.nreal.nebula.service.InputDetectService";
        Object service = construct(name);
        Class<?> callback = Class.forName("ai.nreal.nebula.input.IInputStateChangedListener");
        IInterface proxy = (IInterface) Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (p, m, args) -> {
            if (m.getName().equals("asBinder")) return callbackBinder;
            if (m.getName().equals("onInputKeyboardShow")) shown++;
            if (m.getName().equals("onInputKeyboardHide")) hidden++;
            return null;
        });
        check(((RemoteCallbackList) field(service, "inputStateChangedListeners")).register(proxy), "Original callback registered");
        Object outer = construct(name + "$1", service);
        return (ViewTreeObserver.OnGlobalLayoutListener) construct(name + "$1$1", outer, root);
    }
    private void mainCheck(Checked action) {
        final Throwable[] error = new Throwable[1];
        runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
    private interface Checked { void run() throws Exception; }
    private void geometry() {
        mainCheck(() -> {
            GeometryView view = new GeometryView(getTargetContext());
            ViewTreeObserver.OnGlobalLayoutListener listener = listener(view);
            shown = hidden = 0;
            view.state(0, 45, false);
            listener.onGlobalLayout();
            check(shown == (expectBug ? 1 : 0) && hidden == 0, "OPPO 2616/2571 navigation gap classification");
            listener.onGlobalLayout();
            check(shown == (expectBug ? 1 : 0), "Repeated layout does not duplicate callback");
            if (expectBug) return;
            view.state(107, 45, false);
            view.insets = new WindowInsets.Builder(view.insets)
                    .setInsets(WindowInsets.Type.displayCutout(), Insets.of(0, 107, 0, 0))
                    .setVisible(WindowInsets.Type.displayCutout(), true).build();
            listener.onGlobalLayout();
            check(shown == 0 && hidden == 0, "Status bar and cutout do not imply a keyboard");
            view.state(107, 0, true);
            listener.onGlobalLayout();
            check(shown == 1 && hidden == 0, "IME shown despite status bar and unresized root");
            listener.onGlobalLayout();
            check(shown == 1, "Repeated IME show emits one callback");
            view.state(0, 45, false);
            listener.onGlobalLayout();
            check(shown == 1 && hidden == 1, "IME hides while navigation bar remains");
            view.state(0, 900, false);
            listener.onGlobalLayout();
            check(shown == 1 && hidden == 1, "Large geometry change without an IME stays hidden");
            view.state(0, 0, true);
            listener.onGlobalLayout();
            check(shown == 2, "Floating IME visibility is honored without an occlusion gap");
            view.insets = null;
            listener.onGlobalLayout();
            check(hidden == 2, "Detached root cannot leave a stale keyboard state");
        });
    }
    private void realIme() throws Exception {
        activity = (HostActivity) startActivitySync(new Intent(getTargetContext(), HostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final ViewTreeObserver.OnGlobalLayoutListener[] listener = new ViewTreeObserver.OnGlobalLayoutListener[1];
        mainCheck(() -> {
            shown = hidden = 0;
            listener[0] = listener(activity.getWindow().getDecorView());
            activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(listener[0]);
            activity.editor.requestFocus();
        });
        await(() -> activity.editor.hasWindowFocus() && activity.getSystemService(InputMethodManager.class).isActive(activity.editor),
                "Editor is served before requesting the real keyboard");
        mainCheck(() -> check(activity.getSystemService(InputMethodManager.class).showSoftInput(activity.editor, InputMethodManager.SHOW_IMPLICIT),
                "Android accepts the real keyboard request"));
        await(() -> shown > 0, "Real Android IME shows through the stock callback");
        mainCheck(() -> check(activity.getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime()), "Real IME inset is visible"));
        mainCheck(() -> activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(activity.editor.getWindowToken(), 0));
        await(() -> hidden > 0, "Real Android IME hides through the stock callback");
        mainCheck(() -> {
            check(!activity.getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime()), "Real IME inset is hidden");
            activity.getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(listener[0]);
        });
    }
    private interface Condition { boolean value() throws Exception; }
    private void await(Condition condition, String message) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 8000;
        while (!condition.value() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20);
        check(condition.value(), message);
    }
    private void pointerFlags() throws Exception {
        Context context = getTargetContext();
        check(context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_DENIED,
                "No protected settings permission granted");
        String global = Settings.Global.getString(context.getContentResolver(), FLAG);
        mainCheck(() -> {
            Class<?> type = Class.forName("com.xreal.evapro.InputHelper");
            helper = type.getMethod("getInstance").invoke(null);
            type.getMethod("init", Context.class).invoke(helper, context);
        });
        Method write = helper.getClass().getMethod("hidePointerIcon", boolean.class, boolean.class);
        Method read = helper.getClass().getMethod("isPointerIconHidden");
        if (expectBug) {
            try { write.invoke(helper, true, false); throw new AssertionError("Stock pointer write unexpectedly succeeded"); }
            catch (InvocationTargetException error) { check(error.getCause() instanceof SecurityException, "Stock pointer permission failure reproduced"); }
        } else {
            context.getContentResolver().call(android.net.Uri.parse("content://" + context.getPackageName() + ".flags"), "flag-put", FLAG, new Bundle());
            check(!(Boolean) read.invoke(helper), "Missing private pointer flag defaults to visible");
            for (int cycle = 0; cycle < 6; cycle++) for (boolean remote : new boolean[]{false, true}) for (boolean hide : new boolean[]{true, false}) {
                SettingsCompat.putGlobalInt(context.getContentResolver(), FLAG, hide ? 0 : 1);
                write.invoke(helper, hide, remote);
                await(() -> SettingsCompat.getGlobalInt(context.getContentResolver(), FLAG, -1) == (hide ? 1 : 0), "Pointer write acknowledged: remote=" + remote);
                check((Boolean) read.invoke(helper) == hide, "Pointer helper reads the same state as the separate service");
                check(Objects.equals(global, Settings.Global.getString(context.getContentResolver(), FLAG)), "Global pointer setting unchanged");
            }
        }
        check(Objects.equals(global, Settings.Global.getString(context.getContentResolver(), FLAG)), "Global pointer flag preserved");
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            geometry();
            if (!expectBug) realIme();
            if (activity == null) activity = (HostActivity) startActivitySync(new Intent(getTargetContext(), HostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            pointerFlags();
            result.putBoolean("passed", true);
            result.putString("stream", "PASS phone input checks: " + checks + "; expect_bug=" + expectBug + "\n");
        } catch (Throwable error) {
            result.putString("stream", "FAIL phone input after " + checks + ": " + android.util.Log.getStackTraceString(error));
        } finally {
            getTargetContext().stopService(new Intent().setClassName(getTargetContext(), "com.xreal.evapro.JobService"));
            if (activity != null) runOnMainSync(activity::finish);
        }
        finish(result.getBoolean("passed") ? -1 : 0, result);
    }
}
