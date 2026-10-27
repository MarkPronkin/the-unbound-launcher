package dev.beampro.settings;

import android.app.Activity;
import android.content.Context;

/** Optional single-APK backend; ordinary development builds retain their existing flow. */
final class AdbEntry {
    private static Class<?> backend() {
        try { return Class.forName("dev.beampro.adb.AdbBootstrap"); }
        catch (ClassNotFoundException absent) { return null; }
    }
    static boolean available() { return backend() != null; }
    static boolean needsSetup(Context context) {
        Class<?> type = backend();
        if (type == null) return false;
        try { return Boolean.TRUE.equals(type.getMethod("needsSetup", Context.class).invoke(null, context)); }
        catch (Exception error) { throw new IllegalStateException("Cannot check on-device setup", error); }
    }
    static boolean setup(Activity activity) { return Boolean.TRUE.equals(invoke("setup", activity)); }
    static void open(Activity activity) { invoke("open", activity); }
    static void install(Activity activity) { invoke("install", activity); }
    static void completed(Activity activity) { invoke("completed", activity); }
    static boolean allowLaunch(Activity activity) {
        if (!available()) return true;
        return Boolean.TRUE.equals(invoke("allowLaunch", activity));
    }
    private static Object invoke(String method, Activity activity) {
        Class<?> type = backend();
        if (type == null) return null;
        try {
            java.lang.reflect.Method call=type.getMethod(method, Activity.class);
            Object result=call.invoke(null, activity);
            return call.getReturnType()==void.class ? Boolean.TRUE : result;
        }
        catch (Exception error) {
            android.util.Log.e("UnboundADB", "Cannot open on-device setup", error);
            android.widget.Toast.makeText(activity, "On-device setup could not open. Please restart the app.", 1).show();
            return null;
        }
    }
}
