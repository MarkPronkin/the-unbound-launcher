package dev.beampro.adb;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import java.security.Security;

/** Runs in an isolated test APK, never in the shipping launcher. */
public final class AdbDeviceChecks extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            String launcherApk = args.getString("launcherApk");
            if (launcherApk != null) {
                android.content.pm.PackageInfo parsed = context.getPackageManager().getPackageArchiveInfo(
                        launcherApk, android.content.pm.PackageManager.GET_PROVIDERS);
                check(parsed != null && parsed.providers != null, "Android parses the actual launcher APK");
                android.content.pm.ProviderInfo bridge = null;
                for (android.content.pm.ProviderInfo provider : parsed.providers) {
                    if (provider.name.equals(LocalBridgeProvider.class.getName())) bridge = provider;
                }
                check(bridge != null && bridge.exported, "Actual launcher provider is exported to shell");
                check(bridge.processName.equals(parsed.packageName + ":unbound_bridge"), "Actual launcher provider has a separate process");
                result.putBoolean("actual_launcher_manifest_parsed", true);
            }
            AdbIdentity first = AdbIdentity.load(context), second = AdbIdentity.load(context);
            check(Arrays.equals(first.key.getEncoded(), second.key.getEncoded()), "Stable private installation identity");
            check(Arrays.equals(first.certificate.getEncoded(), second.certificate.getEncoded()), "Stable certificate");
            first.certificate.verify(first.certificate.getPublicKey());
            result.putBoolean("identity_persistence", true);
            java.lang.reflect.Method crypto = Class.forName("io.github.muntashirakon.adb.PairingCryptoChecks").getMethod("verify");
            crypto.invoke(null); result.putBoolean("spake2_and_authenticated_encryption", true);
            java.security.Provider provider = org.conscrypt.Conscrypt.newProvider();
            check(SSLContext.getInstance("TLSv1.3", provider) != null, "TLS 1.3 provider loads");
            result.putBoolean("native_tls_provider", true);
            check(android.os.Process.myUid() != 2000, "Test app is an ordinary Android UID");
            result.putInt("app_uid", android.os.Process.myUid());
            String code = args.getString("pairCode");
            String connect = args.getString("connectPort");
            if (code != null) {
                AdbBootstrap.pair(context, code, Integer.parseInt(args.getString("pairPort")), Integer.parseInt(connect), null, null);
            } else if (connect != null) {
                AdbBootstrap.connect(context, Integer.parseInt(connect), null);
            }
            if (code != null || connect != null) {
                long until = SystemClock.elapsedRealtime() + 60000;
                while (AdbBootstrap.busy() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(200);
                check(AdbBootstrap.ready(context), AdbBootstrap.status(context));
                result.putBoolean("real_shell_helper_ready", true);
                result.putBoolean("android_pairing_remembered", AdbBootstrap.paired(context));
            }
            result.putBoolean("passed", true); finish(-1, result);
        } catch (Throwable error) {
            android.util.Log.e("UnboundAdbChecks", "Device check failed", error);
            while (error.getCause() != null) error = error.getCause();
            result.putString("error", error.toString()); finish(0, result);
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
