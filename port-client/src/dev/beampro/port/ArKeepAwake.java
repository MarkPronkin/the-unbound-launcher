package dev.beampro.port;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import java.util.WeakHashMap;

/** APK-owned screen-timeout prevention while the phone's AR controller is visible. */
public final class ArKeepAwake implements Application.ActivityLifecycleCallbacks, DisplayManager.DisplayListener {
    private static boolean installed;
    private final DisplayManager displays;
    private final WeakHashMap<Activity, Previous> windows = new WeakHashMap<>();

    private static final class Previous {
        final boolean flag, view;
        Previous(Window window) {
            flag = (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
            view = window.getDecorView().getKeepScreenOn();
        }
        void restore(Window window) {
            window.getDecorView().setKeepScreenOn(view);
            if (!flag) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private ArKeepAwake(Application application) {
        displays = application.getSystemService(DisplayManager.class);
        application.registerActivityLifecycleCallbacks(this);
        displays.registerDisplayListener(this, new Handler(Looper.getMainLooper()));
    }

    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        new ArKeepAwake((Application) context.getApplicationContext());
    }

    private boolean controller(Activity activity) {
        String name = activity.getClass().getName();
        return (name.equals("ai.nreal.nebula.space.LaunchSpaceAcrivity")
                || name.equals("ai.nreal.activitylife.NRXRActivity"))
                && activity.getDisplay() != null && activity.getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY;
    }

    private boolean connected() {
        for (Display display : displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY && display.getState() == Display.STATE_ON) return true;
        }
        return false;
    }

    private void apply(Activity activity) {
        if (!windows.containsKey(activity)) return;
        Window window = activity.getWindow();
        if (connected()) {
            // Unity's view traversal can clear a window-only request. The root
            // view participates in every traversal and keeps the request active.
            window.getDecorView().setKeepScreenOn(true);
            if ((window.getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                android.util.Log.i("PortArKeepAwake", "Automatic screen-timeout protection active");
            }
        } else windows.get(activity).restore(window);
    }

    private void refresh() {
        for (Activity activity : windows.keySet()) apply(activity);
    }

    private void release(Activity activity) {
        Previous previous = windows.remove(activity);
        if (previous != null) previous.restore(activity.getWindow());
    }

    private void track(Activity activity) {
        if (!controller(activity)) return;
        if (!windows.containsKey(activity)) windows.put(activity, new Previous(activity.getWindow()));
        apply(activity);
    }
    @Override public void onActivityStarted(Activity activity) { track(activity); }
    // A visible secondary-display app can pause the phone controller. Retain the flag
    // until onStop, and reassert the request after the complete resume implementation.
    @Override public void onActivityResumed(Activity activity) { track(activity); }
    @Override public void onActivityPostResumed(Activity activity) { track(activity); }
    @Override public void onActivityStopped(Activity activity) { release(activity); }
    @Override public void onActivityDestroyed(Activity activity) { release(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle saved) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle saved) {}
    @Override public void onDisplayAdded(int id) { refresh(); }
    @Override public void onDisplayRemoved(int id) { refresh(); }
    @Override public void onDisplayChanged(int id) { refresh(); }
}
