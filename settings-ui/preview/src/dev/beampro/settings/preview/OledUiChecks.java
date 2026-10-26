package dev.beampro.settings.preview;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import dev.beampro.settings.SettingsUi;
import com.xreal.glassesdisplayplugevent.GlassesInitSetting;

/** Verify the approved fixed OLED design, model changes and legacy setting behavior. */
public final class OledUiChecks extends Instrumentation {
    private static final int PAGE = 0x00bea001;
    int passed;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            getTargetContext().getSharedPreferences("beampro_settings_ui", 0).edit().clear().commit();
            SettingsPreviewActivity activity = (SettingsPreviewActivity) open(SettingsPreviewActivity.class);
            waitForIdleSync();
            runOnMainSync(() -> {
                View content = activity.findViewById(android.R.id.content);
                require(background(activity) == Color.BLACK, "OLED black is the default in every system theme");
                require(activity.getWindow().getStatusBarColor() == Color.BLACK, "black status bar");
                require(activity.getWindow().getNavigationBarColor() == Color.BLACK, "black navigation bar");
                require(!activity.getWindow().isNavigationBarContrastEnforced(), "no navigation scrim");
                int lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                require((activity.getWindow().getDecorView().getSystemUiVisibility() & lightFlags) == 0, "light system icons over black");
                require(SettingsUiChecks.findToggle(content, "OLED black") == null, "obsolete optional OLED switch is removed");
                require(SettingsUiChecks.findText(content, "OLED") != null, "appearance describes fixed OLED");
                require(!SettingsUiChecks.findText(content, "Connect your glasses").isEnabled(), "offline launch is disabled");
                require(SettingsUiChecks.findText(content, "Connect your glasses").getAlpha() == 1f, "disabled launch uses its disabled style, not transparency");
                require(SettingsUiChecks.findText(content, "THE UNBOUND LAUNCHER") != null, "settings use the shared app bar");
                int callbacks = activity.mSystemNotificationView.changes;
                getTargetContext().getSharedPreferences("beampro_settings_ui", 0).edit().putBoolean("oled_dark_theme", false).commit();
                SettingsUi.refresh(activity);
                require(background(activity) == Color.BLACK, "legacy optional preference cannot disable approved design");
                require(activity.mSystemNotificationView.changes == callbacks, "rendering has no preference write side effects");
                activity.setConnected(true);
                int[] types = {2,3,4,5,6,7,9,20};
                String[] names = {"XREAL Air","XREAL Air 2 Pro","XREAL Air 2","XREAL Air 2 Ultra","XREAL One Pro","XREAL One","XREAL 1S","XREAL 1 Pro+"};
                for (int i=0; i<types.length; i++) {
                    activity.setConnectedModel(types[i]);
                    require(SettingsUiChecks.findText(content, names[i]) != null, "model label " + types[i]);
                    ImageView image = findArtwork(content, names[i] + " glasses");
                    require(image != null && image.getDrawable() != null && image.getDrawable().getIntrinsicWidth() > 0, "widget picture loads for model " + types[i]);
                }
                activity.setConnectedModel(7);
                activity.setConnected(false);
                require(SettingsUiChecks.findText(content, "XREAL One") != null, "retain default device picture after disconnect");
                require(SettingsUiChecks.findText(content, "Connect your glasses") != null, "connection action restored offline");
            });
            runOnMainSync(activity::finish);
            Activity permissions = open(PermissionsPreviewActivity.class);
            waitForIdleSync();
            runOnMainSync(() -> {
                require(background(permissions) == Color.BLACK, "permission screen shares fixed black design");
                require(SettingsUiChecks.findToggle(permissions.findViewById(android.R.id.content), "Screen capture") != null, "existing permission control retained");
                require(SettingsUiChecks.findText(permissions.findViewById(android.R.id.content), "THE UNBOUND LAUNCHER") != null, "permissions use the shared app bar");
            });
            runOnMainSync(permissions::finish);
            result.putString("stream", "Signature OLED runtime checks passed: " + passed + "\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "OLED check failed after " + passed + ": " + error + "\n");
            finish(0, result);
        }
    }
    Activity open(Class<?> cls) { return startActivitySync(new Intent(getTargetContext(), cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    int background(Activity activity) { return ((ColorDrawable)activity.findViewById(PAGE).getBackground()).getColor(); }
    void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
    ImageView findArtwork(View root, String description) {
        if (root instanceof ImageView && description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return (ImageView)root;
        if (root instanceof ViewGroup) for (int i=0; i<((ViewGroup)root).getChildCount(); i++) {
            ImageView found = findArtwork(((ViewGroup)root).getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }
}
