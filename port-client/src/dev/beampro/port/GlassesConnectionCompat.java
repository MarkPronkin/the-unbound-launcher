package dev.beampro.port;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Connection recovery for the port's manual launch path, without the Beam USB activity handler. */
public final class GlassesConnectionCompat {
    private static final String TAG = "UnboundConnect";
    private static final long DEADLINE_MS = 45000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, Boolean> blocked = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, WeakReference<Session>> callbacks = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, WeakReference<Session>> running = Collections.synchronizedMap(new WeakHashMap<>());
    private static final WeakHashMap<Activity, SettingsWatcher> settings = new WeakHashMap<>();
    private static volatile Session current;
    private static boolean installed;

    public static void install(Context context) {
        if (installed) return;
        installed = true;
        ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {
                if (a.getClass().getName().equals("ai.nreal.nebula.activity.setting.SettingActivity")) {
                    stopSettings(a);
                    SettingsWatcher watch = new SettingsWatcher(a);
                    settings.put(a, watch); watch.run();
                }
            }
            public void onActivityPaused(Activity a) { stopSettings(a); }
            public void onActivityStopped(Activity a) { stopSettings(a); }
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) {
                stopSettings(a);
                if (current != null && current.activity == a) current.dispose();
            }
        });
    }

    private static void stopSettings(Activity activity) {
        SettingsWatcher watch = settings.remove(activity);
        if (watch != null) { watch.stopped = true; MAIN.removeCallbacks(watch); }
    }
    /** Re-run the original button policy when USB changes while Settings stays visible. */
    static final class SettingsWatcher implements Runnable {
        final WeakReference<Activity> owner;
        String previous;
        boolean stopped;
        SettingsWatcher(Activity a) { owner = new WeakReference<>(a); }
        public void run() {
            Activity a = owner.get();
            if (stopped || a == null || a.isFinishing() || a.isDestroyed()) return;
            try {
                java.util.ArrayList<String> names = new java.util.ArrayList<>();
                for (Map.Entry<String, UsbDevice> entry : a.getSystemService(UsbManager.class).getDeviceList().entrySet())
                    if (entry.getValue().getVendorId() == 13080) names.add(entry.getKey());
                Collections.sort(names);
                String signature = names.toString();
                if (!signature.equals(previous)) { previous = signature; call(a, "check3DBtn"); }
            } catch (Exception error) { Log.w(TAG, "Cannot refresh glasses connection action", error); }
            if (!stopped) MAIN.postDelayed(this, 500);
        }
    }

    static Field field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException absent) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    static Object get(Object target, String name) throws Exception { return field(target, name).get(target); }
    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args);
    }
    static Object call(Object target, String name) throws Exception { return call(target, name, new Class<?>[0]); }
    static Object manager() throws Exception {
        return Class.forName("ai.nreal.nebula.manager.XREALManager").getMethod("getInstance").invoke(null);
    }
    static Session session(Object presenter) {
        return current != null && current.presenter == presenter && !current.disposed ? current : null;
    }
    /** Suppress duplicate USB auto-entry during SDK re-enumeration and an existing AR session. */
    public static boolean sessionActive() {
        Session s=current;
        if(s!=null&&!s.disposed&&!s.failed)return true;
        try {
            Object manager=manager();
            return (Boolean)call(manager,"isHasGoLauncher") && (Boolean)call(get(manager,"mNRServiceControl"),"isStartSdk");
        } catch(Exception notInitialized) { return false; }
    }

    /** Replaces only the public no-argument entry; retain the SDK's USB/model registration. */
    public static void begin(Object presenter) {
        try {
            if (session(presenter) != null && !current.failed) return;
            if (current != null && current.busy) { current.fail("Glasses are still responding. Unplug and reconnect them before trying again."); return; }
            if (current != null) current.dispose(current.presenter != presenter);
            current = new Session(presenter);
            WeakReference<Session> prior = running.get(current.manager);
            Session pending = prior == null ? null : prior.get();
            if (pending != null && pending.busy) {
                current.busy = true;
                current.fail("The previous glasses request is still finishing. Close this screen and reconnect the glasses before trying again.");
                return;
            }
            blocked.remove(current.manager);
            current.start();
        } catch (Exception error) {
            Log.e(TAG, "Cannot start glasses connection", error);
            if (current != null && current.presenter == presenter)
                current.error("Cannot start the glasses connection. Reconnect them and try again.", error);
        }
    }

    /** Firmware updates are disabled in the port. Do not block AR startup on cloud/OTA probes. */
    public static void prepare(Object presenter) {
        Session s = session(presenter);
        if (s == null || s.failed || s.preparing) return;
        s.preparing = true;
        s.stage("Preparing glasses");
        s.work.post(() -> {
            if (!s.active()) return;
            s.busy = true;
            running.put(s.manager, new WeakReference<>(s));
            try {
                if (s.active()) call(presenter, "_goToSdk");
            } catch (Exception error) { MAIN.post(() -> s.error("Cannot prepare the glasses. Reconnect them and try again.", error)); }
            finally { if (!s.sdkPending) s.finishedWork(); }
        });
    }

    /** Keep the original SDK and per-model display selector; own its callback lifetime. */
    public static void startSdk(Object presenter) {
        Session s = session(presenter);
        if (s == null || !s.active() || s.sdkPending) return;
        s.sdkPending = true; s.busy = true;
        running.put(s.manager, new WeakReference<>(s));
        s.stage("Starting AR Space");
        try {
            // USB re-enumeration resets the base model identity. Restore the SKU
            // before every SDK start, including the reconnect continuation.
            int type = ((Number) get(s.manager, "mGlassesType")).intValue();
            if (type == 6 || type == 20) {
                String sku = (String) call(get(s.manager, "mOtaManager"), "getSku");
                if (sku != null && !sku.isEmpty() && s.active())
                    Class.forName("com.xreal.basicfunc.BasicFuncManager").getMethod("setGlassesType", String.class)
                            .invoke(null, type + "_" + sku);
            }
            if (!s.active()) { s.sdkPending = false; s.finishedWork(); return; }
            Class<?> callback = Class.forName("ai.nreal.nebula.manager.callback.ISdkStartResult");
            Object proxy = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (p, m, args) -> {
                if (m.getName().equals("onResult")) {
                    int result = ((Number) args[0]).intValue();
                    MAIN.post(() -> s.result(result));
                    return null;
                }
                if (m.getName().equals("toString")) return TAG;
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == args[0];
                return null;
            });
            callbacks.put(proxy, new WeakReference<>(s));
            call(s.manager, "startSdk", new Class<?>[]{callback}, proxy);
        } catch (Exception error) {
            s.sdkPending = false; s.finishedWork();
            MAIN.post(() -> s.error("The glasses could not start AR Space. Reconnect them and try again.", error));
        }
    }

    /** Reject a stale native completion before the original callback mutates display state. */
    public static boolean acceptSdkCallback(Object callback) {
        WeakReference<Session> reference = callbacks.get(callback);
        if (reference == null) return true; // SDK calls outside this connection flow.
        Session owner = reference.get();
        if (owner != null && owner.active()) return true;
        if (owner != null) { owner.sdkPending = false; owner.finishedWork(); }
        Log.i(TAG, "Discarded stale native initialization callback");
        return false;
    }

    /** Hook only the three asynchronous SDK launches, leaving direct user launches intact. */
    public static void launch(Object manager) {
        if (Boolean.TRUE.equals(blocked.get(manager))) { Log.i(TAG, "Discarded a cancelled startup launch"); return; }
        try {
            if ((Boolean) call(manager, "isHasGoLauncher")) return;
            call(manager, "goLauncher");
        } catch (Exception error) {
            Log.e(TAG, "Cannot launch AR Space", error);
            Session s = current;
            if (s != null && s.manager == manager) MAIN.post(() -> s.error("AR Space could not open. Tap Try again.", error));
        }
    }

    /** The stock confirmation kills the entire app. Offer recovery within the current task. */
    public static void confirm(Object presenter) {
        Session s = session(presenter);
        if (s == null) { begin(presenter); return; }
        if (s.busy) { s.close(); return; }
        try {
            s.detachCallbacks();
            // A failed SDK callback has completed; serialize cleanup before retrying.
            s.work.post(() -> {
                try { call(get(s.manager, "mNRServiceControl"), "stopSdk"); }
                catch (Exception error) { Log.w(TAG, "SDK cleanup before retry", error); }
                MAIN.post(() -> {
                    if (!s.disposed) {
                        try { field(s.manager, "hasGoLauncher").setBoolean(s.manager, false); }
                        catch (Exception error) { Log.w(TAG, "Cannot reset launch state", error); }
                        s.failed = true; begin(presenter);
                    }
                });
            });
        } catch (Exception error) { s.error("Unplug and reconnect the glasses, then try again.", error); }
    }
    public static void cancel(Activity activity) {
        if (current != null && current.activity == activity) current.close();
        else activity.finish();
    }
    /** A fresh automatic attachment may reach an existing connection sheet. */
    public static void newIntent(Object presenter) {
        Session s=session(presenter);
        if(s==null) { begin(presenter);return; }
        try {
            String status=String.valueOf(get(presenter,"mOtaStatus"));
            if(s.failed || status.equals("sdkFail") || status.equals("otaFail")) {
                if(s.busy)s.fail("The previous glasses request is still finishing. Reconnect the glasses before trying again.");
                else confirm(presenter);
                return;
            }
            if((Boolean)get(presenter,"hasMTP")) {
                field(presenter,"hasMTP").setBoolean(presenter,false);
                Class<?> init=Class.forName("com.xreal.glassesdisplayplugevent.GlassesInitSetting");
                Class<?> event=Class.forName("com.xreal.glassesdisplayplugevent.IGlassesEvent$IDisplayEvent");
                init.getMethod("unregisterIDisplayEvent",event).invoke(init.getMethod("getInstance").invoke(null),get(presenter,"iDisplayEvent"));
                call(presenter,"checkUsb",new Class<?>[]{boolean.class},true);
            }
        } catch(Exception error) { s.error("Cannot resume the glasses connection. Reconnect them and try again.",error); }
    }

    /** USB reconfiguration may also drop DisplayPort; it is not an SDK failure. */
    public static void usbDisplayRemoved(Object listener) {
        try {
            Session s = session(get(listener, "this$0"));
            if (s != null && s.active()) {
                Log.i(TAG, "Display detached during USB reconfiguration; awaiting live USB readiness");
                s.stage("Waiting for glasses to reconnect");
            }
        } catch (Exception error) { Log.w(TAG, "USB/display reconnect event", error); }
    }

    static final class Session implements Runnable {
        final Object presenter, manager;
        final Activity activity;
        final Handler work;
        final UsbManager usb;
        final DisplayManager displays;
        final long deadline = SystemClock.uptimeMillis() + DEADLINE_MS;
        volatile boolean failed, disposed, busy, sdkPending;
        boolean preparing, success, checkingDisplay;
        long mtpSince, readySince;
        int resumes;
        String phase = "Waiting for glasses USB access";

        Session(Object presenter) throws Exception {
            this.presenter = presenter;
            manager = manager();
            activity = (Activity) get(presenter, "mActivity");
            work = (Handler) get(presenter, "mHandler");
            usb = activity.getSystemService(UsbManager.class);
            displays = activity.getSystemService(DisplayManager.class);
        }
        boolean active() { return current == this && !failed && !success && !disposed && !activity.isFinishing() && !activity.isDestroyed(); }
        void finishedWork() {
            synchronized (running) {
                WeakReference<Session> reference = running.get(manager);
                if (reference != null && reference.get() == this) running.remove(manager);
                busy = false;
            }
        }
        void start() throws Exception {
            stage(phase);
            MAIN.postDelayed(this, 250);
            call(presenter, "checkUsb", new Class<?>[]{boolean.class}, false);
        }
        void stage(String text) {
            phase = text;
            Log.i(TAG, text);
            MAIN.post(() -> {
                if (!active()) return;
                try { ((TextView) get(activity, "mTvSubTitle")).setText(text); }
                catch (Exception error) { Log.w(TAG, "Cannot update connection status", error); }
            });
        }
        UsbDevice liveUsb() {
            for (UsbDevice d : usb.getDeviceList().values()) if (d.getVendorId() == 13080 && usb.hasPermission(d)) return d;
            return null;
        }
        @Override public void run() {
            if (!active()) return;
            try {
                long now = SystemClock.uptimeMillis();
                if (now >= deadline) { fail("Connection timed out while " + phase.toLowerCase() + ". Unplug and reconnect the glasses, then try again."); return; }
                if ((Boolean) get(presenter, "hasMTP")) {
                    if (mtpSince == 0) { mtpSince = now; stage("Waiting for glasses to reconnect"); }
                    // The USB handler Activity was deliberately removed from the port.
                    // Resume its former onNewIntent continuation from live USB readiness,
                    // including a re-enumeration that finishes before hasMTP is set.
                    boolean connected = (Boolean) get(manager, "mUsbConnect");
                    long delay = Math.min(5000L, 1500L << Math.min(resumes, 2));
                    if (connected && liveUsb() != null && now - mtpSince >= delay) {
                        resumes++;
                        mtpSince = 0;
                        Log.i(TAG, "Resuming SDK after USB configuration change without an activity intent");
                        call(presenter, "onNewIntent");
                    }
                } else {
                    mtpSince = 0;
                    if (get(manager, "mDisplayEvent") != null && !checkingDisplay && liveUsb() != null) {
                        checkingDisplay = true;
                        work.post(() -> checkDisplay());
                    }
                }
            } catch (Exception error) { error("Cannot finish the glasses connection. Reconnect them and try again.", error); return; }
            if (active()) MAIN.postDelayed(this, 250);
        }
        void checkDisplay() {
            boolean ready = false;
            try {
                if (!active()) return;
                Object sdk = get(manager, "mNRServiceControl");
                int target = ((Number) call(manager, "getFinalDisplayConfig")).intValue();
                int actual = ((Number) call(sdk, "get2D3D")).intValue();
                if ((Boolean) call(sdk, "isStartSdk") && target == actual) {
                    boolean ethernet = Boolean.TRUE.equals(get(sdk, "isEthernetSeries"));
                    boolean stereo = ethernet ? target >= 5 && target <= 8
                            : target == 2 || target == 3 || target == 4 || target == 9;
                    for (Display display : displays.getDisplays()) if (DisplayStartupCompat.xreal(display)
                            && display.getState() == Display.STATE_ON && (!stereo
                            || (display.getMode().getPhysicalWidth() == 3840 && display.getMode().getPhysicalHeight() == 1080))) ready = true;
                }
            } catch (Exception error) { Log.w(TAG, "Waiting for display readiness", error); }
            finally {
                final boolean found = ready;
                MAIN.post(() -> {
                    checkingDisplay = false;
                    if (!active()) return;
                    long now = SystemClock.uptimeMillis();
                    if (!found) { readySince = 0; return; }
                    if (readySince == 0) { readySince = now; return; }
                    if (now - readySince < 500) return;
                    try {
                        detachDisplay();
                        Log.i(TAG, "Display ready; completing startup without a display-removed event");
                        launch(manager);
                        result(0);
                    } catch (Exception error) { error("Cannot open AR Space on the glasses.", error); }
                });
            }
        }
        void result(int result) {
            sdkPending = false; finishedWork();
            if (!active()) { Log.i(TAG, "Discarded a late SDK result=" + result); return; }
            if (result == 1) { fail("The glasses could not start AR Space. Reconnect them, then tap Try again."); return; }
            if (result == 0) { success = true; MAIN.removeCallbacks(this); }
            try {
                for (Method method : presenter.getClass().getDeclaredMethods())
                    if (method.getName().startsWith("lambda$_goToSdk$") && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{int.class})) {
                        method.setAccessible(true); method.invoke(presenter, result); return;
                    }
                throw new NoSuchMethodException("Original SDK result callback");
            } catch (Exception error) { error("Cannot finish starting AR Space.", error); }
        }
        void detachDisplay() throws Exception {
            Object listener = get(manager, "mDisplayEvent");
            if (listener == null) return;
            Class<?> init = Class.forName("com.xreal.glassesdisplayplugevent.GlassesInitSetting");
            Object singleton = init.getMethod("getInstance").invoke(null);
            Class<?> event = Class.forName("com.xreal.glassesdisplayplugevent.IGlassesEvent$IDisplayEvent");
            init.getMethod("unregisterIDisplayEvent", event).invoke(singleton, listener);
            field(manager, "mDisplayEvent").set(manager, null);
        }
        void detachCallbacks() throws Exception {
            call(manager, "unregisterGlasses", new Class<?>[]{Object.class}, presenter);
            detachDisplay();
        }
        void error(String message, Exception error) { Log.e(TAG, message, error); fail(message); }
        void fail(String message) {
            if (disposed || success) return;
            failed = true;
            blocked.put(manager, true);
            MAIN.removeCallbacks(this);
            Log.w(TAG, message);
            try {
                detachCallbacks();
                ((TextView) get(activity, "mTvTitle")).setText("Glasses connection paused");
                ((TextView) get(activity, "mTvSubTitle")).setText(message);
                ((View) get(activity, "mTvStatus")).setVisibility(View.GONE);
                ((View) get(activity, "mProgressBar")).setVisibility(View.GONE);
                Button button = (Button) get(activity, "mBtnConfirm");
                button.setText(busy ? "Close" : "Try again");
                button.setVisibility(View.VISIBLE);
            } catch (Exception error) { Log.e(TAG, "Cannot show connection recovery", error); }
        }
        void close() {
            if (!success) blocked.put(manager, true);
            dispose();
            activity.finish();
        }
        void dispose() { dispose(true); }
        void dispose(boolean stopThread) {
            if (disposed) return;
            disposed = true;
            MAIN.removeCallbacks(this);
            if (!success) blocked.put(manager, true);
            try {
                detachCallbacks();
                // Stop queued startup work and the stock presenter's otherwise unclosed thread.
                ((Handler) get(presenter, "mMainHandler")).removeCallbacksAndMessages(null);
                if (stopThread) {
                    if (!success) work.post(() -> {
                        try { call(get(manager, "mNRServiceControl"), "stopSdk"); }
                        catch (Exception error) { Log.w(TAG, "Cancelled SDK cleanup", error); }
                    });
                    ((android.os.HandlerThread) get(presenter, "mHandlerThread")).quitSafely();
                }
            } catch (Exception error) { Log.w(TAG, "Connection cleanup", error); }
            if (current == this) current = null;
        }
    }
    private GlassesConnectionCompat() {}
}
