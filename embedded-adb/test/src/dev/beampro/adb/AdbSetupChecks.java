package dev.beampro.adb;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import dev.beampro.settings.PermissionSetupActivity;
import dev.beampro.settings.TermsConsent;

/** First-run ordering and UI checks in an isolated APK. Ready checks use a real shell Binder. */
public final class AdbSetupChecks extends Instrumentation {
    private Bundle args;
    private Activity current;
    private int passed;
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if ("ready".equals(args.getString("phase"))) ready();
            else if ("launcher".equals(args.getString("phase"))) launcher();
            else if ("terms".equals(args.getString("phase"))) terms();
            else if ("capture-terms".equals(args.getString("phase"))) {
                prepareTerms();
                current = startActivitySync(incoming(PermissionSetupActivity.class)); waitForIdleSync();
                captureScreen(); current = null;
            }
            else if ("capture".equals(args.getString("phase")) || "capture-help".equals(args.getString("phase"))
                    || "landscape".equals(args.getString("phase"))) {
                shell("appops set dev.beampro.adbtest SYSTEM_ALERT_WINDOW allow");
                consent(true);
                getTargetContext().getSharedPreferences("beampro_settings_ui", 0).edit().putBoolean("permissions_reviewed", true).commit();
                current = startActivitySync(incoming(AdbPairingActivity.class));
                waitForIdleSync();
                if ("capture-help".equals(args.getString("phase"))) {
                    runOnMainSync(() -> buttonStarting("Phone setup tips").performClick());
                    waitForIdleSync();
                }
                if ("landscape".equals(args.getString("phase"))) runOnMainSync(this::landscape);
                else { captureScreen(); current = null; }
            } else if ("capture-permissions".equals(args.getString("phase"))) {
                consent(true);
                getTargetContext().getSharedPreferences("unbound-adb", 0).edit().remove("setup-complete").commit();
                getTargetContext().getSharedPreferences("beampro_settings_ui", 0).edit().remove("permissions_reviewed").commit();
                current = startActivitySync(incoming(PermissionSetupActivity.class));
                waitForIdleSync(); current = null; // Leave the screen open for visual QA.
            } else before();
            result.putString("stream", "ADB setup checks passed: " + passed + "\n"); finish(-1, result);
        } catch (Throwable error) {
            android.util.Log.e("AdbSetupChecks", "Setup checks failed", error);
            result.putString("stream", "ADB setup checks failed after " + passed + ": " + error + "\n"); finish(0, result);
        } finally { if (current != null) runOnMainSync(current::finish); }
    }
    private Intent incoming(Class<?> target) {
        return new Intent(getTargetContext(), target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setAction("dev.beampro.TEST_USB_START").setData(android.net.Uri.parse("unbound-test://glasses"))
                .putExtra("connection_marker", 73).putExtra("dev.beampro.settings.SETUP_PASSED", true);
    }
    private void consent(boolean accepted) {
        require(getTargetContext().getSharedPreferences("beampro_disclaimer", 0).edit().putBoolean("accepted_v1", accepted).commit(), "consent saved");
    }
    private void landscape() {
        View root = current.findViewById(android.R.id.content);
        boolean wide = dev.beampro.settings.AdaptiveLayout.expanded(current, root.getWidth());
        View overview = root.findViewWithTag("landscape-page-overview");
        ScrollView details = (ScrollView) root.findViewWithTag("landscape-page-details");
        require(overview.isShown() == wide, "ADB overview adapts to available width and text size");
        if (wide) {
            int[] left = new int[2], right = new int[2]; overview.getLocationOnScreen(left); details.getLocationOnScreen(right);
            require(left[0] + overview.getWidth() < right[0], "connection status sits beside instructions");
        }
        View footer = root.findViewWithTag("landscape-page-actions");
        android.graphics.Rect visible = new android.graphics.Rect();
        require(footer.getGlobalVisibleRect(visible) && visible.height() == footer.getHeight(), "Continue fits the landscape window");
        details.scrollTo(0, 10000);
        require(footer.getGlobalVisibleRect(visible) && visible.height() == footer.getHeight(), "Continue remains visible while instructions scroll");
        require(!buttonStarting("Continue").isEnabled(), "layout cannot bypass the live-helper requirement");
    }
    private void captureScreen() throws Exception {
        waitForIdleSync(); SystemClock.sleep(250);
        try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getFilesDir(), "landscape-capture.png"))) {
            android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
            require(screenshot != null, "real setup screenshot available");
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file); screenshot.recycle();
        }
    }
    private void before() throws Exception {
        getTargetContext().getSharedPreferences("unbound-adb", 0).edit().clear().commit();
        getTargetContext().getSharedPreferences("beampro_settings_ui", 0).edit().remove("permissions_reviewed").commit();
        shell("appops set dev.beampro.adbtest SYSTEM_ALERT_WINDOW deny");
        consent(false);
        ActivityMonitor disclaimer = addMonitor(PermissionSetupActivity.class.getName(), null, false);
        startActivitySync(incoming(SetupEntryActivity.class).putExtra("dev.beampro.settings.REVIEW_PERMISSIONS", true));
        current = waitForMonitorWithTimeout(disclaimer, 4000); removeMonitor(disclaimer);
        require(current != null, "startup opens disclaimer before pairing"); waitForIdleSync();
        runOnMainSync(() -> {
            require(text("Before you begin") != null, "disclaimer is first");
            require(text("App permissions") == null && text("Connect this phone") == null, "no setup controls before acceptance");
            require(!current.getIntent().getBooleanExtra("dev.beampro.settings.REVIEW_PERMISSIONS", false), "incoming review extra cannot bypass first-run ADB");
        });
        long until = SystemClock.elapsedRealtime() + 11500;
        while (SystemClock.elapsedRealtime() < until) {
            final boolean[] enabled = {false}; runOnMainSync(() -> enabled[0] = buttonStarting("I accept").isEnabled());
            if (enabled[0]) break; SystemClock.sleep(100);
        }
        runOnMainSync(() -> buttonStarting("I accept").performClick());
        waitForIdleSync();
        runOnMainSync(() -> {
            require(text("App permissions") != null && text("Display over other apps") != null, "permissions follow disclaimer");
            require(text("01  PERMISSIONS") != null && text("Connect this phone") == null, "permissions are step one");
            require(!buttonStarting("Continue to ADB").isEnabled(), "overlay access required before ADB");
            buttonStarting("Continue to ADB").performClick();
            require(!PermissionSetupActivity.hasReviewedPermissions(current), "programmatic click cannot bypass overlay access");
            verifyIntent();
        });
        shell("appops set dev.beampro.adbtest SYSTEM_ALERT_WINDOW allow");
        ActivityMonitor permissions = addMonitor(PermissionSetupActivity.class.getName(), null, false);
        runOnMainSync(current::recreate);
        current = waitForMonitorWithTimeout(permissions, 4000); removeMonitor(permissions);
        require(current != null, "permissions refresh after granting overlay access"); waitForIdleSync();
        ActivityMonitor adb = addMonitor(AdbPairingActivity.class.getName(), null, false);
        runOnMainSync(() -> buttonStarting("Continue to ADB").performClick());
        current = waitForMonitorWithTimeout(adb, 4000); removeMonitor(adb);
        require(current != null, "permissions continue to on-device ADB"); waitForIdleSync();
        runOnMainSync(() -> {
            require(text("Connect this phone") != null, "guided ADB screen displayed");
            require(text("02  CONNECTION") != null, "ADB is step two after permissions");
            require(PermissionSetupActivity.hasReviewedPermissions(current), "permission review is saved");
            require(current.getWindow().getStatusBarColor() == Color.BLACK && current.getWindow().getNavigationBarColor() == Color.BLACK, "OLED system bars");
            require(!buttonStarting("Continue").isEnabled(), "cannot continue without a live helper");
            buttonStarting("Continue").performClick();
            require(AdbBootstrap.needsSetup(current), "programmatic click cannot complete disconnected setup");
            require(!field("code").isShown(), "manual options start collapsed");
            current.findViewById(android.R.id.content).findViewWithTag("adb-advanced-toggle").performClick();
            require(field("code").isShown(), "split-screen fallback can be expanded");
            field("pairingPort").setText("65536"); require(AdbPairingActivity.port(field("pairingPort")) == -1, "reject invalid port");
            field("pairingPort").setText(""); require(AdbPairingActivity.port(field("pairingPort")) == 0, "empty port uses detection");
            field("code").setText("1234567"); require(field("code").length() == 6, "six-digit code limit");
            require(!field("code").isSaveEnabled(), "pairing code excluded from saved state");
            verifyIntent();
        });
        ActivityMonitor recreated = addMonitor(AdbPairingActivity.class.getName(), null, false);
        runOnMainSync(current::recreate); current = waitForMonitorWithTimeout(recreated, 4000); removeMonitor(recreated);
        require(current != null, "pairing recreation succeeds"); waitForIdleSync();
        runOnMainSync(() -> { require(field("code").isShown(), "advanced disclosure survives recreation"); require(field("code").length() == 0, "code cleared on recreation"); });
        runOnMainSync(current::finish); current = null;

        // Notification entry with no SETUP extra still offers the onboarding continuation.
        current = startActivitySync(incoming(AdbPairingActivity.class)); waitForIdleSync();
        runOnMainSync(() -> require(buttonStarting("Continue") != null, "notification return retains first-run flow"));
        runOnMainSync(current::finish); current = null;
        // Reviewing permissions later is an explicit action independent of the first-run gate.
        current = startActivitySync(incoming(PermissionSetupActivity.class).putExtra("dev.beampro.settings.REVIEW_PERMISSIONS", true));
        waitForIdleSync(); runOnMainSync(() -> require(text("App permissions") != null, "explicit permission review remains accessible"));
        runOnMainSync(current::finish); current = null;
        getTargetContext().getSharedPreferences("beampro_settings_ui", 0).edit().remove("permissions_reviewed").commit();
        permissions = addMonitor(PermissionSetupActivity.class.getName(), null, false);
        startActivitySync(incoming(AdbPairingActivity.class));
        current = waitForMonitorWithTimeout(permissions, 4000); removeMonitor(permissions);
        require(current != null, "direct ADB entry cannot bypass first permission review"); waitForIdleSync();
        runOnMainSync(() -> require(text("App permissions") != null, "direct ADB entry shows permissions first"));
        runOnMainSync(current::finish); current = null;
        getTargetContext().getSharedPreferences("beampro_settings_ui", 0).edit().putBoolean("permissions_reviewed", true).commit();
    }

    private void launcher() throws Exception {
        consent(true);
        getTargetContext().getSharedPreferences("unbound-adb", 0).edit().remove("setup-complete").commit();
        getTargetContext().getSharedPreferences("beampro_settings_ui", 0).edit().remove("permissions_reviewed").commit();
        shell("appops set dev.beampro.adbtest SYSTEM_ALERT_WINDOW allow");
        ActivityMonitor permissions=addMonitor(PermissionSetupActivity.class.getName(),null,false);
        Intent icon=new Intent(getTargetContext(),SetupEntryActivity.class).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                .putExtra("connection_marker",73);
        startActivitySync(icon);
        current=waitForMonitorWithTimeout(permissions,4000);removeMonitor(permissions);
        require(current!=null,"launcher icon reaches permissions");waitForIdleSync();
        ActivityMonitor pairing=addMonitor(AdbPairingActivity.class.getName(),null,false);
        runOnMainSync(()->buttonStarting("Continue to ADB").performClick());
        current=waitForMonitorWithTimeout(pairing,4000);removeMonitor(pairing);
        require(current!=null,"launcher-origin Continue opens ADB instead of closing the task");waitForIdleSync();
        runOnMainSync(()->{
            require(text("Connect this phone")!=null,"ADB screen is visible");
            require(!current.isFinishing(),"ADB screen stays open");
            Intent intent=current.getIntent();
            require((intent.getFlags()&(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))==0,"launcher task flags are removed");
            require(!Intent.ACTION_MAIN.equals(intent.getAction()),"home-screen action is not forwarded");
            require(!intent.hasCategory(Intent.CATEGORY_LAUNCHER),"home-screen category is not forwarded");
            require(intent.getIntExtra("connection_marker",0)==73,"connection extras survive the handoff");
            require(!buttonStarting("Continue").isEnabled(),"onboarding still requires a live helper");
        });
    }
    private void ready() throws Exception {
        consent(true);
        getTargetContext().getSharedPreferences("beampro_settings_ui", 0).edit().putBoolean("permissions_reviewed", true).commit();
        require(getTargetContext().checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED, "optional camera access is still missing");
        long until = SystemClock.elapsedRealtime() + 30000;
        while (!AdbBootstrap.ready(getTargetContext()) && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
        require(AdbBootstrap.ready(getTargetContext()), "real shell helper Binder is alive");
        getTargetContext().getSharedPreferences("unbound-adb", 0).edit().remove("setup-complete").commit();
        current = startActivitySync(incoming(AdbPairingActivity.class)); waitForIdleSync();
        getTargetContext().getSharedPreferences("nebula_app", 0).edit().clear().commit();
        ActivityMonitor terms = addMonitor(PermissionSetupActivity.class.getName(), null, false);
        runOnMainSync(() -> {
            require(text("READY FOR AR SPACE") != null, "live helper produces Ready state");
            require(buttonStarting("Continue").isEnabled(), "live helper enables continuation");
            buttonStarting("Continue").performClick();
        });
        current = waitForMonitorWithTimeout(terms, 4000); removeMonitor(terms);
        require(current != null, "Ready continues directly to terms"); waitForIdleSync();
        runOnMainSync(() -> {
            require(text("Terms of service") != null && buttonStarting("Agree and finish") != null, "terms follow ADB inline");
            require(!TermsConsent.hasAccepted(current), "ADB completion does not accept terms"); verifyIntent();
        });
        ActivityMonitor launcher = addMonitor(SetupEntryActivity.class.getName(), null, false);
        runOnMainSync(() -> buttonStarting("Agree and finish").performClick());
        current = waitForMonitorWithTimeout(launcher, 4000); removeMonitor(launcher);
        require(current != null, "agreement enters launcher without reopening the icon"); waitForIdleSync();
        runOnMainSync(() -> {
            require(text("Launcher ready") != null && text("App permissions") == null, "optional permissions do not repeat after ADB");
            require(!AdbBootstrap.needsSetup(current), "completion is saved durably"); verifyIntent();
        });
        runOnMainSync(current::finish); current = null;
        current = startActivitySync(incoming(AdbPairingActivity.class)); waitForIdleSync();
        runOnMainSync(() -> { require(text("Done") != null, "later settings entry uses recovery UI"); require(text("Continue  →") == null, "later recovery does not repeat onboarding"); });
    }
    private void prepareTerms() throws Exception {
        shell("appops set dev.beampro.adbtest SYSTEM_ALERT_WINDOW allow");
        consent(true);
        getTargetContext().getSharedPreferences("unbound-adb", 0).edit().putBoolean("setup-complete", true).commit();
        getTargetContext().getSharedPreferences("beampro_settings_ui", 0).edit().putBoolean("permissions_reviewed", true).commit();
        getTargetContext().getSharedPreferences("nebula_app", 0).edit().clear().putInt("PpTosConstant_VERSION_TOS", 17).commit();
    }
    private void terms() throws Exception {
        prepareTerms();
        ActivityMonitor terms = addMonitor(PermissionSetupActivity.class.getName(), null, false);
        startActivitySync(incoming(SetupEntryActivity.class));
        current = waitForMonitorWithTimeout(terms, 4000); removeMonitor(terms);
        require(current != null, "relaunch resumes unfinished agreement"); waitForIdleSync();
        runOnMainSync(() -> {
            require(text("03  TERMS") != null, "terms are the third setup step");
            require(!TermsConsent.hasAccepted(current), "displaying terms does not accept them");
            View root = current.findViewById(android.R.id.content);
            View footer = root.findViewWithTag("landscape-page-actions");
            android.graphics.Rect visible = new android.graphics.Rect();
            require(footer.getGlobalVisibleRect(visible) && visible.height() == footer.getHeight(), "agreement action fits the window");
            ((ScrollView) root.findViewWithTag("landscape-page-details")).scrollTo(0, 10000);
            require(footer.getGlobalVisibleRect(visible) && visible.height() == footer.getHeight(), "agreement stays visible while scrolling");
            clickRow(root, "Terms of service"); clickRow(root, "Privacy policy");
            require(ai.nreal.nebula.activity.splash.pptos.PpTosWebHelper.termsOpened == 1
                    && ai.nreal.nebula.activity.splash.pptos.PpTosWebHelper.privacyOpened == 1, "legal rows route to the original documents");
            require(!TermsConsent.hasAccepted(current), "opening documents does not accept terms");
        });
        terms = addMonitor(PermissionSetupActivity.class.getName(), null, false);
        runOnMainSync(current::recreate);
        current = waitForMonitorWithTimeout(terms, 4000); removeMonitor(terms);
        require(current != null, "agreement survives recreation"); waitForIdleSync();
        runOnMainSync(() -> require(buttonStarting("Agree and finish") != null && !TermsConsent.hasAccepted(current), "recreation does not skip agreement"));
        runOnMainSync(current::finish); current = null;
        terms = addMonitor(PermissionSetupActivity.class.getName(), null, false);
        startActivitySync(new Intent(getTargetContext(), SetupEntryActivity.class).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        current = waitForMonitorWithTimeout(terms, 4000); removeMonitor(terms);
        require(current != null, "leaving without agreement resumes terms from icon"); waitForIdleSync();
        ActivityMonitor launcher = addMonitor(SetupEntryActivity.class.getName(), null, false);
        runOnMainSync(() -> buttonStarting("Agree and finish").performClick());
        current = waitForMonitorWithTimeout(launcher, 4000); removeMonitor(launcher);
        require(current != null, "agreement opens launcher in the same flow"); waitForIdleSync();
        runOnMainSync(() -> {
            require(text("Launcher ready") != null && TermsConsent.hasAccepted(current), "agreement is saved before launcher entry");
            require(Intent.ACTION_MAIN.equals(current.getIntent().getAction()), "final icon-origin handoff restores the phone launch action");
            require(getTargetContext().getSharedPreferences("nebula_app", 0).getInt("PpTosConstant_VERSION_TOS", 0) == 17, "existing policy version retained");
            require(ai.nreal.nebula.features.pp_tos.PPToSHelper.getInstance().agreements == 1, "original first-agreement callback runs once");
        });
        runOnMainSync(current::finish); current = null;
        current = startActivitySync(incoming(SetupEntryActivity.class)); waitForIdleSync();
        runOnMainSync(() -> require(text("Launcher ready") != null && text("Terms of service") == null,
                "original acceptance record prevents repeat agreement"));
        runOnMainSync(current::finish); current = null;
        current = startActivitySync(incoming(PermissionSetupActivity.class).putExtra("dev.beampro.settings.REVIEW_PERMISSIONS", true));
        waitForIdleSync(); runOnMainSync(() -> require(text("App permissions") != null, "later permission reviews remain available"));
    }
    private void clickRow(View view, String title) {
        if (view instanceof dev.beampro.settings.Signature.Row) {
            dev.beampro.settings.Signature.Row row = (dev.beampro.settings.Signature.Row) view;
            if (row.title.getText().toString().equals(title)) { row.performClick(); return; }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clickRow(group.getChildAt(i), title);
        }
    }
    private void shell(String command) throws Exception {
        try (java.io.InputStream stream = new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))) {
            while (stream.read() != -1) {}
        }
    }
    private void verifyIntent() {
        require("dev.beampro.TEST_USB_START".equals(current.getIntent().getAction())
                && "unbound-test://glasses".equals(current.getIntent().getDataString())
                && current.getIntent().getIntExtra("connection_marker", 0) == 73, "glasses connection intent survives setup transition");
    }
    private EditText field(String name) {
        try { java.lang.reflect.Field field = AdbPairingActivity.class.getDeclaredField(name); field.setAccessible(true); return (EditText) field.get(current); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private TextView text(String value) { return find(current.findViewById(android.R.id.content), value, false); }
    private Button buttonStarting(String value) { return (Button) find(current.findViewById(android.R.id.content), value, true); }
    private TextView find(View view, String value, boolean prefix) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString();
            if (prefix ? view instanceof Button && text.startsWith(value) : text.equals(value)) return (TextView) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { TextView result = find(group.getChildAt(i), value, prefix); if (result != null) return result; }
        }
        return null;
    }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
}
