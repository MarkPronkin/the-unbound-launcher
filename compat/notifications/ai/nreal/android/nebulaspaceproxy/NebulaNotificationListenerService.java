package ai.nreal.android.nebulaspaceproxy;

import android.app.Activity;
import android.content.Context;

/** The legacy Unity interface remains callable in this build without a listener service. */
public final class NebulaNotificationListenerService {
    public static boolean isNotificationListenerEnabled(Context context) { return false; }
    public static void openNotificationListenSettings(Context context) {}
    public static void setCallback(NotificationCallback callback) {}
    public static void startService(Activity activity) {}
}
