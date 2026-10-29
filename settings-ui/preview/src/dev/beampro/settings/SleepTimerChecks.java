package dev.beampro.settings;

import ai.nreal.nebula.manager.XREALManager;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import com.xreal.glassesdisplayplugevent.GlassesInitSetting;
import dev.beampro.settings.preview.SettingsPreviewActivity;

/** Exercises real menu navigation, offline persistence and both original SDK timeout contracts. */
public final class SleepTimerChecks extends Instrumentation {
    private int passed;
    private SettingsPreviewActivity owner;
    private MenuActivity menu;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            getTargetContext().getSharedPreferences(SleepTimer.PREFERENCES, 0).edit().clear().commit();
            XREALManager.reset();
            GlassesInitSetting.previewSleepLevels = true;
            GlassesInitSetting.previewSleepUnsupported = false;
            owner = (SettingsPreviewActivity) startActivitySync(new Intent(getTargetContext(), SettingsPreviewActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            runOnMainSync(() -> {
                View row = owner.findViewById(android.R.id.content).findViewWithTag("automatic-sleep");
                require(row != null && row.getVisibility() == View.VISIBLE && row.isEnabled(), "sleep row available offline");
                require(owner.mSleepTimeView.getVisibility() == View.GONE, "legacy connection-only view stays hidden");
                require(XREALManager.reads == 0 && writes() == 0, "offline rendering never calls hardware");
            });
            menu = MenuChecks.open(this, owner, "Automatic sleep time");
            runOnMainSync(() -> {
                require(menu.page instanceof SleepTimerPage, "sleep uses the shared native menu");
                require(owner.menuLaunchOptions.getInt("android:activity.animEnterRes") == android.R.anim.fade_in,
                        "sleep uses the same fade transition");
                require(((ColorDrawable)((View)menu.page.scroll.getParent().getParent()).getBackground()).getColor() == Signature.BACKGROUND,
                        "sleep shares the black page background");
                require(menu.getWindow().getStatusBarColor() == Signature.BACKGROUND, "black system bars");
                require(MenuChecks.findText(content(), "UNBOUND LAUNCHER") != null, "shared app bar");
                for (int i = 0; i < SleepTimer.count(); i++) {
                    AccessibilityNodeInfo info = choice(i).createAccessibilityNodeInfo();
                    require(info.isCheckable() && info.isChecked() == (i == 2), "accessible radio choice " + i);
                    require(choice(i).getMinimumHeight() >= Signature.dp(menu, 48), "accessible touch target " + i);
                    info.recycle();
                }
                choice(3).performClick();
                require(timer().selected() == 3 && timer().pending() && choice(3).isSelected(), "offline selection saves and updates immediately");
                require(writes() == 0, "offline selection does not call SDK");
                SleepTimer restored = new SleepTimer(getTargetContext());
                require(restored.selected() == 3 && restored.pending(), "choice and pending state survive a new controller instance");
                require(MenuChecks.findText(content(), "Saved on this phone. Applies when your glasses connect.") != null,
                        "offline save guidance");
            });
            closeMenu();
            runOnMainSync(() -> require(MenuChecks.findText(owner.findViewById(android.R.id.content), "20 minutes") != null,
                    "settings summary refreshes on return"));
            menu = MenuChecks.open(this, owner, "Automatic sleep time");
            require(choice(3).isSelected(), "selection survives reopening");
            ActivityMonitor recreated = addMonitor(MenuActivity.class.getName(), null, false);
            try {
                runOnMainSync(menu::recreate);
                menu = (MenuActivity) waitForMonitorWithTimeout(recreated, 5000);
                require(menu != null, "menu recreates");
                waitForIdleSync();
                runOnMainSync(() -> {
                    require(choice(3).isSelected(), "selection survives recreation");
                    menu.onConfigurationChanged(new Configuration(menu.getResources().getConfiguration()));
                    require(choice(3).isSelected() && writes() == 0, "configuration rebuild preserves selection without writes");
                    owner.setConnectedModel(7);
                    require(XREALManager.sleepLevel == 3 && XREALManager.levelWrites == 1 && XREALManager.timeWrites == 0,
                            "reconnect applies queued G-series level exactly once");
                    require(!timer().pending(), "successful SDK write clears pending state");
                    SettingsUi.refresh(owner);
                    require(writes() == 1, "ordinary refresh never repeats a successful write");
                    XREALManager.sleepLevel = 4;
                    SettingsUi.refresh(owner);
                    require(timer().selected() == 4 && choice(4).isSelected(), "live hardware changes update the open menu");
                    owner.setConnected(false);
                    choice(0).performClick();
                    require(writes() == 1 && timer().pending(), "menu remains usable after disconnect");
                    GlassesInitSetting.previewSleepLevels = false;
                    owner.setConnectedModel(2);
                    require(XREALManager.sleepSeconds == 30 && XREALManager.timeWrites == 1,
                            "legacy glasses receive seconds after reconnect");
                    int[] seconds = {30, 300, 600, 1200, 3600, 9000};
                    for (int i = 0; i < seconds.length; i++) {
                        choice(i).performClick();
                        require(XREALManager.sleepSeconds == seconds[i] && !timer().pending(), "original seconds mapping " + i);
                    }
                    GlassesInitSetting.previewSleepLevels = true;
                    for (int i = 0; i < seconds.length; i++) {
                        choice(i).performClick();
                        require(XREALManager.sleepLevel == i && !timer().pending(), "original level mapping " + i);
                    }
                    XREALManager.rejectWrites = true;
                    choice(1).performClick();
                    int writes = writes();
                    require(timer().pending() && timer().selected() == 1, "rejected SDK write retains pending choice");
                    SettingsUi.refresh(owner);
                    require(writes() == writes && timer().selected() == 1, "stale hardware cannot overwrite pending choice");
                    owner.setConnected(false);
                    XREALManager.rejectWrites = false;
                    owner.setConnectedModel(7);
                    require(XREALManager.sleepLevel == 1 && !timer().pending(), "failed choice retries on reconnect");
                    XREALManager.throwWrites = true;
                    choice(3).performClick();
                    require(timer().pending() && timer().selected() == 3, "SDK exception retains choice");
                    XREALManager.throwWrites = false;
                    owner.setConnected(false);
                    GlassesInitSetting.previewSleepUnsupported = true;
                    writes = writes();
                    owner.setConnectedModel(10);
                    choice(4).performClick();
                    require(writes() == writes && timer().pending(), "unsupported glasses never receive a write");
                    require(timer().status().contains("do not support"), "unsupported model guidance");
                    owner.setConnected(false);
                    GlassesInitSetting.previewSleepUnsupported = false;
                    owner.setConnectedModel(7);
                    require(XREALManager.sleepLevel == 4 && !timer().pending(), "pending choice applies to next supported glasses");
                    require(owner.launches == 0 && owner.mSystemNotificationView.changes == 0, "unrelated controls unchanged");
                });
            } finally { removeMonitor(recreated); }
            closeMenu();
            result.putString("stream", "Automatic sleep runtime checks passed: " + passed + "\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", "Automatic sleep failed after " + passed + ": " + failure + "\n");
            finish(0, result);
        } finally {
            if (menu != null) runOnMainSync(menu::finish);
            if (owner != null) runOnMainSync(owner::finish);
        }
    }
    private SleepTimer timer() { return SleepTimer.get(getTargetContext()); }
    private View content() { return menu.findViewById(android.R.id.content); }
    private View choice(int index) { return content().findViewWithTag("sleep-choice-" + index); }
    private static int writes() { return XREALManager.levelWrites + XREALManager.timeWrites; }
    private void closeMenu() {
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); MenuChecks.awaitClosed(this, menu); menu = null; waitForIdleSync();
    }
    private void require(boolean value, String description) { if (!value) throw new AssertionError(description); passed++; }
}
