package dev.beampro.port;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/** Keeps Beam-only flags in the certificate-protected companion's local state. */
public final class SettingsCompat {
    private static final Uri URI = Uri.parse("content://dev.beampro.portprobe.bridge");

    private static boolean put(ContentResolver resolver, String key, String value) {
        try {
            Bundle data = new Bundle();
            data.putString("value", value);
            Bundle result = resolver.call(URI, "flag-put", key, data);
            return result != null && result.getBoolean("saved");
        } catch (Exception error) { Log.w("PortSettings", "Cannot save local Beam flag " + key, error); return false; }
    }

    private static String get(ContentResolver resolver, String key) {
        try {
            Bundle result = resolver.call(URI, "flag-get", key, null);
            return result == null ? null : result.getString("value");
        } catch (Exception error) { Log.w("PortSettings", "Cannot read local Beam flag " + key, error); return null; }
    }

    public static boolean putGlobalInt(ContentResolver resolver, String key, int value) { return put(resolver, key, Integer.toString(value)); }
    public static int getGlobalInt(ContentResolver resolver, String key, int fallback) {
        try { return Integer.parseInt(get(resolver, key)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    public static boolean putSystemString(ContentResolver resolver, String key, String value) { return put(resolver, key, value); }
    public static String getSystemString(ContentResolver resolver, String key) { return get(resolver, key); }
}
