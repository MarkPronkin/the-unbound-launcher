package dev.beampro.settings.preview;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.beampro.settings.SettingsUi;

/** Exercise the shipped page, original policy callbacks, and both back paths. */
public final class AboutPageChecks extends Instrumentation {
    int passed;
    Dialog about;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            SettingsPreviewActivity activity = (SettingsPreviewActivity) startActivitySync(
                    new Intent(getTargetContext(), SettingsPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            runOnMainSync(() -> {
                try {
                    View content = activity.findViewById(android.R.id.content);
                    TextView entry = text(content, "About");
                    require(entry != null, "About entry available without glasses");
                    ((View)entry.getParent().getParent()).performClick();
                    Object state = field(activity.findViewById(0x00bea001), "state");
                    about = (Dialog)field(state, "aboutPage");
                    require(about.isShowing(), "About opens as a separate full-screen page");
                    View page = about.getWindow().getDecorView();
                    require(text(page, SettingsUi.APP_NAME) != null, "project name");
                    TextView description = text(page, "A fan-made project that extends the features and device support of the original MyGlasses software.");
                    require(description != null, "fan-made feature and device support description");
                    require(text(page, "This project is independent and is not affiliated with or endorsed by XREAL. All rights to the original XREAL software remain with XREAL.") != null, "original rights and independent project");
                    require(text(page, "0.1.0 alpha") != null, "app alpha version");
                    require(text(page, "MyGlasses 2.2.2") != null, "real base version independent of original runtime label");
                    require(about.getWindow().getStatusBarColor() == Color.BLACK
                            && about.getWindow().getNavigationBarColor() == Color.BLACK, "OLED system chrome");
                    require(getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(), 0)
                            .versionName.equals("0.1.0 alpha"), "Android package version agrees with About");
                    ((View)text(page, "Privacy policy").getParent().getParent()).performClick();
                    require(activity.privacyClicks == 1, "original privacy callback runs once");
                    ((View)text(page, "Terms of service").getParent().getParent()).performClick();
                    require(activity.termsClicks == 1, "original terms callback runs once");
                    require(activity.launches == 0 && activity.mSystemNotificationView.changes == 0, "opening About preserves settings and AR state");
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            waitForIdleSync();
            runOnMainSync(() -> {
                View page = about.getWindow().getDecorView();
                View description = text(page, "A fan-made project that extends the features and device support of the original MyGlasses software.");
                View versions = text(page, "VERSIONS");
                int[] info = new int[2], detail = new int[2];
                description.getLocationOnScreen(info); versions.getLocationOnScreen(detail);
                require(info[1] + description.getHeight() <= detail[1] || info[0] + description.getWidth() < detail[0],
                        "project info precedes versions vertically or in the leading landscape pane");
            });
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            waitForIdleSync();
            runOnMainSync(() -> {
                require(!about.isShowing() && !activity.isFinishing(), "system back returns to settings");
                View content = activity.findViewById(android.R.id.content);
                TextView entry = text(content, "About");
                ((View)entry.getParent().getParent()).performClick();
                require(about.isShowing(), "About can reopen");
                View back = about.getWindow().getDecorView().findViewWithTag("about-back");
                require(back != null, "accessible toolbar back");
                back.performClick();
                require(!about.isShowing() && !activity.isFinishing(), "toolbar back returns to settings");
                ((View)entry.getParent().getParent()).performClick();
                activity.finish();
            });
            AtomicBoolean destroyed = new AtomicBoolean();
            long deadline = SystemClock.uptimeMillis() + 5000;
            do {
                waitForIdleSync();
                runOnMainSync(() -> destroyed.set(activity.isDestroyed() && !about.isShowing()));
                if (!destroyed.get()) SystemClock.sleep(50);
            } while (!destroyed.get() && SystemClock.uptimeMillis() < deadline);
            require(destroyed.get(), "page dismissed when owning settings are destroyed");
            result.putString("stream", "About page runtime checks passed: " + passed + "\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "About page check failed after " + passed + ": " + error + "\n");
            finish(0, result);
        }
    }
    static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    static TextView text(View root, String value) {
        return SettingsUiChecks.findText(root, value);
    }
    void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); passed++; }
}
