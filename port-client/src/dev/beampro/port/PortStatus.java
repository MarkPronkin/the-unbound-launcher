package dev.beampro.port;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Records whether the installed app's replacement LaunchService resolves the helper. */
public final class PortStatus {
    public static final String PROBE_ACTION = "dev.beampro.port.PROBE_BIND";

    public static void install(Context context) {
        UsbPermissionCompat.install(context);
        GlassesConnectionCompat.install(context);
        DisplayStartupCompat.install(context);
        ArKeepAwake.install(context);
        PhoneScreenCompat.install(context);
        QuickAccessCompat.install(context);
        RecentAppsCompat.install(context);
        WirelessCastCompat.install(context);
        Context app = context.getApplicationContext();
        if (!android.app.Application.getProcessName().equals(app.getPackageName())) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            final boolean[] finished = {false};
            ServiceConnection connection = new ServiceConnection() {
                private void record(IBinder binder, String error) {
                    if (finished[0]) return;
                    finished[0] = true;
                    try {
                        JSONObject result = new JSONObject();
                        result.put("client_uid", android.os.Process.myUid());
                        result.put("service", "ai.nreal.nebula.service.LaunchService");
                        result.put("connected", binder != null && binder.isBinderAlive());
                        if (binder != null) result.put("descriptor", binder.getInterfaceDescriptor());
                        if (error != null) result.put("error", error);
                        try (FileOutputStream stream = new FileOutputStream(new File(app.getFilesDir(), "port-bridge-status.json"))) {
                            stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (Exception failure) { android.util.Log.e("PortStatus", "Cannot save bridge status", failure); }
                    try { app.unbindService(this); } catch (Exception ignored) {}
                }
                @Override public void onServiceConnected(ComponentName name, IBinder binder) { record(binder, null); }
                @Override public void onNullBinding(ComponentName name) { record(null, "Launch helper unavailable"); }
                @Override public void onServiceDisconnected(ComponentName name) {}
            };
            Intent intent = new Intent(PROBE_ACTION).setComponent(new ComponentName(app, "ai.nreal.nebula.service.LaunchService"));
            try {
                if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                    android.util.Log.e("PortStatus", "LaunchService binding was rejected");
                }
            } catch (Exception failure) { android.util.Log.e("PortStatus", "Startup bridge check failed", failure); }
        }, 500);
    }
}
