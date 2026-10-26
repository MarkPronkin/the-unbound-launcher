package dev.beampro.settings.preview;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import dev.beampro.settings.PermissionSetupActivity;

/** Exercise the real startup gate, countdown, recreation, and durable acceptance. */
public final class DisclaimerChecks extends Instrumentation {
    private static final String PASSED = "dev.beampro.settings.SETUP_PASSED";
    private static final String REVIEW = "dev.beampro.settings.REVIEW_PERMISSIONS";
    int passed;
    Activity current;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            SharedPreferences consent = getTargetContext().getSharedPreferences("beampro_disclaimer", 0);
            require(consent.edit().clear().commit(), "fresh-install consent state");
            PermissionSetupActivity.initialize((android.app.Application)getTargetContext().getApplicationContext());
            java.lang.reflect.Field initialized = PermissionSetupActivity.class.getDeclaredField("initialized");
            initialized.setAccessible(true);
            require(!initialized.getBoolean(null), "application initialization waits for acceptance even with overlay access");

            Intent incoming = new Intent(getTargetContext(), SettingsPreviewActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setAction("dev.beampro.TEST_USB_START")
                    .setData(Uri.parse("beampro-test://glasses/connected"))
                    .putExtra("connection_marker", 73).putExtra(PASSED, true).putExtra(REVIEW, true);
            Activity entry = startActivitySync(incoming);
            ActivityMonitor monitor = addMonitor(PermissionSetupActivity.class.getName(), null, false);
            runOnMainSync(() -> require(PermissionSetupActivity.guard(entry), "startup guard cannot skip disclaimer via setup-passed extra"));
            current = waitForMonitorWithTimeout(monitor, 3000); removeMonitor(monitor);
            require(current != null, "startup redirects to disclaimer");
            long opened = SystemClock.elapsedRealtime();
            waitForIdleSync();
            runOnMainSync(() -> {
                require(text("Before you begin") != null, "disclaimer precedes permission checklist");
                require(text("App permissions") == null, "no permission controls before acceptance");
                require(text("Phone compatibility") != null, "phone requirement visible");
                require(text("This app requires a phone with USB-C DisplayPort Alt Mode support. It will not work on phones without DisplayPort Alt Mode.") != null,
                        "unsupported-phone warning is explicit");
                require(text("Update your glasses to the latest firmware using the XREAL update website. The app may not work with older firmware versions.") != null,
                        "latest firmware requirement and older firmware warning");
                require(text("This is a fan-made project. All rights to the original XREAL software are reserved by XREAL.") != null,
                        "fan project and XREAL rights notice");
                require("dev.beampro.TEST_USB_START".equals(current.getIntent().getAction())
                        && "beampro-test://glasses/connected".equals(current.getIntent().getDataString())
                        && current.getIntent().getIntExtra("connection_marker", 0) == 73, "incoming connection intent preserved");
                Button accept = button();
                require(!accept.isEnabled() && accept.getText().toString().matches("I accept \\([0-9]+s\\)"), "countdown visible and acceptance disabled");
                accept.performClick();
                require(!PermissionSetupActivity.hasAcceptedDisclaimer(current), "early programmatic click cannot accept");
            });

            android.content.IntentFilter updateLink = new android.content.IntentFilter(Intent.ACTION_VIEW);
            updateLink.addDataScheme("https"); updateLink.addDataAuthority("www.xreal.com", null);
            updateLink.addDataPath("/ota/", android.os.PatternMatcher.PATTERN_LITERAL);
            ActivityMonitor updater = addMonitor(updateLink, null, true);
            runOnMainSync(() -> text("XREAL firmware updater ↗\nxreal.com/ota").performClick());
            require(updater.getHits() == 1, "firmware link opens external browser without permission request");
            removeMonitor(updater);

            // Recreate after several seconds: resetting the wait would leave the new button disabled at the original deadline.
            SystemClock.sleep(3100);
            ActivityMonitor recreation = addMonitor(PermissionSetupActivity.class.getName(), null, false);
            runOnMainSync(current::recreate);
            current = waitForMonitorWithTimeout(recreation, 3000); removeMonitor(recreation);
            require(current != null, "screen recreation succeeds");
            waitForIdleSync();
            runOnMainSync(() -> require(!button().isEnabled(), "recreation does not bypass countdown"));
            long wait = opened + 10_400 - SystemClock.elapsedRealtime();
            if (wait > 0) SystemClock.sleep(wait);
            waitForIdleSync();
            runOnMainSync(() -> {
                require(button().isEnabled() && "I accept".contentEquals(button().getText()), "acceptance unlocks after original ten-second deadline");
                button().performClick();
                require(PermissionSetupActivity.hasAcceptedDisclaimer(current), "acceptance saved durably");
                require(text("App permissions") != null && text("Before you begin") == null, "acceptance reveals permission checklist");
            });
            require(consent.getBoolean("accepted_v1", false), "saved choice can be read from preferences");
            runOnMainSync(current::finish);
            current = startActivitySync(new Intent(getTargetContext(), PermissionSetupActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(REVIEW, true));
            waitForIdleSync();
            runOnMainSync(() -> {
                require(text("App permissions") != null && text("Before you begin") == null, "later opens skip accepted disclaimer");
                current.finish();
            });
            result.putString("stream", "Disclaimer runtime checks passed: " + passed + "\n");
            finish(-1, result);
        } catch (Throwable error) {
            if (current != null) runOnMainSync(current::finish);
            result.putString("stream", "Disclaimer check failed after " + passed + ": " + error + "\n");
            finish(0, result);
        }
    }
    android.widget.TextView text(String value) { return SettingsUiChecks.findText(current.findViewById(android.R.id.content), value); }
    Button button() { return findButton(current.findViewById(android.R.id.content)); }
    Button findButton(View view) {
        if (view instanceof Button && ((Button)view).getText().toString().startsWith("I accept")) return (Button)view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) { Button found = findButton(group.getChildAt(i)); if (found != null) return found; }
        }
        return null;
    }
    void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); passed++; }
}
