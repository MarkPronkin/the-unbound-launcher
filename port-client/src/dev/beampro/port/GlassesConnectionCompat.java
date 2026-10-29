package dev.beampro.port;

import dev.beampro.compat.GlassesIdentity;
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
    private static final java.util.concurrent.atomic.AtomicLong ATTEMPTS = new java.util.concurrent.atomic.AtomicLong();
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
                    if (GlassesIdentity.usb(entry.getValue().getVendorId(), entry.getValue().getProductId())) names.add(entry.getKey());
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
            if (current != null && current.isBusy()) { current.fail("Glasses are still responding. Unplug and reconnect them before trying again."); return; }
            if (current != null) current.dispose(current.presenter != presenter);
            current = new Session(presenter);
            current.debug("begin", "New connection attempt");
            WeakReference<Session> prior = running.get(current.manager);
            Session pending = prior == null ? null : prior.get();
            if (pending != null && pending.busy) {
                current.waitingFor = pending;
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

    /** Keep cloud/OTA writes disabled; the SDK entry first performs a read-only firmware gate. */
    public static void prepare(Object presenter) {
        Session s = session(presenter);
        if (s == null || s.failed || s.preparing) return;
        s.preparing = true;
        s.stage("Checking glasses firmware");
        s.work.post(() -> {
            if (!s.active()) return;
            try {
                if (s.active()) call(presenter, "_goToSdk");
            } catch (Exception error) { MAIN.post(() -> s.error("Cannot prepare the glasses. Reconnect them and try again.", error)); }
        });
    }

    /** Keep the original SDK and per-model display selector; own its callback lifetime. */
    public static void startSdk(Object presenter) {
        Session s = session(presenter);
        if (s == null || !s.active() || s.sdkPending || s.sdkReady) return;
        if (s.firmwareAccepted && !s.firmwareUsb.equals(s.liveUsb())) {
            UsbDevice reconnected=s.liveUsb();
            if (s.usbReconfiguration && s.sameUsbFamily(reconnected)) {
                // SDK result 2 deliberately changes USB configuration. This is
                // the same connection, including its explicit override.
                s.firmwareUsb=reconnected;s.usbReconfiguration=false;
            } else s.firmwareAccepted=false;
        }
        // All entry paths, including the original MTP continuation, must pass
        // this before SKU reads, Ethernet setup, nativeGlassesInit or mode changes.
        if (!s.firmwareAccepted) { s.checkFirmware(); return; }
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
            s.debug("sdk_start", "Calling the original SDK startup path");
            s.sdkInvoked = true;
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
        if (owner != null && owner.active() && !owner.sdkReady) return true;
        if (owner != null) { owner.debug("stale_callback", "Rejected late native initialization callback"); owner.sdkPending = false; owner.finishedWork(); }
        Log.i(TAG, "Discarded stale native initialization callback");
        return false;
    }

    /** Hook only the three asynchronous SDK launches, leaving direct user launches intact. */
    public static void launch(Object manager) {
        if (Boolean.TRUE.equals(blocked.get(manager))) { Log.i(TAG, "Discarded a cancelled startup launch"); return; }
        try {
            if ((Boolean) call(manager, "isHasGoLauncher")) return;
            Session s = current;
            if (s != null && s.manager == manager && !s.disposed && !s.failed && !s.handoffFinished) {
                return;
            }
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
        if (s.firmwarePaused || s.firmwareChecking) return; // Only the explicit warning override may proceed.
        s.debug("retry", "Retry requested");
        if (s.isBusy()) { s.close(); return; }
        try {
            s.detachCallbacks();
            // A failed SDK callback has completed; serialize cleanup before retrying.
            s.work.post(() -> {
                try { if (s.sdkInvoked) call(get(s.manager, "mNRServiceControl"), "stopSdk"); }
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
    /** Original USB callbacks still own model detection; the phone owns its connection UI. */
    public static void status(Activity activity, Object status) {
        MAIN.post(() -> {
            Session s = current;
            if (s == null || s.activity != activity || !s.active()) return;
            String value = String.valueOf(status);
            s.debug("usb_status", value);
            if (s.firmwarePaused || s.firmwareChecking) {
                if (s.liveUsb() == null) s.close();
                return;
            }
            if (value.equals("sdkFail") || value.equals("otaFail")) {
                try {
                    if ((Boolean)get(s.presenter, "hasMTP")) {
                        s.stage("Waiting for glasses to reconnect"); return;
                    }
                } catch (Exception error) { Log.w(TAG, "Cannot read USB reconnect state", error); }
                s.fail("The glasses disconnected or could not start. Reconnect them, then try again.");
            } else if (s.animation != null) {
                s.animation.stage(s.phase);
            }
        });
    }
    /** A fresh automatic attachment may reach an existing connection sheet. */
    public static void newIntent(Object presenter) {
        Session s=session(presenter);
        if(s==null) { begin(presenter);return; }
        if(s.firmwarePaused || s.firmwareChecking) return;
        s.debug("usb_resume", "Attachment continuation received");
        try {
            String status=String.valueOf(get(presenter,"mOtaStatus"));
            if(s.failed || status.equals("sdkFail") || status.equals("otaFail")) {
                if(s.isBusy())s.fail("The previous glasses request is still finishing. Reconnect the glasses before trying again.");
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
        final String attempt = Long.toHexString(System.currentTimeMillis()) + "-" + ATTEMPTS.incrementAndGet();
        final Activity activity;
        final Handler work;
        final UsbManager usb;
        final DisplayManager displays;
        long deadline = SystemClock.uptimeMillis() + DEADLINE_MS;
        volatile boolean failed, disposed, busy, sdkPending, sdkReady;
        volatile boolean firmwareChecking, firmwarePaused, firmwareAccepted, sdkInvoked;
        long firmwareDeadline;
        int firmwareType;
        UsbDevice firmwareUsb;
        FirmwareWarningView firmwareWarning;
        FirmwarePolicy.Result firmwareResult;
        Session waitingFor;
        boolean preparing, success, checkingDisplay, handoffFinished, usbReconfiguration;
        long mtpSince, readySince;
        int resumes;
        String phase = "Waiting for glasses USB access";
        ConnectionAnimation animation;
        ConnectionHandoff handoff;

        Session(Object presenter) throws Exception {
            this.presenter = presenter;
            manager = manager();
            activity = (Activity) get(presenter, "mActivity");
            work = (Handler) get(presenter, "mHandler");
            usb = activity.getSystemService(UsbManager.class);
            displays = activity.getSystemService(DisplayManager.class);
            try {
                animation = new ConnectionAnimation(activity, () -> confirm(presenter), () -> cancel(activity));
                animation.show(activity);
            } catch (RuntimeException error) {
                if (animation != null) animation.dispose();
                animation = null;
                Log.w(TAG, "Cannot show connection animation", error);
            }
        }
        boolean active() { return current == this && !failed && !success && !disposed && !activity.isFinishing() && !activity.isDestroyed(); }
        boolean isBusy() { return busy || (waitingFor != null && waitingFor.busy); }
        void finishedWork() {
            synchronized (running) {
                WeakReference<Session> reference = running.get(manager);
                if (reference != null && reference.get() == this) running.remove(manager);
                busy = false;
            }
            Session waiting = current;
            if (waiting != null && waiting.waitingFor == this) MAIN.post(() -> {
                if (current == waiting && waiting.failed && !waiting.disposed && waiting.waitingFor == this) {
                    waiting.waitingFor = null;
                    waiting.fail("The previous glasses request finished. Reconnect the glasses, then tap Try again.");
                }
            });
        }
        void start() throws Exception {
            stage(phase);
            MAIN.postDelayed(this, 250);
            call(presenter, "checkUsb", new Class<?>[]{boolean.class}, false);
        }
        void stage(String text) {
            phase = text;
            Log.i(TAG, text);
            debug("stage", text);
            MAIN.post(() -> {
                if (!active()) return;
                if (animation != null) animation.stage(text);
                try { ((TextView) get(activity, "mTvSubTitle")).setText(text); }
                catch (Exception error) { Log.w(TAG, "Cannot update connection status", error); }
            });
        }
        /** Read Java bookkeeping only: diagnostic getters must not issue extra glasses commands. */
        void debug(String event, String detail) {
            try {
                StringBuilder state = new StringBuilder(detail).append("; phase=").append(phase)
                        .append("; busy=").append(busy).append("; sdkPending=").append(sdkPending)
                        .append("; sdkReady=").append(sdkReady).append("; failed=").append(failed)
                        .append("; resumes=").append(resumes).append("; deadlineMs=").append(deadline);
                diagnosticField(state, "model", manager, "mGlassesType");
                diagnosticField(state, "usbConnected", manager, "mUsbConnect");
                diagnosticField(state, "hasMTP", presenter, "hasMTP");
                Object sdk = get(manager, "mNRServiceControl");
                diagnosticField(state, "nativeStarted", sdk, "isStartSdk");
                diagnosticField(state, "ethernetSeries", sdk, "isEthernetSeries");
                diagnosticField(state, "dpInitialized", sdk, "mDpInitStateGSeries");
                Class<?> debug = Class.forName("dev.beampro.settings.DebugData");
                Method record = debug.getDeclaredMethod("connectionEvent", Context.class, String.class, String.class, String.class);
                record.setAccessible(true); record.invoke(null, activity, attempt, event, state.toString());
            } catch (ClassNotFoundException previewWithoutDebug) { }
            catch (Exception error) { Log.w(TAG, "Cannot record startup diagnostics", error); }
        }
        private void diagnosticField(StringBuilder state, String label, Object owner, String name) {
            state.append("; ").append(label).append('=');
            try { state.append(get(owner, name)); }
            catch (Exception unavailable) { state.append("unavailable"); }
        }
        UsbDevice liveUsb() {
            for (UsbDevice d : usb.getDeviceList().values()) if (GlassesIdentity.usb(d.getVendorId(), d.getProductId()) && usb.hasPermission(d)) return d;
            return null;
        }
        boolean sameUsbFamily(UsbDevice device) {
            if(device==null||firmwareUsb==null||device.getVendorId()!=firmwareUsb.getVendorId())return false;
            if(GlassesIdentity.rogR1Usb(device.getVendorId(),device.getProductId()))
                return GlassesIdentity.rogR1Usb(firmwareUsb.getVendorId(),firmwareUsb.getProductId());
            // XREAL boot/app USB identities are adjacent odd/even pairs.
            return (device.getProductId()+1)/2==(firmwareUsb.getProductId()+1)/2;
        }
        void checkFirmware() {
            synchronized(this) {
                if (!active() || firmwareChecking || firmwarePaused || firmwareAccepted) return;
                firmwareUsb=liveUsb();
                if (firmwareUsb==null) { MAIN.post(() -> fail("Allow USB access to the glasses, then reconnect them to check their firmware.")); return; }
                try {
                    firmwareType=GlassesIdentity.rogR1Usb(firmwareUsb.getVendorId(),firmwareUsb.getProductId())
                            ?1003:((Number)get(manager,"mGlassesType")).intValue();
                } catch(Exception error) { MAIN.post(() -> error("Cannot identify the glasses for their firmware check.",error)); return; }
                firmwareChecking=true;busy=true;
                firmwareDeadline=SystemClock.uptimeMillis()+15000;
                running.put(manager,new WeakReference<>(this));
            }
            stage("Checking glasses firmware");
            debug("firmware_check","Reading firmware before SDK initialization");
            work.post(() -> {
                FirmwarePolicy.Result result;
                try {
                    Object reader=get(manager,"mOtaManager");
                    result=FirmwarePolicy.check(firmwareType,component -> {
                        if (!active() || !firmwareUsb.equals(liveUsb())) throw new IllegalStateException("Glasses detached during firmware read");
                        String version=(String)call(reader,"readFWVersion",new Class<?>[]{int.class},component);
                        if (!active() || !firmwareUsb.equals(liveUsb())) throw new IllegalStateException("Glasses detached during firmware read");
                        return version;
                    });
                } catch(Exception|LinkageError error) {
                    Log.w(TAG,"Cannot verify glasses firmware",error);
                    result=FirmwarePolicy.unavailable(firmwareType,"The glasses firmware could not be read. Compatibility could not be verified.");
                }
                final FirmwarePolicy.Result checked=result;
                MAIN.post(() -> {
                    firmwareChecking=false;finishedWork();
                    if (!active()) return;
                    if (!firmwareUsb.equals(liveUsb())) { close(); return; }
                    firmwareResult=checked;
                    debug("firmware_result","supported="+checked.supported+"; detected="+checked.detected
                            +"; recommended="+checked.recommended+"; "+checked.detail);
                    if (checked.supported) resumeAfterFirmware(false);
                    else pauseForFirmware(checked);
                });
            });
        }
        void pauseForFirmware(FirmwarePolicy.Result result) {
            if (!active()) return;
            firmwarePaused=true;firmwareResult=result;phase="Firmware verification paused";
            blocked.put(manager,true);
            debug("firmware_paused",result.detail);
            try {
                if (firmwareWarning==null) {
                    firmwareWarning=new FirmwareWarningView(activity,result,this::close,() -> resumeAfterFirmware(true));
                    firmwareWarning.show(activity);
                }
                firmwareWarning.update(result,firmwareChecking);
            } catch(RuntimeException error) {
                Log.e(TAG,"Cannot display firmware warning",error);
                fail("Unsupported or unreadable glasses firmware. Install the latest stable release at xreal.com/ota before connecting.");
            }
        }
        void resumeAfterFirmware(boolean override) {
            if (!active() || firmwareChecking || isBusy()) return;
            if (override && !firmwarePaused) return;
            if (firmwareUsb==null || !firmwareUsb.equals(liveUsb())) { close(); return; }
            firmwareAccepted=true;firmwarePaused=false;
            debug(override?"firmware_override":"firmware_accepted",override?"Explicit override for this connection only":"Exact recommended firmware verified");
            blocked.remove(manager);
            deadline=SystemClock.uptimeMillis()+DEADLINE_MS;
            if (firmwareWarning!=null) {
                firmwareWarning=null;
                if (animation!=null) animation.show(activity);
            }
            stage("Preparing glasses");
            work.post(() -> { if (active()) startSdk(presenter); });
        }
        @Override public void run() {
            if (!active()) return;
            try {
                long now = SystemClock.uptimeMillis();
                if (firmwareChecking || firmwarePaused) {
                    if (firmwareUsb!=null && !firmwareUsb.equals(liveUsb())) { close(); return; }
                    if (firmwareChecking && !firmwarePaused && now>=firmwareDeadline)
                        pauseForFirmware(FirmwarePolicy.unavailable(firmwareType,
                                "The firmware check is taking too long. Initialization is stopped. Wait for the read to finish or exit."));
                    MAIN.postDelayed(this,250);return;
                }
                if (now >= deadline && (handoff == null || !handoff.isReady())) {
                    fail("Connection timed out while " + phase.toLowerCase() + ". Unplug and reconnect the glasses, then try again."); return;
                }
                if (!firmwareAccepted) { MAIN.postDelayed(this,250);return; }
                if (sdkReady) { MAIN.postDelayed(this, 250); return; }
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
            debug("sdk_result", "result=" + result);
            sdkPending = false; finishedWork();
            if (!active() || sdkReady) { Log.i(TAG, "Discarded a late SDK result=" + result); return; }
            if (result == 1) { fail("The glasses could not start AR Space. Reconnect them, then tap Try again."); return; }
            if (result == 0) {
                sdkReady = true;
                deadline = SystemClock.uptimeMillis() + DEADLINE_MS;
                stage("Starting AR Space");
                try {
                    handoff = new ConnectionHandoff(activity, animation);
                    handoff.start(() -> completeSuccess(result), () -> fail("AR Space could not finish starting. Reconnect the glasses, then tap Try again."));
                    // Start the real controller underneath the cover, before its success animation.
                    if (!(Boolean)call(manager, "isHasGoLauncher")) call(manager, "goLauncher");
                } catch (Exception error) { error("AR Space could not open. Tap Try again.", error); }
                return;
            }
            usbReconfiguration=result==2;
            finishResult(result);
        }
        void completeSuccess(int result) {
            if (!active() || handoffFinished) return;
            debug("ready", "Controller and glasses output ready");
            handoffFinished = true; success = true;
            MAIN.removeCallbacks(this);
            if (handoff != null) handoff.close(false);
            if (animation != null) animation.dispose();
            finishResult(result);
        }
        void finishResult(int result) {
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
            debug("failure", message);
            if (handoff != null) handoff.close(true);
            blocked.put(manager, true);
            MAIN.removeCallbacks(this);
            Log.w(TAG, message);
            try {
                Class<?> debug = Class.forName("dev.beampro.settings.DebugData");
                Method capture = debug.getDeclaredMethod("connectionFailure", Context.class, String.class);
                capture.setAccessible(true); capture.invoke(null, activity, "attempt=" + attempt + "; phase=" + phase + "; " + message);
            } catch (ClassNotFoundException previewWithoutDebug) { }
            catch (Exception error) { Log.w(TAG, "Cannot request connection diagnostics", error); }
            if (animation != null) animation.failure(message, isBusy());
            try {
                detachCallbacks();
                ((TextView) get(activity, "mTvTitle")).setText("Glasses connection paused");
                ((TextView) get(activity, "mTvSubTitle")).setText(message);
                ((View) get(activity, "mTvStatus")).setVisibility(View.GONE);
                ((View) get(activity, "mProgressBar")).setVisibility(View.GONE);
                Button button = (Button) get(activity, "mBtnConfirm");
                button.setText(isBusy() ? "Close" : "Try again");
                button.setVisibility(View.VISIBLE);
            } catch (Exception error) { Log.e(TAG, "Cannot show connection recovery", error); }
        }
        void close() {
            if (!success) debug("cancel", "Connection screen closed");
            if (!success) blocked.put(manager, true);
            dispose();
            activity.finish();
        }
        void dispose() { dispose(true); }
        void dispose(boolean stopThread) {
            if (disposed) return;
            debug("dispose", "Connection disposed; success=" + success);
            disposed = true;
            firmwareWarning=null;
            if (handoff != null) handoff.close(!success);
            if (animation != null) { animation.dispose(); animation = null; }
            MAIN.removeCallbacks(this);
            if (!success) blocked.put(manager, true);
            try {
                detachCallbacks();
                // Stop queued startup work and the stock presenter's otherwise unclosed thread.
                ((Handler) get(presenter, "mMainHandler")).removeCallbacksAndMessages(null);
                if (stopThread) {
                    if (!success && sdkInvoked) work.post(() -> {
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
