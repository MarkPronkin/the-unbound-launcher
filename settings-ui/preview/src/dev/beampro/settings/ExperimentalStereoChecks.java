package dev.beampro.settings;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import dev.beampro.settings.preview.SettingsPreviewActivity;
import java.io.File;
import java.io.FileOutputStream;

/** Exercise the production preference and switch against Android's real files. */
public final class ExperimentalStereoChecks extends Instrumentation {
    int passed;
    SettingsPreviewActivity activity;
    static class Manager { Context mContext; Control mNRServiceControl = new Control(); }
    static class Control { boolean isEthernetSeries; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ExperimentalStereo.save(getTargetContext(), false);
            open();
            runOnMainSync(() -> {
                require(!toggle().isChecked(), "Off by default");
                require(toggle().getContentDescription().toString().contains("Reconnect"), "Reconnect guidance");
                toggle().performClick();
                require(toggle().isChecked() && ExperimentalStereo.enabled(activity), "Switch saves enabled state");
                activity.finish();
            });
            waitForIdleSync(); open();
            runOnMainSync(() -> {
                require(toggle().isChecked(), "Enabled state survives recreation");
                Manager manager = new Manager(); manager.mContext = activity;
                for (boolean ethernet : new boolean[]{false, true}) {
                    manager.mNRServiceControl.isEthernetSeries = ethernet;
                    for (int mode = -1; mode < 16; mode++) {
                        boolean mono = ethernet ? mode >= 1 && mode <= 4 : mode == 1 || mode == 5 || mode == 10 || mode == 11;
                        require(ExperimentalStereo.selectConfig(mode, manager) == (mono ? (ethernet ? 5 : 3) : mode),
                                "Only mono defaults change: " + ethernet + "/" + mode);
                    }
                }
                activity.setConnectedModel(7);
                require(toggle().isChecked(), "Connection refresh preserves the preference");
                toggle().performClick();
                require(!toggle().isChecked() && !ExperimentalStereo.enabled(activity), "Disable restores original behavior");
                require(activity.launches == 0 && activity.mSystemNotificationView.changes == 0, "Existing callbacks untouched");
                activity.finish();
            });
            waitForIdleSync(); open();
            runOnMainSync(() -> {
                require(!toggle().isChecked(), "Disabled state survives recreation");
                activity.finish();
            });
            waitForIdleSync();
            try (FileOutputStream flag = new FileOutputStream(new File(getTargetContext().getFilesDir(), "unbound-experimental-stereo"))) {
                flag.write(2); // A preference saved before the 2D 60 Hz option was removed.
            }
            open();
            runOnMainSync(() -> {
                require(!toggle().isChecked() && !ExperimentalStereo.enabled(activity), "Retired 2D override leaves stereo disabled");
                Manager manager = new Manager(); manager.mContext = activity;
                for (boolean ethernet : new boolean[]{false, true}) {
                    manager.mNRServiceControl.isEthernetSeries = ethernet;
                    for (int mode = -1; mode < 16; mode++) {
                        require(ExperimentalStereo.selectConfig(mode, manager) == mode,
                                "Retired 2D override restores defaults: " + ethernet + "/" + mode);
                    }
                }
                toggle().performClick();
                require(toggle().isChecked() && ExperimentalStereo.enabled(activity), "Stereo replaces the retired preference");
                activity.setConnectedModel(7);
                require(toggle().isChecked(), "Connection refresh preserves stereo after retiring 2D override");
                toggle().performClick();
                require(!ExperimentalStereo.enabled(activity), "Turning stereo off restores defaults");
                activity.finish();
            });
            result.putString("stream", "PASS experimental stereo Android/UI checks: " + passed + "\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL experimental stereo after " + passed + ": " + failure + "\n");
            finish(0, result);
        }
    }
    void open() {
        activity = (SettingsPreviewActivity) startActivitySync(new Intent(getTargetContext(), SettingsPreviewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        runOnMainSync(() -> {
            require(activity.findViewById(0x00bea001).findViewWithTag("force-2d-60") == null, "2D 60 Hz switch removed");
            require(activity.findViewById(0x00bea001).findViewWithTag("experimental-mono-60") == null, "2D 60 Hz row removed");
        });
    }
    SettingsUi.SignatureSwitch toggle() { return activity.findViewById(0x00bea001).findViewWithTag("force-3d-sbs"); }
    void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); passed++; }
}
