package dev.beampro.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.KeyEvent;
import dev.beampro.settings.preview.SettingsPreviewActivity;

/** Native Activity navigation in every menu, with no simulated dialog transition. */
public final class MenuPageChecks extends Instrumentation {
    private int passed;
    private Activity current;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        SettingsPreviewActivity owner = null;
        try {
            for (int resource : new int[]{android.R.anim.fade_in, android.R.anim.fade_out}) {
                android.view.animation.Animation fade = android.view.animation.AnimationUtils.loadAnimation(getTargetContext(), resource);
                fade.initialize(800, 1200, 800, 1200); fade.setStartTime(0);
                android.view.animation.Transformation halfway = new android.view.animation.Transformation();
                fade.getTransformation(fade.computeDurationHint() / 2, halfway);
                require(halfway.getMatrix().isIdentity(), "fade does not move or scale content");
                require(halfway.getAlpha() > 0f && halfway.getAlpha() < 1f, "fade changes only opacity");
            }
            owner = (SettingsPreviewActivity)startActivitySync(new Intent(getTargetContext(), SettingsPreviewActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            String[] titles = {"App permissions", "About", "Help", "App menu"};
            Class<?>[] targets = {PermissionSetupActivity.class, MenuActivity.class, MenuActivity.class, AppMenuActivity.class};
            int[] expected = null;
            for (int i = 0; i < titles.length; i++) {
                owner.menuLaunchOptions = null;
                current = MenuChecks.openActivity(this, owner, titles[i], targets[i]);
                require(owner.menuLaunchOptions != null
                        && owner.menuLaunchOptions.getInt("android:activity.animEnterRes") == android.R.anim.fade_in
                        && owner.menuLaunchOptions.getInt("android:activity.animExitRes") == android.R.anim.fade_out,
                        titles[i] + " explicitly launches with fade in and fade out");
                final Activity menu = current;
                int[] motion = new int[4];
                runOnMainSync(() -> {
                    require(menu.getWindow().getDecorView().isAttachedToWindow(), "menu has an attached native window");
                    require(menu.getWindow().getDecorView().getAnimation() == null, "no custom decor animation");
                    TypedArray window = menu.obtainStyledAttributes(new int[]{android.R.attr.windowIsTranslucent});
                    require(window.getBoolean(0, false), "native translucent window matches the pre-2937 editor");
                    window.recycle();
                    try {
                        android.content.pm.ActivityInfo info = menu.getPackageManager().getActivityInfo(menu.getComponentName(), 0);
                        android.content.res.Resources.Theme manifest = menu.getResources().newTheme();
                        manifest.applyStyle(info.getThemeResource(), true);
                        TypedArray server = manifest.obtainStyledAttributes(new int[]{android.R.attr.windowIsTranslucent});
                        require(server.getBoolean(0, false), "manifest and runtime agree on native translucent navigation");
                        server.recycle();
                    } catch (Exception error) { throw new RuntimeException(error); }
                    int style = menu.getWindow().getAttributes().windowAnimations;
                    require(style != 0, "Android owns the window transition");
                    TypedArray attrs = menu.obtainStyledAttributes(style, new int[]{android.R.attr.activityOpenEnterAnimation,
                            android.R.attr.activityOpenExitAnimation, android.R.attr.activityCloseEnterAnimation,
                            android.R.attr.activityCloseExitAnimation});
                    for (int n = 0; n < motion.length; n++) motion[n] = attrs.getResourceId(n, 0);
                    attrs.recycle();
                    if (menu instanceof MenuActivity) {
                        MenuPage page = ((MenuActivity)menu).page;
                        require(page != null && page.getWindow() == menu.getWindow(), "menu content uses Activity window");
                        android.content.res.Configuration config = new android.content.res.Configuration(menu.getResources().getConfiguration());
                        menu.onConfigurationChanged(config);
                        require(((MenuActivity)menu).page == page, "configuration changes retain menu state");
                        require(menu.getWindow().getDecorView().getAnimation() == null, "content refresh does not replay navigation");
                    }
                });
                if (expected == null) expected = motion;
                else require(java.util.Arrays.equals(expected, motion), titles[i] + " uses the authority-management transition");
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                MenuChecks.awaitClosed(this, menu);
                require(!owner.isFinishing(), "Back returns to the existing settings Activity");
            }
            for (String title : new String[]{"About", "Help"}) {
                current = MenuChecks.open(this, owner, title);
                int[] savedScroll = new int[1];
                ActivityMonitor recreated = addMonitor(MenuActivity.class.getName(), null, false);
                try {
                    runOnMainSync(() -> ((MenuActivity)current).page.scroll.scrollTo(0, 120));
                    waitForIdleSync();
                    runOnMainSync(() -> {
                        savedScroll[0] = ((MenuActivity)current).page.scroll.getScrollY();
                        require(savedScroll[0] > 0, "menu can scroll before recreation");
                        current.recreate();
                    });
                    current = waitForMonitorWithTimeout(recreated, 5000);
                    require(current != null, "native menu recreates");
                    waitForIdleSync();
                    runOnMainSync(() -> require(((MenuActivity)current).page.scroll.getScrollY() == savedScroll[0],
                            "scroll survives Activity recreation"));
                    sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                    MenuChecks.awaitClosed(this, current);
                } finally { removeMonitor(recreated); }
            }
            result.putString("stream", "Native menu navigation checks passed: " + passed + "\n"); finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "Native menu navigation failed after " + passed + ": " + error + "\n"); finish(0, result);
        } finally {
            if (current != null) runOnMainSync(current::finish);
            if (owner != null) { final Activity close = owner; runOnMainSync(close::finish); }
        }
    }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
}
