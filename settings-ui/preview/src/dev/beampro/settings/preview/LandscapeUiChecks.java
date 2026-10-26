package dev.beampro.settings.preview;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.beampro.settings.AppMenuActivity;
import dev.beampro.settings.HeadsetMenuPreview;
import dev.beampro.settings.PermissionSetupActivity;

/** Real-window reachability, independent scrolling and input on 16:9 / 16:10 displays. */
public final class LandscapeUiChecks extends Instrumentation {
    private Activity current;
    private int passed;
    private boolean expanded;
    private String capture;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        expanded = Boolean.parseBoolean(args.getString("expanded", "true"));
        capture = args.getString("capture", ""); start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        boolean accepted = PermissionSetupActivity.hasAcceptedDisclaimer(getTargetContext());
        try {
            if (!capture.isEmpty()) {
                if (capture.equals("settings")) openSettings();
                else if (capture.equals("menu")) openMenu();
                else openPermissions(capture.equals("permissions"));
                waitForIdleSync(); SystemClock.sleep(250); screenshot("landscape-capture.png");
            } else {
                checkSettings(); close();
                checkMenu(); close();
                checkSetup(false); close();
                checkSetup(true); close();
            }
            result.putString("stream", "Landscape UI checks passed: " + passed + "\n"); finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "Landscape UI checks failed after " + passed + ": " + error + "\n"); finish(0, result);
        } finally {
            close();
            getTargetContext().getSharedPreferences("beampro_disclaimer", 0).edit().putBoolean("accepted_v1", accepted).commit();
        }
    }

    private void openSettings() {
        current = startActivitySync(new Intent(getTargetContext(), SettingsPreviewActivity.class)
                .putExtra("connected", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync();
    }

    private void checkSettings() {
        openSettings();
        runOnMainSync(() -> {
            if (expanded) {
                View launch = text("Enter AR Space");
                fullyVisible(launch, "launch available without scrolling");
                Rect before = bounds(launch);
                ScrollView preferences = (ScrollView) control("Settings options");
                preferences.scrollTo(0, preferences.getChildAt(0).getHeight());
                ((ScrollView) control("Glasses and launch")).scrollTo(0, 10000);
                fullyVisible(launch, "launch stays available while either pane scrolls");
                require(bounds(launch).equals(before), "scrolling does not move the launch action");
                launch.performClick();
                require(((SettingsPreviewActivity) current).launches == 1, "landscape launch keeps the original action");
                ((SettingsPreviewActivity) current).setConnected(false);
                require(!text("Connect your glasses").isEnabled(), "offline launch stays disabled");
            }
        });
    }

    private void openMenu() throws Exception {
        current = startActivitySync(new Intent(getTargetContext(), AppMenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.lang.reflect.Field loading = AppMenuActivity.class.getDeclaredField("loading"); loading.setAccessible(true);
        for (int i = 0; i < 100; i++) {
            waitForIdleSync(); boolean[] pending = {true};
            runOnMainSync(() -> { try { pending[0] = loading.getBoolean(current); } catch (Exception e) { throw new RuntimeException(e); } });
            if (!pending[0]) { waitForIdleSync(); return; }
            SystemClock.sleep(50);
        }
        throw new AssertionError("App catalog did not load");
    }

    private void checkMenu() throws Exception {
        openMenu();
        runOnMainSync(() -> {
            fullyVisible(control("Save app menu"), "Save is reachable");
            fullyVisible(control("Search apps or package names"), "search fits the window");
            ListView list = AppMenuChecks.findList(root());
            require(list.getHeight() >= dp(48), "at least one complete list touch target fits");
            if (expanded) {
                View preview = control("Live headset preview");
                require(bounds(control("Search apps or package names")).bottom <= bounds(preview).top, "toolbar search leaves both panes their full height");
                require(bounds(preview).top < bounds(list).top, "preview uses space beside the search and filters");
                HeadsetMenuPreview headset = LauncherPreviewChecks.preview((AppMenuActivity) current);
                if (getTargetContext().getResources().getConfiguration().fontScale <= 1.3f) {
                    for (String id : LauncherPreviewChecks.tiles(headset)) fullyVisible(headset.findViewWithTag(id), "both preview rows fit");
                    fullyVisible(control("Next headset page"), "preview paging fits without scrolling");
                }
            }
            ((EditText) control("Search apps or package names")).setText("Tutorial");
            require(list.getAdapter().getCount() == 1, "search filters the real app list");
            require(!control("Save app menu").isEnabled(), "search alone does not change the saved menu");
        });
        // Exercise the actual IME, which must not replace landscape search with Android's fullscreen editor.
        runOnMainSync(() -> {
            EditText search = (EditText) control("Search apps or package names");
            search.requestFocus(); current.getSystemService(InputMethodManager.class).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        });
        SystemClock.sleep(600); waitForIdleSync();
        screenshot("landscape-keyboard.png");
        runOnMainSync(() -> {
            require(control("Search apps or package names").hasFocus(), "toolbar search retains input focus");
            require(root().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()), "the real keyboard remains open");
            fullyVisible(control("Save app menu"), "Save remains reachable with the keyboard");
            fullyVisible(control("Search apps or package names"), "search remains reachable with the keyboard");
            require(AppMenuChecks.findList(root()).getHeight() >= dp(48), "keyboard leaves a usable search result: list="
                    + AppMenuChecks.findList(root()).getHeight() + ", window=" + root().getHeight() + ", search=" + control("Search apps or package names").getHeight());
            current.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root().getWindowToken(), 0);
        });
        SystemClock.sleep(300); waitForIdleSync();
    }

    private void openPermissions(boolean accepted) {
        getTargetContext().getSharedPreferences("beampro_disclaimer", 0).edit().putBoolean("accepted_v1", accepted).commit();
        current = startActivitySync(new Intent(getTargetContext(), PermissionSetupActivity.class)
                .putExtra("dev.beampro.settings.REVIEW_PERMISSIONS", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync();
    }

    private void checkSetup(boolean accepted) {
        openPermissions(accepted);
        runOnMainSync(() -> {
            View heading = text(accepted ? "App permissions" : "Before you begin");
            View detail = text(accepted ? "Display over other apps" : "Phone compatibility");
            if (expanded) {
                require(bounds(heading).right < bounds(detail).left, "overview sits beside the scrolling details");
                require(root().findViewWithTag("landscape-page-overview").isShown(), "independent overview is visible");
            }
            if (!accepted) {
                View actions = root().findViewWithTag("landscape-page-actions");
                fullyVisible(actions, "acceptance stays in the visible action area");
                ((ScrollView) root().findViewWithTag("landscape-page-details")).scrollTo(0, 10000);
                fullyVisible(actions, "disclaimer can scroll without losing acceptance");
            }
            require(root().getWidth() > 0 && root().getHeight() > 0, "page fills an actual window");
        });
    }

    private View root() { return current.findViewById(android.R.id.content); }
    private void screenshot(String name) throws Exception {
        try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getFilesDir(), name))) {
            android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
            require(image != null, "real window screenshot available");
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file); image.recycle();
        }
    }
    private View text(String label) { View view = SettingsUiChecks.findText(root(), label); if (view == null) throw new AssertionError("Missing " + label); return view; }
    private View control(String label) { View view = AppMenuChecks.findDescription(root(), label); if (view == null) throw new AssertionError("Missing " + label); return view; }
    private Rect bounds(View view) { int[] p = new int[2]; view.getLocationOnScreen(p); return new Rect(p[0], p[1], p[0] + view.getWidth(), p[1] + view.getHeight()); }
    private void fullyVisible(View view, String message) {
        Rect visible = new Rect();
        require(view.getGlobalVisibleRect(visible) && visible.width() == view.getWidth() && visible.height() == view.getHeight(), message);
    }
    private int dp(int value) { return Math.round(value * getTargetContext().getResources().getDisplayMetrics().density); }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
    private void close() { if (current != null) { runOnMainSync(current::finish); waitForIdleSync(); current = null; } }
}
