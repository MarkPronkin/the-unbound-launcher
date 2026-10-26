package dev.beampro.port;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.lang.reflect.Field;

/** Keep optional vendor Miracast unavailable when its protected provider cannot be used. */
public final class WirelessCastCompat {
    private static final String TAG = "PortWirelessCast";
    private static final ComponentName WFD_SERVICE = new ComponentName(
            "com.qualcomm.wfd.service", "com.qualcomm.wfd.service.WfdService");
    private static volatile Context app;
    private static volatile String unavailableReason = "Application context is not ready";
    private static volatile String loggedReason;

    public static void install(Context context) {
        app = context.getApplicationContext();
        isMiracastEnabled();
    }

    public static String getUnavailableReason() { return unavailableReason; }

    public static boolean isMiracastEnabled() {
        Context context = app;
        if (context == null) return unavailable("Application context is not ready");
        try {
            ServiceInfo service = context.getPackageManager().getServiceInfo(WFD_SERVICE, 0);
            if (!service.enabled || !service.applicationInfo.enabled) {
                return unavailable("Wi-Fi Display provider is disabled");
            }
            if (!service.exported && !context.getPackageName().equals(service.packageName)) {
                return unavailable("Wi-Fi Display provider is not exported");
            }
            String permission = service.permission;
            if (permission != null && !permission.isEmpty()
                    && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return unavailable("Wi-Fi Display provider requires " + permission);
            }
            unavailableReason = null;
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            return unavailable("Wi-Fi Display provider is not installed");
        } catch (RuntimeException failure) {
            Log.w(TAG, "Cannot inspect Wi-Fi Display provider", failure);
            return unavailable("Wi-Fi Display provider could not be checked");
        }
    }

    /** A failed vendor initialization must not schedule a null manager on the cast worker. */
    public static boolean isMiracastReady(Object castUtil) {
        if (!isMiracastEnabled()) return false;
        if (castUtil == null) return unavailable("Miracast has not been initialized");
        try {
            Field manager = castUtil.getClass().getDeclaredField("mWfdManager");
            manager.setAccessible(true);
            if (manager.get(castUtil) != null) return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Log.w(TAG, "Cannot inspect Miracast initialization", failure);
        }
        return unavailable("Miracast initialization did not complete");
    }

    /** The cast switch also starts AirPlay, so report only the unavailable Miracast part. */
    public static void onCastServiceStart(Object castUtil) {
        if (isMiracastReady(castUtil)) return;
        Context context = app;
        if (context != null) {
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context,
                    "Miracast is unavailable on this phone.", Toast.LENGTH_LONG).show());
        }
    }

    /** Discovery broadcasts may outlive an unsuccessful vendor manager initialization. */
    public static void startSearch(Object manager) {
        if (!isMiracastEnabled() || manager == null) return;
        try {
            manager.getClass().getMethod("startSearch").invoke(manager);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Log.w(TAG, "Cannot restart Miracast discovery", failure);
        }
    }

    private static boolean unavailable(String reason) {
        unavailableReason = reason;
        if (!reason.equals(loggedReason)) {
            loggedReason = reason;
            Log.w(TAG, "Miracast unavailable: " + reason);
        }
        return false;
    }

    private WirelessCastCompat() {}
}
