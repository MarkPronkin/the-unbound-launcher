package dev.beampro.settings;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** The bridge provider owns the preference and the USB attachment selected by a manual launch. */
public final class AutoConnect {
    public static final String GET = "auto-connect-get";
    private static final String SET = "auto-connect-set", MANUAL = "auto-connect-manual";
    private static final String CONTINUE = "auto-connect-continue";
    private static UsbDevice manualDevice;
    private static String lastManualName;

    private AutoConnect() {}

    private static Bundle call(Context context, String method, String arg) {
        return call(context, method, arg, null);
    }

    private static Bundle call(Context context, String method, String arg, Bundle extras) {
        Uri uri = Uri.parse("content://" + context.getPackageName() + ".unbound.bridge");
        // The standalone settings build has no embedded bridge. Its settings run in one process.
        if (!"dev.markpronkin.unbound".equals(context.getPackageName())
                && context.getPackageManager().resolveContentProvider(uri.getAuthority(), 0) == null)
            return handle(context, method, arg, extras);
        Bundle result = context.getContentResolver().call(uri, method, arg, extras);
        if (result == null || !result.containsKey("automatic"))
            throw new IllegalStateException("Connection preference unavailable");
        return result;
    }

    public static boolean automatic(Context context) { return call(context, GET, null).getBoolean("automatic"); }
    static void disable(Context context, boolean disabled) { call(context, SET, Boolean.toString(disabled)); }

    static boolean prepareManual(Context context) {
        return automatic(context) || call(context, MANUAL, null).getBoolean("manage");
    }

    /** Called only by a live startup session after the SDK explicitly requests USB reconfiguration. */
    public static boolean continueManual(Context context, String previous, String replacement) {
        Bundle extras = new Bundle();
        extras.putString("device", replacement);
        return replacement.equals(call(context, CONTINUE, previous, extras).getString("device"));
    }

    public static synchronized Bundle handle(Context context, String method, String arg, Bundle extras) {
        int caller = Binder.getCallingUid();
        if (caller != context.getApplicationInfo().uid && !(caller == 2000 && GET.equals(method)))
            throw new SecurityException("Only Unbound can change the connection preference");
        long identity = Binder.clearCallingIdentity();
        try {
            SharedPreferences prefs = context.getSharedPreferences("unbound-auto-connect", 0);
            if (!prefs.getBoolean("migrated", false)) migrate(context, prefs);
            if (SET.equals(method)) {
                if (!"true".equals(arg) && !"false".equals(arg)) throw new IllegalArgumentException("Expected a switch value");
                if (!prefs.edit().putBoolean("disabled", Boolean.parseBoolean(arg)).commit())
                    throw new IllegalStateException("Cannot save connection preference");
                manualDevice = null;
                lastManualName = null;
            } else if (MANUAL.equals(method)) {
                manualDevice = null;
                try {
                    java.lang.reflect.Method identify = Class.forName("com.xreal.glassesdisplayplugevent.helper.GlassesTypeUtil")
                            .getMethod("checkGlassesType", UsbDevice.class);
                    for (UsbDevice device : context.getSystemService(UsbManager.class).getDeviceList().values()) {
                        if ((Integer) identify.invoke(null, device) <= 1) continue;
                        if (manualDevice != null) { manualDevice = null; break; }
                        manualDevice = device;
                    }
                } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                lastManualName = manualDevice == null ? null : manualDevice.getDeviceName();
            } else if (CONTINUE.equals(method)) {
                if (arg != null && arg.equals(lastManualName) && extras != null) {
                    UsbDevice replacement = context.getSystemService(UsbManager.class).getDeviceList().get(extras.getString("device"));
                    if (replacement != null) {
                        manualDevice = replacement;
                        lastManualName = replacement.getDeviceName();
                    }
                }
            } else if (!GET.equals(method)) throw new IllegalArgumentException("Unknown connection operation");

            if (manualDevice != null) {
                UsbDevice live = context.getSystemService(UsbManager.class).getDeviceList().get(manualDevice.getDeviceName());
                if (live == null || live.getDeviceId() != manualDevice.getDeviceId()
                        || live.getVendorId() != manualDevice.getVendorId() || live.getProductId() != manualDevice.getProductId())
                    manualDevice = null;
            }
            boolean automatic = !prefs.getBoolean("disabled", false);
            Bundle result = new Bundle();
            result.putBoolean("automatic", automatic);
            result.putBoolean("manage", automatic || manualDevice != null);
            if (manualDevice != null) result.putString("device", manualDevice.getDeviceName());
            return result;
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private static void migrate(Context context, SharedPreferences prefs) {
        // Preserve an existing opt-out before clearing the SDK flag that hides Enter AR Space.
        if ("dev.markpronkin.unbound".equals(context.getPackageName())) {
            Uri settings = Uri.parse("content://" + context.getPackageName() + "/settings/");
            try (Cursor rows = context.getContentResolver().query(settings, null, null, null, null)) {
                if (rows == null) throw new IllegalStateException("Cannot read existing glasses settings");
                while (rows.moveToNext()) {
                    if (!"castScreen".equals(rows.getString(rows.getColumnIndexOrThrow("name")))) continue;
                    if (Boolean.parseBoolean(rows.getString(rows.getColumnIndexOrThrow("value")))) {
                        if (!prefs.edit().putBoolean("disabled", true).commit())
                            throw new IllegalStateException("Cannot preserve connection preference");
                        ContentValues values = new ContentValues();
                        values.put("value", "false");
                        Uri row = android.content.ContentUris.withAppendedId(settings, rows.getLong(rows.getColumnIndexOrThrow("_id")));
                        if (context.getContentResolver().update(row, values, null, null) != 1)
                            throw new IllegalStateException("Cannot reset the old mirroring preference");
                    }
                    break;
                }
            }
        }
        if (!prefs.edit().putBoolean("migrated", true).commit()) throw new IllegalStateException("Cannot finish migration");
    }
}
