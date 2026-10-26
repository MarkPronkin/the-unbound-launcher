package dev.beampro.settings.preview;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import dev.beampro.settings.AppMenuActivity;
import dev.beampro.settings.AppMenuStore;
import dev.beampro.settings.HeadsetMenuPreview;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Run at cover, inner, landscape and split-screen window sizes on an isolated emulator. */
public final class FoldableUiChecks extends Instrumentation {
    private int passed;
    private boolean expanded, transition;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        expanded = Boolean.parseBoolean(arguments.getString("expanded", "false"));
        transition = Boolean.parseBoolean(arguments.getString("transition", "false"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        File file = new File(getTargetContext().getExternalFilesDir(null), "AppDetails/EPHInfoConfig/LayoutConfig.json");
        byte[] backup = null; boolean existed = file.exists();
        try {
            checkSettings();
            new AppMenuStore(getTargetContext());
            if (existed) backup = Files.readAllBytes(file.toPath());
            file.getParentFile().mkdirs(); Files.write(file.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
            AppMenuActivity activity = openMenu(); awaitCatalog(activity);
            final AppMenuActivity initial = activity;
            List<String> original = new ArrayList<>(LauncherPreviewChecks.ids(activity, "visible"));
            runOnMainSync(() -> {
                checkMenuGeometry(initial, expanded);
                EditText search = (EditText) control(initial, "Search apps or package names");
                search.setText("Tutorial");
                ListView list = AppMenuChecks.findList(initial.getWindow().getDecorView());
                require(list.getAdapter().getCount() == 1, "search filters editor");
                require(LauncherPreviewChecks.tiles(LauncherPreviewChecks.preview(initial)).size() > 1, "preview retains surrounding apps");
                require(!control(initial, "Save app menu").isEnabled(), "resizing and search do not dirty the menu");
                if (!expanded) control(initial, "Show headset preview").performClick();
                try { AppMenuChecks.invoke(initial, "move", new Class[]{String.class, int.class}, original.get(0), original.size() - 1); }
                catch (Exception e) { throw new RuntimeException(e); }
                HeadsetMenuPreview preview = LauncherPreviewChecks.preview(initial);
                preview.showPage(preview.pageCount() - 1);
                require(control(initial, "Save app menu").isEnabled(), "draft edit enables Save");
            });
            int previewPage = LauncherPreviewChecks.preview(activity).getPage();
            if (transition) {
                activity = resizeMenu(activity, "1440x1600");
                final AppMenuActivity unfolded = activity;
                runOnMainSync(() -> {
                    checkMenuGeometry(unfolded, true);
                    checkDraft(unfolded, original, previewPage);
                });
                activity = resizeMenu(activity, "720x1600");
                final AppMenuActivity folded = activity;
                runOnMainSync(() -> {
                    checkMenuGeometry(folded, false);
                    require(LauncherPreviewChecks.preview(folded).isShown(), "cover screen restores chosen preview mode");
                    checkDraft(folded, original, previewPage);
                });
            } else {
                ActivityMonitor monitor = addMonitor(AppMenuActivity.class.getName(), null, false);
                runOnMainSync(activity::recreate);
                activity = (AppMenuActivity) waitForMonitorWithTimeout(monitor, 5000); removeMonitor(monitor);
                require(activity != null, "Activity recreated"); awaitCatalog(activity);
                final AppMenuActivity recreated = activity;
                runOnMainSync(() -> checkDraft(recreated, original, previewPage));
            }
            require(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).equals("{}"), "folding never auto-saves a draft");
            final AppMenuActivity current = activity;
            runOnMainSync(() -> {
                control(current, "Undo last menu change").performClick();
                try { require(LauncherPreviewChecks.ids(current, "visible").equals(original), "Undo survives recreation and restores order"); }
                catch (Exception e) { throw new RuntimeException(e); }
                current.finish();
            });
            result.putString("stream", "Foldable UI runtime checks passed: " + passed + "\n"); finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "Foldable UI check failed after " + passed + ": " + error + "\n"); finish(0, result);
        } finally {
            try { if (existed) Files.write(file.toPath(), backup); else file.delete(); } catch (Exception ignored) {}
        }
    }

    private void checkSettings() throws Exception {
        SettingsPreviewActivity activity = (SettingsPreviewActivity) startActivitySync(
                new Intent(getTargetContext(), SettingsPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        runOnMainSync(() -> {
            View content = activity.getWindow().getDecorView();
            View preferences = SettingsUiChecks.findText(content, "Preferences");
            require((preferences != null) == expanded, "Settings matches the available window width");
            View launch = SettingsUiChecks.findText(content, "Connect your glasses");
            View option = SettingsUiChecks.findText(content, "App menu");
            if (expanded) {
                require(bounds(launch).right < bounds(option).left, "glasses and settings occupy separate panes");
                require(bounds(launch).width() <= bounds(control(content, "Glasses and launch")).width(), "launch action fits its pane");
                require(bounds(control(activity.getWindow().getDecorView(), "Glasses and launch")).width() >= dp(250), "device pane remains useful at the breakpoint");
                ScrollView options = (ScrollView) control(content, "Settings options");
                ScrollView device = (ScrollView) control(content, "Glasses and launch");
                options.scrollTo(0, dp(300));
                require(device.getScrollY() == 0, "settings scroll independently of launch");
            }
            CompoundButton toggle = SettingsUiChecks.findToggle(content, "System notifications");
            int changes = activity.mSystemNotificationView.changes;
            toggle.performClick();
            require(activity.mSystemNotificationView.changes == changes + 1, "native settings callbacks retained");
            View artwork = activity.findViewById(0x00bea003);
            View hero = (View) artwork.getParent();
            if (((android.widget.ImageView) artwork).getScaleType() != android.widget.ImageView.ScaleType.FIT_XY) {
                require(hero.getHeight() > 0 && hero.getHeight() <= Math.round(hero.getWidth() * 480f / 990), "short landscape artwork fits without stretching");
            } else require(Math.abs(hero.getHeight() - Math.round(hero.getWidth() * 480f / 990)) <= 1, "glasses artwork follows pane width");
        });
        runOnMainSync(activity::finish); waitForIdleSync();
    }

    private void checkMenuGeometry(AppMenuActivity activity, boolean wide) {
        View editor = control(activity, "App list editor"), preview = control(activity, "Live headset preview");
        View modes = (View) control(activity, "Show headset preview").getParent();
        require((modes.getVisibility() == View.GONE) == wide, "mode switch only needed on compact windows");
        if (wide) {
            require(editor.isShown() && preview.isShown(), "editor and live preview visible together");
            Rect left = bounds(editor), right = bounds(preview);
            require(left.right + dp(23) <= right.left, "centre gutter separates panes");
            require(left.top == right.top && left.bottom == right.bottom, "panes share available height");
            require(left.width() >= dp(250) && right.width() >= dp(250), "both panes remain readable");
        } else {
            require(editor.isShown() != preview.isShown(), "compact window shows one active pane");
        }
    }

    private void checkDraft(AppMenuActivity activity, List<String> original, int page) {
        require(((EditText) control(activity, "Search apps or package names")).getText().toString().equals("Tutorial"), "search survives configuration change");
        require(LauncherPreviewChecks.preview(activity).getPage() == page, "headset page survives configuration change");
        require(control(activity, "Save app menu").isEnabled(), "unsaved draft survives configuration change");
        require(control(activity, "Undo last menu change").getVisibility() == View.VISIBLE, "Undo remains available");
        try {
            List<String> ids = LauncherPreviewChecks.ids(activity, "visible");
            require(ids.get(ids.size() - 1).equals(original.get(0)), "reordered app stays in its draft position");
        } catch (Exception error) { throw new RuntimeException(error); }
    }

    private AppMenuActivity resizeMenu(AppMenuActivity activity, String size) throws Exception {
        ActivityMonitor monitor = addMonitor(AppMenuActivity.class.getName(), null, false);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("wm size " + size))) {
            byte[] buffer = new byte[256]; while (input.read(buffer) != -1) {}
        }
        AppMenuActivity resized = null;
        long deadline = android.os.SystemClock.uptimeMillis() + 5000;
        int targetWidth = Integer.parseInt(size.split("x")[0]);
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            AppMenuActivity candidate = (AppMenuActivity) waitForMonitorWithTimeout(monitor, 200);
            if (candidate != null && candidate != activity) { resized = candidate; break; }
            // Android can resize an instrumented window in place. Exercise that path too.
            boolean[] reached = {false};
            runOnMainSync(() -> reached[0] = !activity.isDestroyed() && activity.getWindow().getDecorView().getWidth() == targetWidth);
            if (reached[0]) { resized = activity; break; }
        }
        removeMonitor(monitor);
        require(resized != null, "real window resize reaches requested width");
        awaitCatalog(resized); return resized;
    }

    private AppMenuActivity openMenu() { return (AppMenuActivity) startActivitySync(new Intent(getTargetContext(), AppMenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    private void awaitCatalog(AppMenuActivity activity) throws Exception {
        java.lang.reflect.Field field = AppMenuActivity.class.getDeclaredField("loading"); field.setAccessible(true);
        for (int i = 0; i < 100; i++) {
            waitForIdleSync(); boolean[] loading = {true};
            runOnMainSync(() -> {
                try { loading[0] = field.getBoolean(activity); } catch (Exception error) { throw new RuntimeException(error); }
            });
            if (!loading[0]) { waitForIdleSync(); return; }
            Thread.sleep(50);
        }
        throw new AssertionError("catalog timeout");
    }
    private View control(AppMenuActivity activity, String name) { return control(activity.getWindow().getDecorView(), name); }
    private View control(View root, String name) { View value = AppMenuChecks.findDescription(root, name); if (value == null) throw new AssertionError("Missing " + name); return value; }
    private Rect bounds(View view) { int[] location = new int[2]; view.getLocationOnScreen(location); return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight()); }
    private int dp(int value) { return Math.round(value * getTargetContext().getResources().getDisplayMetrics().density); }
    private void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); passed++; }
}
