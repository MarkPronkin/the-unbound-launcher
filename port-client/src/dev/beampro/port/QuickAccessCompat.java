package dev.beampro.port;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.provider.Settings;
import android.util.Log;

/** Radio metadata and existing-window navigation through the signed shell helper. */
public final class QuickAccessCompat {
    private static final String TOKEN = "dev.beampro.bridge.RADIO";
    private static Context application;

    public static void install(Context context) { application = context.getApplicationContext(); }

    private static IBinder bridge(Context context) {
        Bundle result = context.getContentResolver().call(
                Uri.parse("content://dev.beampro.portprobe.bridge"), "get", null, null);
        return result == null ? null : result.getBinder("binder");
    }

    public static String getSSID(WifiInfo info) {
        String original = info == null ? null : info.getSSID();
        if (original != null && !original.equals("<unknown ssid>") && !original.equals("\"<unknown ssid>\"")) return original;
        if (application == null) return original;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            IBinder remote = bridge(application);
            if (remote == null || !remote.isBinderAlive()) return original;
            data.writeInterfaceToken(TOKEN);
            if (!remote.transact(0x00FFFFFC, data, reply, 0)) return original;
            reply.readException();
            String ssid = reply.readString();
            return ssid == null || ssid.isEmpty() || ssid.equals("<unknown ssid>") ? original : ssid;
        } catch (Exception error) {
            Log.w("PortQuickAccess", "Cannot read connected Wi-Fi name", error);
            return original;
        } finally { data.recycle(); reply.recycle(); }
    }

    public static String getPackageNameByIntent(Intent intent, Context context) {
        try {
            ComponentName component = intent.resolveActivity(context.getPackageManager());
            if (component == null) return "";
            int radio = Settings.ACTION_WIFI_SETTINGS.equals(intent.getAction()) ? 1
                    : Settings.ACTION_BLUETOOTH_SETTINGS.equals(intent.getAction()) ? 2 : 0;
            if (radio != 0) {
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    IBinder remote = bridge(context);
                    if (remote != null && remote.isBinderAlive()) {
                        data.writeInterfaceToken(TOKEN);
                        data.writeInt(radio);
                        if (remote.transact(0x00FFFFFD, data, reply, 0)) reply.readException();
                    }
                } catch (Exception error) {
                    Log.w("PortQuickAccess", "Cannot navigate existing radio settings window", error);
                } finally { data.recycle(); reply.recycle(); }
            }
            return component.getPackageName();
        } catch (Exception error) {
            Log.w("PortQuickAccess", "Cannot resolve settings Intent", error);
            return null;
        }
    }
}
