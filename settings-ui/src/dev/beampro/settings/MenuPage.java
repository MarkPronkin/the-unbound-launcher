package dev.beampro.settings;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.ScrollView;

/** Content of a native menu Activity; Android owns all navigation animation. */
abstract class MenuPage {
    final Activity activity;
    ScrollView scroll;
    private int restoredScroll;

    MenuPage(Activity activity) { this.activity = activity; }
    abstract void render();
    protected void onStart() {}
    protected void onStop() {}
    final Window getWindow() { return activity.getWindow(); }
    final void setContentView(View content) { activity.setContentView(content); }
    final void dismiss() { activity.finish(); }
    final boolean isShowing() { return !activity.isFinishing() && !activity.isDestroyed(); }
    final int scrollPosition() {
        if (scroll != null && scroll.isLaidOut()) restoredScroll = scroll.getScrollY();
        return restoredScroll;
    }
    void restoreState(Bundle state) { if (state != null) restoredScroll = state.getInt("menu-scroll"); }
    void saveState(Bundle state) { state.putInt("menu-scroll", scrollPosition()); }
}
