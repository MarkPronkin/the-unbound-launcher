package dev.beampro.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Navigate through the actual settings rows and wait for Android's Activity lifecycle. */
public final class MenuChecks {
    private MenuChecks() {}
    public static MenuActivity open(Instrumentation runner, Activity owner, String title) {
        return (MenuActivity)openActivity(runner, owner, title, MenuActivity.class);
    }
    public static Activity openActivity(Instrumentation runner, Activity owner, String title, Class<?> target) {
        Instrumentation.ActivityMonitor monitor = runner.addMonitor(target.getName(), null, false);
        try {
            runner.runOnMainSync(() -> {
                View label = findText(owner.findViewById(android.R.id.content), title);
                if (label == null) throw new AssertionError("Missing menu entry: " + title);
                ((View)label.getParent().getParent()).performClick();
            });
            Activity menu = runner.waitForMonitorWithTimeout(monitor, 5000);
            if (menu == null) throw new AssertionError("Native menu did not open: " + title);
            runner.waitForIdleSync();
            return menu;
        } finally { runner.removeMonitor(monitor); }
    }
    public static TextView findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView)root).getText())) return (TextView)root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) {
            TextView found = findText(((ViewGroup)root).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
    public static void awaitClosed(Instrumentation runner, Activity menu) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (!menu.isDestroyed() && SystemClock.uptimeMillis() < deadline) {
            runner.waitForIdleSync(); SystemClock.sleep(20);
        }
        if (!menu.isDestroyed()) throw new AssertionError("Menu Activity did not close");
    }
}
