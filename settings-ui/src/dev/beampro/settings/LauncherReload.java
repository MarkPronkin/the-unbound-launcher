package dev.beampro.settings;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.util.List;

/** Restart the app's own AR process through the existing glasses launch action. */
final class LauncherReload {
    private static final String PACKAGE = "com.xreal.evapro.nebula";
    static boolean canApply(Context context) {
        if (!PACKAGE.equals(context.getPackageName())) return false;
        try {
            Object manager = manager();
            Object connection = manager.getClass().getMethod("getIUsbNotifyType").invoke(manager);
            if (!"sdkSuccess".equals(String.valueOf(connection))) return false;
            List<ActivityManager.RunningAppProcessInfo> processes = ((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getRunningAppProcesses();
            if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes)
                if (process.uid == Process.myUid() && (PACKAGE + ":space").equals(process.processName)) return true;
        } catch (Exception error) { Log.w("AppMenuReload", "Cannot check AR process", error); }
        return false;
    }
    private static Object manager() throws Exception {
        return Class.forName("ai.nreal.nebula.manager.XREALManager").getMethod("getInstance").invoke(null);
    }
    static void confirm(Activity activity) {
        Signature.show(Signature.dialog(activity)
                .setTitle("Apply app menu to glasses?")
                .setMessage("AR Space will restart to load your saved order. Open spatial windows will close.")
                .setPositiveButton("Apply now", (dialog, which) -> restart(activity))
                .setNegativeButton("Later", null));
    }
    private static void restart(Activity activity) {
        try {
            Object manager = manager(); Method launch = manager.getClass().getMethod("goLauncher");
            List<ActivityManager.RunningAppProcessInfo> processes = ((ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE)).getRunningAppProcesses();
            if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes)
                if (process.uid == Process.myUid() && (PACKAGE + ":space").equals(process.processName) && process.pid != Process.myPid()) Process.killProcess(process.pid);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { launch.invoke(manager); }
                catch (Exception error) { failed(activity, error); }
            }, 700);
        } catch (Exception error) { failed(activity, error); }
    }
    private static void failed(Activity activity, Exception error) {
        Log.e("AppMenuReload", "Cannot restart AR Space", error);
        Toast.makeText(activity, "Menu saved. Close and reopen " + SettingsUi.APP_NAME + " to apply it.", Toast.LENGTH_LONG).show();
    }
}
