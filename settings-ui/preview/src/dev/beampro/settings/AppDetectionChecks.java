package dev.beampro.settings;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import dev.beampro.settings.preview.SettingsPreviewActivity;

/** The production switch must change the real system category, including after recreation. */
public final class AppDetectionChecks extends Instrumentation {
    int passed;
    AppDetection detection;
    SettingsPreviewActivity activity;
    boolean noHelper, selfInstaller;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        noHelper = "true".equals(args.getString("no_helper"));
        selfInstaller = "true".equals(args.getString("self_installer"));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            activity = (SettingsPreviewActivity) startActivitySync(new Intent(getTargetContext(), SettingsPreviewActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> detection = AppDetection.get(activity));
            waitReady();
            if (!noHelper && !selfInstaller) waitForBridge();
            runOnMainSync(() -> {
                require(activity.findViewById(0x00bea001).findViewWithTag("experimental-app-detection") != null, "Experimental setting available without glasses");
                require(!toggle().isChecked(), "normal app initially");
                toggle().performClick();
                require(!toggle().isChecked() && !toggle().isEnabled(), "pending change keeps confirmed state and disables duplicate requests");
            });
            waitReady();
            if (noHelper) {
                runOnMainSync(() -> {
                    require(detection.error != null && detection.error.contains("On-device ADB"), "missing helper has actionable explanation");
                    require(!detection.game && !toggle().isChecked() && toggle().isEnabled(), "failed change stays normal and remains retryable");
                    activity.finish();
                });
                result.putString("stream", "PASS app detection failure UI checks: " + passed + "\n"); finish(-1, result); return;
            }
            runOnMainSync(() -> {
                require(detection.error == null && detection.game && toggle().isChecked(), "game category applied through real Android API");
                activity.finish();
            });
            waitForIdleSync();
            // Reopen settings after recreation and use its newly built switch.
            activity = (SettingsPreviewActivity) startActivitySync(new Intent(getTargetContext(), SettingsPreviewActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitReady();
            runOnMainSync(() -> {
                require(toggle().isChecked(), "game state survives settings recreation");
                toggle().performClick();
            });
            waitReady();
            runOnMainSync(() -> {
                require(detection.error == null && !detection.game && !toggle().isChecked(), "switch restores normal app classification");
                require(activity.launches == 0 && activity.mSystemNotificationView.changes == 0, "AR and existing settings callbacks untouched");
                activity.finish();
            });
            result.putString("stream", "PASS app detection UI checks: " + passed + "\n"); finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL app detection UI after " + passed + ": " + error + "\n"); finish(0, result);
        }
    }
    SettingsUi.SignatureSwitch toggle() { return activity.findViewById(0x00bea001).findViewWithTag("detect-as-game"); }
    void waitForBridge() {
        long deadline = SystemClock.uptimeMillis() + 12000;
        while (SystemClock.uptimeMillis() < deadline) {
            Bundle state = getTargetContext().getContentResolver().call(android.net.Uri.parse("content://dev.beampro.settings.preview.unbound.bridge"), "get", null, null);
            android.os.IBinder bridge = state.getBinder("binder");
            if (bridge != null && bridge.isBinderAlive()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("shell fixture unavailable");
    }
    void waitReady() {
        long deadline = SystemClock.uptimeMillis() + 12000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] ready = {false};
            runOnMainSync(() -> ready[0] = detection.known && !detection.busy);
            if (ready[0]) { waitForIdleSync(); return; }
            SystemClock.sleep(50);
        }
        throw new AssertionError("category request timed out");
    }
    void require(boolean value, String reason) { if (!value) throw new AssertionError(reason + " (" + detection.error + ")"); passed++; }
}
