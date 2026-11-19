package dev.beampro.notificationchecks;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.lang.reflect.Array;
import java.util.List;

/** Exercise the installed launcher, including its own foreground pairing notification. */
public final class NotificationDeviceChecks extends Instrumentation {
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override public void onStart() {
        Context context = getTargetContext();
        String pkg = context.getPackageName();
        Intent service = new Intent().setClassName(pkg, "dev.beampro.adb.PairingOverlayService").setAction("notification");
        Activity screen = null;
        Bundle result = new Bundle();
        try {
            PackageManager pm = context.getPackageManager();
            require(pm.queryIntentServices(new Intent(NotificationListenerService.SERVICE_INTERFACE).setPackage(pkg), 0).isEmpty(),
                    "Android can still discover a notification listener");
            try {
                pm.getServiceInfo(new ComponentName(pkg, "com.xreal.evapro.NotifyService"), 0);
                throw new AssertionError("Reader service is registered");
            } catch (PackageManager.NameNotFoundException expected) {}

            ClassLoader loader = context.getClassLoader();
            Class<?> legacy = loader.loadClass("ai.nreal.android.nebulaspaceproxy.NebulaNotificationListenerService");
            require(!NotificationListenerService.class.isAssignableFrom(legacy), "Legacy adapter is a listener");
            require(Boolean.FALSE.equals(legacy.getMethod("isNotificationListenerEnabled", Context.class).invoke(null, context)),
                    "Legacy read access enabled");
            legacy.getMethod("openNotificationListenSettings", Context.class).invoke(null, (Object) null);
            legacy.getMethod("startService", Activity.class).invoke(null, (Object) null);
            Class<?> helper = loader.loadClass("com.xreal.evapro.NotifyHelper");
            Object adapter = helper.getMethod("getInstance").invoke(null);
            helper.getMethod("init", Context.class).invoke(adapter, (Object) null);
            require(Array.getLength(helper.getMethod("getActiveNotifications").invoke(adapter)) == 0,
                    "Renderer received notifications");

            Class<?> setup = loader.loadClass("dev.beampro.settings.PermissionSetupActivity");
            boolean postingOption = false;
            for (Object option : (List<?>) setup.getMethod("accesses", Context.class).invoke(null, context)) {
                String key = (String) option.getClass().getField("key").get(option);
                require(!"notification_access".equals(key), "Setup offers listener access");
                if ("notifications".equals(key)) postingOption = true;
            }
            require(postingOption, "Setup is missing the app notification option");
            screen = startActivitySync(new Intent().setClassName(pkg, setup.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            Activity active = screen;
            runOnMainSync(() -> active.startForegroundService(service));
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            long deadline = SystemClock.elapsedRealtime() + 12000;
            while (!pairingNotificationPosted(manager, pkg)) {
                require(SystemClock.elapsedRealtime() < deadline, "Pairing notification missing");
                SystemClock.sleep(100);
            }
            result.putString("stream", "PASS notification checks: zero listeners, empty renderer adapter, setup and pairing notification verified\n");
        } catch (Throwable failure) {
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(failure) + "\n");
        } finally {
            context.stopService(service);
            if (screen != null) {
                Activity active = screen;
                runOnMainSync(active::finish);
            }
        }
        finish(result.getString("stream").startsWith("PASS") ? -1 : 1, result);
    }

    private static boolean pairingNotificationPosted(NotificationManager manager, String pkg) {
        for (StatusBarNotification entry : manager.getActiveNotifications()) {
            if (entry.getId() == 7318 && pkg.equals(entry.getPackageName())
                    && "unbound-adb-pairing".equals(entry.getNotification().getChannelId())
                    && entry.getNotification().actions != null) return true;
        }
        return false;
    }

    private static void require(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }
}
