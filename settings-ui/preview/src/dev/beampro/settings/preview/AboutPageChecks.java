package dev.beampro.settings.preview;

import android.app.Instrumentation;
import android.graphics.Color;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import dev.beampro.settings.MenuActivity;
import dev.beampro.settings.MenuChecks;
import ai.nreal.nebula.activity.splash.pptos.PpTosWebHelper;

/** Exercise the actual native About page, original legal helper and both Back paths. */
public final class AboutPageChecks extends Instrumentation {
    private int passed;
    private MenuActivity about;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        SettingsPreviewActivity owner = null;
        try {
            owner = (SettingsPreviewActivity)startActivitySync(new Intent(getTargetContext(), SettingsPreviewActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final SettingsPreviewActivity settings = owner;
            about = MenuChecks.open(this, owner, "About");
            runOnMainSync(() -> {
                try {
                    View page = about.getWindow().getDecorView();
                    require(MenuChecks.findText(page, "Unbound Launcher") != null, "in-app name leaves The out");
                    android.content.pm.PackageInfo installed = getTargetContext().getPackageManager()
                            .getPackageInfo(getTargetContext().getPackageName(), 0);
                    require(MenuChecks.findText(page, installed.versionName) != null, "app version agrees with installed package");
                    require(MenuChecks.findText(page, "MyGlasses 2.2.2") != null, "original base version");
                    require(MenuChecks.findText(page, "Build number") != null
                            && MenuChecks.findText(page, Long.toString(installed.getLongVersionCode())) != null, "actual build number");
                    require(about.getWindow().getStatusBarColor() == Color.BLACK
                            && about.getWindow().getNavigationBarColor() == Color.BLACK, "OLED system chrome");
                    int privacy = PpTosWebHelper.privacyClicks, terms = PpTosWebHelper.termsClicks;
                    row(MenuChecks.findText(page, "Privacy policy")).performClick();
                    row(MenuChecks.findText(page, "Terms of service")).performClick();
                    require(PpTosWebHelper.privacyClicks == privacy + 1 && PpTosWebHelper.termsClicks == terms + 1,
                            "original legal helper opens each document once");
                    require(PpTosWebHelper.owner == about, "legal document has the visible Activity as owner");
                    require(settings.launches == 0 && settings.mSystemNotificationView.changes == 0,
                            "opening About preserves AR and settings state");
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            MenuChecks.awaitClosed(this, about);
            require(!owner.isFinishing(), "system Back returns to settings");
            about = MenuChecks.open(this, owner, "About");
            runOnMainSync(() -> {
                View back = about.getWindow().getDecorView().findViewWithTag("about-back");
                require(back != null, "accessible toolbar Back"); back.performClick();
            });
            MenuChecks.awaitClosed(this, about);
            require(!owner.isFinishing(), "toolbar Back returns to settings");
            result.putString("stream", "About page runtime checks passed: " + passed + "\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "About page checks failed after " + passed + ": " + error + "\n");
            finish(0, result);
        } finally {
            if (owner != null) { final SettingsPreviewActivity close = owner; runOnMainSync(close::finish); }
            if (about != null) runOnMainSync(about::finish);
        }
    }
    private static View row(TextView label) { return (View)label.getParent().getParent(); }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
}
