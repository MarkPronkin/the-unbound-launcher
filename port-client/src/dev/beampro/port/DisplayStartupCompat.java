package dev.beampro.port;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Session-only recovery for an XREAL stereo display that connects at the wrong size. */
public final class DisplayStartupCompat implements Application.ActivityLifecycleCallbacks,
        DisplayManager.DisplayListener {
    private static final String TAG = "UnboundDisplay";
    private static final String FAKE = "ai.nreal.activitylife.NRFakeActivity";
    private static DisplayStartupCompat instance;
    private static Waiter pending;
    private final DisplayManager displays;
    private final WeakHashMap<Activity, Request> windows = new WeakHashMap<>();

    private static final class Request {
        boolean visible = true;
        int previous, owned;
    }

    private DisplayStartupCompat(Application app) {
        displays = app.getSystemService(DisplayManager.class);
        app.registerActivityLifecycleCallbacks(this);
        displays.registerDisplayListener(this, new Handler(Looper.getMainLooper()));
    }

    public static synchronized void install(Context context) {
        if (instance == null) instance = new DisplayStartupCompat((Application) context.getApplicationContext());
    }

    private static Field field(Object app, String name) throws Exception {
        Field f = app.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    static boolean xreal(Display display) {
        if (display == null || !display.isValid() || display.getDisplayId() == 0) return false;
        try {
            // Retain the SDK's EDID identity check; do not select an arbitrary external screen.
            Method check = Class.forName("ai.nreal.activitylife.NRXRApp")
                    .getMethod("isNrealDisplay", Display.class, boolean.class);
            return (Boolean) check.invoke(null, display, false);
        } catch (Exception failure) {
            Log.w(TAG, "Cannot identify XREAL display", failure);
            return false;
        }
    }

    static int stereoMode(Display display) {
        int selected = 0;
        float best = Float.MAX_VALUE;
        for (Display.Mode mode : display.getSupportedModes()) {
            float distance = Math.abs(mode.getRefreshRate() - 60f);
            if (mode.getPhysicalWidth() == 3840 && mode.getPhysicalHeight() == 1080
                    && distance <= 0.5f && distance < best) {
                selected = mode.getModeId();
                best = distance;
            }
        }
        return selected;
    }

    static boolean stereoEdid(Display display) {
        if(stereoMode(display)==0)return false;
        for(Display.Mode mode:display.getSupportedModes())
            if(mode.getPhysicalWidth()==1920&&mode.getPhysicalHeight()==1080)return false;
        return true;
    }

    private void apply(Activity activity, Request request) {
        if (!request.visible || activity.isDestroyed() || Build.VERSION.SDK_INT < 30) return;
        Display display = activity.getDisplay();
        if (!xreal(display)) { release(activity, request); return; }
        Display.Mode current = display.getMode();
        WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
        if (request.owned == 0) {
            // Leave a working SDK mode or an existing explicit window preference alone.
            if (attrs.preferredDisplayModeId != 0
                    || (current.getPhysicalWidth() == 3840 && current.getPhysicalHeight() == 1080)
                    || !stereoEdid(display)) return;
        } else if (attrs.preferredDisplayModeId != request.owned) {
            // A later SDK request owns this window now.
            request.owned = 0;
            return;
        }
        int mode = stereoMode(display);
        if (mode == 0) { release(activity, request); return; }
        if (request.owned == 0) request.previous = attrs.preferredDisplayModeId;
        request.owned = mode;
        if (attrs.preferredDisplayModeId == mode) return;
        attrs.preferredDisplayModeId = mode;
        activity.getWindow().setAttributes(attrs);
        Log.i(TAG, "AR window requests 3840x1080@60 mode=" + mode
                + " on display=" + display.getDisplayId() + "; active="
                + current.getPhysicalWidth() + "x" + current.getPhysicalHeight());
    }

    private void release(Activity activity, Request request) {
        if (request.owned == 0) return;
        WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
        if (attrs.preferredDisplayModeId == request.owned) {
            attrs.preferredDisplayModeId = request.previous;
            activity.getWindow().setAttributes(attrs);
        }
        request.owned = 0;
    }

    private void refresh() {
        for (Activity activity : new ArrayList<>(windows.keySet())) {
            Request request = windows.get(activity);
            if (request != null) apply(activity, request);
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle saved) {
        if (activity.getClass().getName().equals(FAKE)) {
            Request request = new Request();
            windows.put(activity, request);
            apply(activity, request);
        }
    }
    @Override public void onActivityStarted(Activity activity) {
        Request request = windows.get(activity);
        if (request != null) { request.visible = true; apply(activity, request); }
    }
    @Override public void onActivityResumed(Activity activity) { onActivityStarted(activity); }
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {
        Request request = windows.get(activity);
        if (request != null) { request.visible = false; release(activity, request); }
    }
    @Override public void onActivityDestroyed(Activity activity) {
        Request request = windows.remove(activity);
        if (request != null) release(activity, request);
        if (pending != null && pending.unity == activity) pending.close();
    }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle saved) {}
    @Override public void onDisplayAdded(int id) { refresh(); }
    @Override public void onDisplayChanged(int id) { refresh(); }
    @Override public void onDisplayRemoved(int id) { refresh(); }

    /** Hook only the SDK's external renderer launch; retain display ID and permission checks. */
    public static void startActivity(Activity activity, Intent intent, Bundle options) {
        Bundle actual = options;
        if (intent.getComponent() != null && FAKE.equals(intent.getComponent().getClassName())
                && activity.getPackageName().equals(intent.getComponent().getPackageName()) && options != null) {
            DisplayManager manager = activity.getSystemService(DisplayManager.class);
            Display display = manager.getDisplay(options.getInt("android.activity.launchDisplayId", -1));
            if (xreal(display)) {
                actual = new Bundle(options);
                // ActivityOptions' framework key: a fullscreen request for this task only.
                actual.putInt("android.activity.windowingMode", 1);
            }
        }
        activity.startActivity(intent, actual);
    }

    /** Route both existing and newly connected displays through the same readiness check. */
    public static void startRenderer(Object xrApp) {
        if(PresentationOnlyCompat.owns(xrApp))return;
        try {
            Activity context = (Activity) field(xrApp, "mContext").get(xrApp);
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(context.getPackageName(), FAKE));
            intent.addFlags(276824064); // Preserve NEW_TASK | EXCLUDE_FROM_RECENTS.
            field(xrApp, "mIsActivityStartedOnDPAdded").setBoolean(xrApp, false);
            awaitDisplay(xrApp, intent);
        } catch (Exception failure) {
            Log.e(TAG, "Cannot prepare external renderer startup", failure);
        }
    }

    /** Replace the five-second, added-event-only waiter; never enable a display or bypass consent. */
    public static void awaitDisplay(Object xrApp, Intent intent) {
        if (pending != null) pending.close();
        try {
            pending = new Waiter(xrApp, intent);
            pending.start();
        } catch (Exception failure) {
            Log.e(TAG, "Cannot await the external renderer display", failure);
            if (pending != null) pending.close();
        }
    }

    private static final class Waiter implements Runnable, DisplayManager.DisplayListener {
        private final Object app;
        private final Intent intent;
        private final Activity context, unity;
        private final DisplayManager displays;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final long deadline = SystemClock.uptimeMillis() + 45000;
        private boolean closed;
        private boolean waitingForStereo;

        Waiter(Object app, Intent intent) throws Exception {
            this.app = app;
            this.intent = new Intent(intent);
            context = (Activity) field(app, "mContext").get(app);
            unity = (Activity) field(app, "mUnityActivity").get(app);
            displays = context.getSystemService(DisplayManager.class);
        }
        void start() {
            StereoPresentationCompat.track(unity);
            displays.registerDisplayListener(this, handler);
            Log.i(TAG, "Waiting up to 45s for XREAL display and Android display consent");
            run(); // Re-query after registration, including displays added during startup.
        }
        void close() {
            if (closed) return;
            closed = true;
            handler.removeCallbacks(this);
            displays.unregisterDisplayListener(this);
            if (pending == this) pending = null;
        }
        @Override public void run() {
            if (closed) return;
            handler.removeCallbacks(this);
            try {
                // The SDK intentionally finishes its entry Activity after starting Unity.
                // Only the owning Unity Activity determines whether this request is alive.
                if (unity == null || unity.isFinishing() || unity.isDestroyed()
                        || field(app, "mIsQuiting").getBoolean(app)) { close(); return; }
                if (field(app, "mIsActivityStartedOnDPAdded").getBoolean(app)) { close(); return; }
                ActivityManager manager = context.getSystemService(ActivityManager.class);
                for (Display display : displays.getDisplays()) {
                    if (!xreal(display) || display.getState() != Display.STATE_ON
                            || !manager.isActivityStartAllowedOnDisplay(context, display.getDisplayId(), intent)) continue;
                    if (Build.VERSION.SDK_INT>=37 && stereoEdid(display)
                            && (display.getMode().getPhysicalWidth()!=3840 || display.getMode().getPhysicalHeight()!=1080)) {
                        if(!waitingForStereo)Log.i(TAG,"Waiting for embedded ADB helper to select native stereo output");
                        waitingForStereo=true;
                        continue;
                    }
                    Method launch = app.getClass().getDeclaredMethod("startActivityOnDisplay", Display.class, Intent.class);
                    launch.setAccessible(true);
                    if ((Boolean) launch.invoke(app, display, intent)) {
                        field(app, "mIsActivityStartedOnDPAdded").setBoolean(app, true);
                        Log.i(TAG, "External renderer launched on display=" + display.getDisplayId());
                        close();
                        return;
                    }
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    Log.w(TAG, "XREAL display startup timed out; Android consent/display access may still be pending");
                    close();
                    Method finish = app.getClass().getDeclaredMethod("tryFinishUnityActivity");
                    finish.setAccessible(true);
                    finish.invoke(app);
                    return;
                }
            } catch (Exception failure) {
                Log.e(TAG, "External display startup failed", failure);
                close();
                return;
            }
            handler.postDelayed(this, 250);
        }
        @Override public void onDisplayAdded(int id) { run(); }
        @Override public void onDisplayChanged(int id) { run(); }
        @Override public void onDisplayRemoved(int id) { run(); }
    }
}
