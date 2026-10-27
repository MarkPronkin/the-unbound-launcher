package dev.beampro.settings.preview;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import dev.beampro.settings.SettingsUi;

/** Runtime checks exercise delegation and backend-to-UI updates, not just rendering. */
public final class SettingsUiChecks extends Instrumentation {
    int passed;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent intent = new Intent(getTargetContext(), SettingsPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            SettingsPreviewActivity activity = (SettingsPreviewActivity) startActivitySync(intent);
            waitForIdleSync();
            runOnMainSync(() -> {
                ViewGroup content = activity.findViewById(android.R.id.content);
                require(findText(content, "Connect your glasses") != null, "offline connection card");
                require(findText(content, "Appearance") != null, "appearance available offline");
                CompoundButton toggle = findToggle(content, "System notifications");
                require(toggle != null, "accessible native switch");
                require(activity.mSystemNotificationView.changes == 0, "initial checked state has no write side effects");
                boolean before = activity.mSystemNotificationView.isChecked();
                toggle.performClick();
                require(activity.mSystemNotificationView.isChecked() != before, "toggle delegates to original switch");
                require(activity.mSystemNotificationView.changes == 1, "exactly one original callback");
                boolean saved = activity.getPreferences(0).getBoolean("notifications", before);
                require(saved != before, "original persistence retained");
                activity.mSystemNotificationView.mIvSwitch.setChecked(before);
                SettingsUi.refresh(activity);
                require(toggle.isChecked() == before, "backend changes refresh switch");
                int callbacks = activity.mSystemNotificationView.changes;
                SettingsUi.refresh(activity);
                require(activity.mSystemNotificationView.changes == callbacks, "refresh has no write side effects");
                activity.setConnected(true);
                require(findText(content, "XREAL One") != null, "live model connection update");
                TextView launch = findText(content, "Enter AR Space");
                require(launch != null && launch.getVisibility() == View.VISIBLE, "connected launch button");
                launch.performClick();
                require(activity.launches == 1, "original launch callback");
                activity.setConnected(false);
                require(!launch.isEnabled() && launch.getText().toString().equals("Connect your glasses"), "launch disabled after disconnect");
                require(activity.findViewById(0x00bea001) != null, "settings remain available disconnected");
                require(findText(content, "About") != null, "About available offline");
                TextView help = findText(content, "Help");
                require(help != null, "Help available offline");
                View aboutRow = (View)findText(content, "About").getParent().getParent();
                View helpRow = (View)help.getParent().getParent();
                ViewGroup appRows = (ViewGroup)aboutRow.getParent();
                require(helpRow.getParent() == appRows && appRows.indexOfChild(helpRow) == appRows.indexOfChild(aboutRow) + 1,
                        "Help immediately below About");
                helpRow.performClick();
                require(activity.externalLaunch == null, "Help opens its menu before external support");
                require(findText(content, "Privacy policy") == null && findText(content, "Terms of service") == null,
                        "legal links moved out of main settings");
            });
            runOnMainSync(activity::finish);
            result.putString("stream", "Settings UI runtime checks passed: " + passed + "\n");
            finish(ActivityResult.OK, result);
        } catch (Throwable error) {
            result.putString("stream", "Settings UI check failed after " + passed + ": " + error + "\n");
            finish(ActivityResult.FAIL, result);
        }
    }
    void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); passed++; }
    static TextView findText(View root, String text) {
        if (root instanceof TextView && ((TextView)root).getText().toString().equals(text)) return (TextView)root;
        if (root instanceof ViewGroup) for (int i=0;i<((ViewGroup)root).getChildCount();i++) {
            TextView value = findText(((ViewGroup)root).getChildAt(i),text); if (value != null) return value;
        }
        return null;
    }
    static CompoundButton findToggle(View root, String title) {
        if (root instanceof CompoundButton && root.getContentDescription() != null && root.getContentDescription().toString().startsWith(title)) return (CompoundButton)root;
        if (root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            CompoundButton result=findToggle(((ViewGroup)root).getChildAt(i),title); if(result!=null)return result;
        }
        return null;
    }
    static final class ActivityResult { static final int OK = -1, FAIL = 0; }
}
