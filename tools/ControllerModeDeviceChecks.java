package dev.beampro.controllermodechecks;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import dev.beampro.port.SettingsCompat;
import java.util.Objects;

/** Execute the APK's real controller service without secure-settings permission or a shell helper. */
public final class ControllerModeDeviceChecks extends Instrumentation {
    private static final String FLAG = "xreal_hide_pointicon";
    private static final String SERVICE = "com.xreal.evapro.JobService";
    private int passed;
    private Activity activity;
    public static final class HostActivity extends Activity {}

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    private void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        passed++;
    }

    private int servicePid(Context context) {
        for (ActivityManager.RunningServiceInfo service : context.getSystemService(ActivityManager.class).getRunningServices(100))
            if (service.service.getClassName().equals(SERVICE)) return service.pid;
        return -1;
    }

    @Override public void onStart() {
        Context context = getTargetContext();
        Bundle result = new Bundle();
        Intent service = new Intent().setClassName(context, SERVICE);
        try {
            activity = startActivitySync(new Intent(context, HostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            check(context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_DENIED,
                    "Fixture has no protected settings permission");
            String global = Settings.Global.getString(context.getContentResolver(), FLAG);
            try {
                Settings.Global.putInt(context.getContentResolver(), FLAG, 0);
                throw new AssertionError("Android accepted the stock protected write");
            } catch (SecurityException expected) { passed++; }
            Uri provider = Uri.parse("content://" + context.getPackageName() + ".flags");
            int pid = -1;
            for (int cycle = 0; cycle < 12; cycle++) for (boolean hide : new boolean[]{false, true, true}) {
                // Clear the previous acknowledgement so both trackpads' identical values must be processed anew.
                context.getContentResolver().call(provider, "flag-put", FLAG, new Bundle());
                ComponentName started = context.startService(new Intent(service)
                        .setAction("evapro.job.ACTION_MOUSE_CTRL").putExtra("hide_pointer", hide));
                check(started != null && started.getClassName().equals(SERVICE), "Original controller service starts");
                long deadline = SystemClock.uptimeMillis() + 5000;
                int expected = hide ? 1 : 0;
                while (SettingsCompat.getGlobalInt(context.getContentResolver(), FLAG, -1) != expected
                        && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20);
                check(SettingsCompat.getGlobalInt(context.getContentResolver(), FLAG, -1) == expected,
                        "Pointer request acknowledged in private state: cycle " + cycle + ", hide " + hide);
                int current = servicePid(context);
                check(current > 0 && (pid < 0 || pid == current), "Service process survives every transition");
                pid = current;
                check(Objects.equals(global, Settings.Global.getString(context.getContentResolver(), FLAG)),
                        "Controller request leaves global settings unchanged");
            }
            context.startService(new Intent(service).setAction("unbound.test.UNRELATED"));
            SystemClock.sleep(100);
            check(servicePid(context) == pid && SettingsCompat.getGlobalInt(context.getContentResolver(), FLAG, -1) == 1,
                    "Unrelated service action preserves pointer state and process");
            check(SettingsCompat.putGlobalInt(context.getContentResolver(), "xreal.flag.not_focusable", 1)
                    && SettingsCompat.getGlobalInt(context.getContentResolver(), "xreal.flag.not_focusable", -1) == 1,
                    "Existing Beam flags still use the same private provider");
            try {
                context.getContentResolver().call(provider, "flag-put", "screen_brightness", new Bundle());
                throw new AssertionError("Provider accepted a non-Beam setting");
            } catch (IllegalArgumentException expected) { passed++; }
            result.putBoolean("passed", true);
            result.putInt("checks", passed);
            result.putInt("transitions", 36);
            result.putString("stream", "PASS controller mode service checks: " + passed + "; transitions: 36\n");
        } catch (Throwable error) {
            result.putString("stream", "FAIL controller mode service after " + passed + ": "
                    + android.util.Log.getStackTraceString(error));
        } finally {
            context.stopService(service);
            if (activity != null) runOnMainSync(activity::finish);
        }
        finish(result.getBoolean("passed") ? -1 : 0, result);
    }
}
