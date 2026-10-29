package dev.beampro.settings;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Explicit opacity-only menu transitions, independent of the phone's default slide. */
public final class MenuTransitions {
    private static final int ENTER = android.R.anim.fade_in, EXIT = android.R.anim.fade_out;
    private static final Set<Activity> menus = Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private static boolean installed;
    private MenuTransitions() {}

    public static void install(Activity activity) {
        menus.add(activity);
        if (!installed) {
            installed = true;
            activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityCreated(Activity a, Bundle state) { if (menus.contains(a)) apply(a); }
                public void onActivityPaused(Activity a) {
                    if (menus.contains(a) && a.isFinishing() && Build.VERSION.SDK_INT < 34) fade(a);
                }
                public void onActivityDestroyed(Activity a) { menus.remove(a); }
                public void onActivityStarted(Activity a) {}
                public void onActivityResumed(Activity a) {}
                public void onActivityStopped(Activity a) {}
                public void onActivitySaveInstanceState(Activity a, Bundle state) {}
            });
        }
        apply(activity);
    }

    private static void apply(Activity activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, ENTER, EXIT);
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, ENTER, EXIT);
        } else fade(activity);
    }

    @SuppressWarnings("deprecation")
    private static void fade(Activity activity) { activity.overridePendingTransition(ENTER, EXIT); }

    public static void open(Activity activity, Intent intent) {
        install(activity);
        activity.startActivity(intent, ActivityOptions.makeCustomAnimation(activity, ENTER, EXIT).toBundle());
    }
}
