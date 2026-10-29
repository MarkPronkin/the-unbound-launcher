package dev.beampro.settings;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;

/** Full-screen menus follow the same native navigation as authority management. */
public final class MenuActivity extends Activity {
    public static final String PAGE = "dev.beampro.settings.MENU_PAGE";
    public static final String ABOUT = "about", HELP = "help", SLEEP = "sleep";
    MenuPage page;

    /** The existing theme used by the app-menu editor before build 2937. */
    public static void applyTheme(Activity activity) {
        int theme = activity.getResources().getIdentifier("Theme.Transparent", "style", activity.getPackageName());
        if (theme == 0) throw new IllegalStateException("Original app-menu theme is missing");
        activity.setTheme(theme);
        MenuTransitions.install(activity);
    }

    public static void open(Activity activity, String page) {
        MenuTransitions.open(activity, new Intent(activity, MenuActivity.class).putExtra(PAGE, page));
    }

    @Override public void onCreate(Bundle state) {
        applyTheme(this);
        super.onCreate(state);
        String name = getIntent().getStringExtra(PAGE);
        if (ABOUT.equals(name)) {
            setTitle("About");
            page = new AboutPage(this, () -> TermsConsent.openDocument(this, true),
                    () -> TermsConsent.openDocument(this, false));
        } else if (HELP.equals(name)) {
            setTitle("Help");
            page = new HelpPage(this);
        } else if (SLEEP.equals(name)) {
            setTitle("Automatic sleep");
            page = new SleepTimerPage(this);
        } else {
            finish(); return;
        }
        Signature.window(getWindow());
        page.restoreState(state);
        page.render();
    }

    @Override protected void onStart() { super.onStart(); if (page != null) page.onStart(); }
    @Override protected void onStop() { if (page != null) page.onStop(); super.onStop(); }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (page != null) page.render();
    }
    @Override public void onSaveInstanceState(Bundle state) {
        if (page != null) page.saveState(state);
        super.onSaveInstanceState(state);
    }
}
