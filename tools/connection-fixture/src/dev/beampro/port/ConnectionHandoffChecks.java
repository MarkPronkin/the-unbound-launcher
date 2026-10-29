package dev.beampro.port;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;
import java.util.concurrent.Callable;

/** Checks the real cover, IPC and completed draws, including an inactive optional laser. */
public final class ConnectionHandoffChecks extends Instrumentation {
    static final Uri STATE = Uri.parse("content://dev.beampro.connectiontest.state");
    static final Uri BRIDGE = Uri.parse("content://dev.beampro.portprobe.bridge");
    static volatile Entry entry;
    int checks;
    boolean expectOverlay;
    boolean software, nativeHost;

    public static final class App extends Application {
        @Override public void onCreate() { super.onCreate(); ConnectionHandoff.install(this); }
    }
    public static final class Entry extends Activity {
        ConnectionAnimation animation;
        ConnectionHandoff handoff;
        volatile int completed, failed;
        @Override public void onCreate(Bundle state) { super.onCreate(state); entry = this; }
        void begin() {
            completed = failed = 0;
            animation = new ConnectionAnimation(this, () -> {}, () -> cancel());
            animation.show(this); animation.stage("Starting AR Space");
            handoff = new ConnectionHandoff(this, animation);
            handoff.start(() -> { completed++; handoff.close(false); animation.dispose(); }, () -> { failed++; cancel(); });
        }
        void cancel() { handoff.close(true); animation.dispose(); }
    }
    public static final class State extends ContentProvider {
        Bundle state = new Bundle(), report = new Bundle();
        public boolean onCreate() { return true; }
        public synchronized Bundle call(String method, String arg, Bundle value) {
            if (android.os.Binder.getCallingUid() != getContext().getApplicationInfo().uid) throw new SecurityException();
            if (method.equals("reset")) { state = new Bundle(); report = new Bundle(); }
            if (method.equals("put")) state.putAll(value);
            if (method.equals("report")) report.putAll(value);
            Bundle result = new Bundle(state); result.putAll(report); return result;
        }
        public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
        public String getType(Uri u) { return null; }
        public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
        public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
        public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    }
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments); expectOverlay = !"false".equals(arguments.getString("overlay"));
        software = "true".equals(arguments.getString("software")); nativeHost = "true".equals(arguments.getString("nativeHost")); start();
    }
    private <T> T main(Callable<T> action) throws Exception {
        Object[] result = new Object[1]; Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> { try { result[0] = action.call(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new IllegalStateException(failure[0]);
        @SuppressWarnings("unchecked") T value = (T)result[0]; return value;
    }
    private void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private Bundle state() { return getTargetContext().getContentResolver().call(STATE, "get", null, null); }
    private void set(String key, boolean value) { Bundle data = new Bundle(); data.putBoolean(key, value); getTargetContext().getContentResolver().call(STATE, "put", null, data); }
    private void await(Callable<Boolean> condition, String label) throws Exception {
        long until = SystemClock.uptimeMillis() + 5000;
        while (!condition.call() && SystemClock.uptimeMillis() < until) SystemClock.sleep(30);
        check(condition.call(), label);
    }
    private void waiting(String label) throws Exception {
        SystemClock.sleep(850);
        check(entry.completed == 0 && entry.failed == 0, label);
        check(!(Boolean)field(field(entry.animation, "matrix"), "connected"), "Success animation waits for " + label);
    }
    private static Object field(Object owner, String name) throws Exception {
        java.lang.reflect.Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private void launch() throws Exception {
        getTargetContext().getContentResolver().call(STATE, "reset", null, null);
        main(() -> { entry.begin(); entry.startActivity(new Intent().setClassName(getTargetContext(), "ai.nreal.nebula.space.LaunchSpaceAcrivity")
                .putExtra("software", software).putExtra("nativeHost", nativeHost)); return null; });
        await(() -> state().getInt("rendererPid") != 0, "Renderer created");
        check(state().getInt("rendererPid") != android.os.Process.myPid(), "Readiness crosses the real renderer process boundary");
        boolean overlay = main(() -> entry.animation.getLayoutParams() instanceof WindowManager.LayoutParams
                && ((WindowManager.LayoutParams)entry.animation.getLayoutParams()).type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        check(overlay == expectOverlay, "Correct overlay or permission-free cover path");
        if (!expectOverlay) await(() -> state().getBoolean("coverShown"), "Controller receives its own cover before readiness");
    }
    private void stopController() throws Exception { set("finish", true); await(() -> state().getBoolean("destroyed"), "Controller released"); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent start = new Intent(getTargetContext(), Entry.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivitySync(start); waitForIdleSync();
            launch(); waiting("Empty Unity host");
            set("pointer", true); set("anchor", false); set("advancing", true); set("surface", true);
            set("drawOutput", true);
            await(() -> state().getBoolean("surfaceShown"), "Real glasses Presentation appears"); waiting("Missing phone controller");
            set("controls", true); set("surface", false);
            await(() -> state().getBoolean("controlsShown") && !state().getBoolean("surfaceShown"), "Phone controller appears independently of glasses output");
            waiting("Missing glasses surface");
            set("surface", true); set("anchor", false); waiting("Controller laid out but not drawn");
            set("surface", false); await(() -> !state().getBoolean("surfaceShown"), "Previous output window removed");
            set("drawOutput", false); set("surface", true); set("drawControls", true); waiting("Glasses window not drawn");
            set("drawOutput", true);
            await(() -> entry.completed == 1, "Drawn controls and glasses output release the cover with the optional laser anchor inactive");
            check(state().getBoolean("hardwareControls") != software, "Expected software or hardware drawing path");
            check((Boolean)field(entry.animation, "disposed"), "Completion removes the connection surface");
            SystemClock.sleep(400); check(entry.completed == 1 && entry.failed == 0, "Later frames cannot complete twice");
            stopController();

            launch();
            Bundle old = getTargetContext().getContentResolver().call(BRIDGE, "connection-handoff-get", null, null);
            main(() -> { entry.cancel(); return null; });
            await(() -> state().getBoolean("destroyed"), "Cancellation finishes the covered renderer");
            set("controls", true); set("surface", true); set("pointer", true); set("anchor", true); set("advancing", true);
            SystemClock.sleep(300); check(entry.completed == 0, "Cancelled readiness cannot finish startup");
            check(getTargetContext().getContentResolver().call(BRIDGE, "connection-handoff-get", null, null).isEmpty(), "Cancellation clears IPC state");
            main(() -> { entry.begin(); return null; });
            Bundle current = getTargetContext().getContentResolver().call(BRIDGE, "connection-handoff-get", null, null);
            getTargetContext().getContentResolver().call(BRIDGE, "connection-handoff-clear", old.getString("token"), null);
            check(current.getString("token").equals(getTargetContext().getContentResolver().call(BRIDGE, "connection-handoff-get", null, null).getString("token")), "An old session cannot clear a newer handoff");
            main(() -> { entry.cancel(); entry.finish(); return null; });
            result.putString("stream", "PASS connection handoff Android checks: " + checks + "; overlay=" + expectOverlay
                    + "; software=" + software + "; nativeHost=" + nativeHost + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            try { main(() -> { if (entry != null && entry.handoff != null) entry.cancel(); return null; }); } catch (Exception ignored) { }
            result.putString("stream", "FAIL connection handoff after " + checks + ": " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
