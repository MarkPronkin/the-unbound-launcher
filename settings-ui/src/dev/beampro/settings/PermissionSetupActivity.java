package dev.beampro.settings;

import static dev.beampro.settings.Signature.*;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** First-launch disclaimer, permission checklist and final terms agreement. */
public class PermissionSetupActivity extends Activity {
    static final String PASSED = "dev.beampro.settings.SETUP_PASSED";
    static final String REVIEW = "dev.beampro.settings.REVIEW_PERMISSIONS";
    private static final String COUNTDOWN_DEADLINE = "disclaimer_countdown_deadline";
    private static final long ACCEPT_DELAY_MS = 10_000;
    private static boolean initialized;
    private static boolean acknowledgedSession;
    private ScrollView scroll;
    private LinearLayout body;
    private boolean leaving;
    private long acceptAfter;
    private Button acceptButton;
    private final Handler countdown = new Handler(Looper.getMainLooper());
    private final Runnable updateCountdown = this::updateAcceptButton;

    public static boolean hasAcceptedDisclaimer(Context context) {
        return context.getSharedPreferences("beampro_disclaimer", MODE_PRIVATE).getBoolean("accepted_v1", false);
    }

    public static boolean hasReviewedPermissions(Context context) {
        return Settings.canDrawOverlays(context)
                && context.getSharedPreferences("beampro_settings_ui", MODE_PRIVATE).getBoolean("permissions_reviewed", false);
    }

    /** The original application initialization creates an overlay before any Activity exists. */
    public static synchronized void initialize(Application application) {
        DebugData.initialize(application);
        AppUpdater.initialize(application);
        if (initialized) return;
        boolean main = application.getPackageName().equals(Application.getProcessName());
        if (main) {
            // Recover the first editor's misplaced file before AR Space can load its catalog.
            try { new AppMenuStore(application); }
            catch (java.io.IOException error) { Log.e("AppMenuStore", "Keeping layout files after recovery error", error); }
        }
        if (main && (!hasAcceptedDisclaimer(application) || AdbEntry.needsSetup(application) || !Settings.canDrawOverlays(application))) return;
        try {
            java.lang.reflect.Method original = application.getClass().getDeclaredMethod("mainProcessInit");
            original.setAccessible(true);
            original.invoke(application);
            initialized = true;
        } catch (NoSuchMethodException preview) {
            if (!application.getPackageName().equals("dev.beampro.settings.preview")) throw new IllegalStateException(preview);
            initialized = true;
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Cannot initialize " + SettingsUi.APP_NAME, error.getCause());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    /** Called immediately after the original Activity's super.onCreate, before its startup logic. */
    public static boolean guard(Activity activity) {
        boolean acknowledged = activity.getIntent().getBooleanExtra(PASSED, false);
        if (activity.getClass().getName().endsWith(".SplashActivity") && !acknowledged) acknowledgedSession = false;
        if (!hasAcceptedDisclaimer(activity) || AdbEntry.needsSetup(activity) || TermsConsent.required(activity) || !Settings.canDrawOverlays(activity)
                || (!acknowledged && !acknowledgedSession && hasMissing(activity))) {
            Intent intent = SetupNavigation.forward(activity, PermissionSetupActivity.class);
            intent.removeExtra(PASSED);
            intent.removeExtra(REVIEW);
            activity.startActivity(intent);
            activity.finish();
            return true;
        }
        initialize(activity.getApplication());
        return false;
    }

    public static void review(Activity activity) {
        MenuTransitions.open(activity, new Intent(activity, PermissionSetupActivity.class).putExtra(REVIEW, true));
    }

    @Override public void onCreate(Bundle state) {
        MenuActivity.applyTheme(this);
        super.onCreate(state);
        if (state != null) acceptAfter = state.getLong(COUNTDOWN_DEADLINE, 0);
        // Initialize while this Activity is being created so the original legal-link
        // loader receives onResume and has a live owner before either link is tapped.
        if (hasAcceptedDisclaimer(this) && !AdbEntry.needsSetup(this) && Settings.canDrawOverlays(this)) initialize(getApplication());
        if (hasAcceptedDisclaimer(this) && !AdbEntry.needsSetup(this) && !getIntent().getBooleanExtra(REVIEW, false)
                && !TermsConsent.required(this)
                && (!hasMissing(this) || (getIntent().getBooleanExtra(PASSED, false) && hasReviewedPermissions(this)))) { proceed(); return; }
        render();
    }
    @Override public void onResume() { super.onResume(); if (!leaving) render(); }
    @Override public void onPause() { countdown.removeCallbacks(updateCountdown); super.onPause(); }
    @Override public void onDestroy() { countdown.removeCallbacks(updateCountdown); super.onDestroy(); }
    @Override public void onSaveInstanceState(Bundle state) {
        state.putLong(COUNTDOWN_DEADLINE, acceptAfter);
        super.onSaveInstanceState(state);
    }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); leaving = false; render(); }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); render(); }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results); render();
    }

    void proceed() {
        if (!hasAcceptedDisclaimer(this) || !Settings.canDrawOverlays(this)) return;
        if (!getIntent().getBooleanExtra(REVIEW, false) && AdbEntry.needsSetup(this)) {
            if (!getSharedPreferences("beampro_settings_ui", MODE_PRIVATE).edit().putBoolean("permissions_reviewed", true).commit()) {
                Toast.makeText(this, "Could not save setup. Please try again.", Toast.LENGTH_LONG).show(); return;
            }
            if (AdbEntry.setup(this)) { leaving = true; finish(); }
            return;
        }
        if (!getIntent().getBooleanExtra(REVIEW, false) && TermsConsent.required(this)) { render(); return; }
        initialize(getApplication());
        acknowledgedSession = true;
        leaving = true;
        if (!getIntent().getBooleanExtra(REVIEW, false)) {
            Intent intent = SetupNavigation.launcher(this);
            intent.removeExtra(REVIEW);
            intent.putExtra(PASSED, true);
            startActivity(intent);
            AdbEntry.completed(this);
        }
        finish();
    }

    public static final class Access {
        public final String key, title, description;
        public final String[] permissions;
        public final boolean granted, required;
        Access(String key, String title, String description, boolean granted, boolean required, String... permissions) {
            this.key=key; this.title=title; this.description=description; this.granted=granted; this.required=required; this.permissions=permissions;
        }
    }

    public static List<Access> accesses(Context context) {
        List<Access> items = new ArrayList<>();
        Set<String> declared = new HashSet<>();
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) declared.addAll(Arrays.asList(info.requestedPermissions));
        } catch (PackageManager.NameNotFoundException error) { throw new IllegalStateException(error); }
        items.add(new Access("overlay", "Display over other apps", "Required for floating controls and spatial app windows.", Settings.canDrawOverlays(context), true));
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (declared.contains(Manifest.permission.POST_NOTIFICATIONS)) {
            items.add(new Access("notifications", "Notifications", "Allow " + SettingsUi.APP_NAME + " to show connection and service notifications.", notifications.areNotificationsEnabled(), false,
                    Build.VERSION.SDK_INT >= 33 ? new String[]{Manifest.permission.POST_NOTIFICATIONS} : new String[0]));
        }
        if (declared.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES)) {
            items.add(new Access("install", "Install app updates", "Allow updates from " + SettingsUi.APP_NAME + ". Each installation still needs your approval.", context.getPackageManager().canRequestPackageInstalls(), false));
        }
        ComponentName listener = new ComponentName(context.getPackageName(), "com.xreal.evapro.NotifyService");
        try {
            context.getPackageManager().getServiceInfo(listener, 0);
            items.add(new Access("notification_access", "Notification access", "Optional: show notifications from other apps in AR Space.", notifications.isNotificationListenerAccessGranted(listener), false));
        } catch (PackageManager.NameNotFoundException ignored) {}
        if (Build.VERSION.SDK_INT >= 31 && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            runtime(items, context, declared, "nearby", "Nearby devices", "Optional: connect paired Bluetooth controllers and input devices.", Manifest.permission.BLUETOOTH_CONNECT);
        }
        runtime(items, context, declared, "camera", "Camera", "Optional: camera features and camera sharing.", Manifest.permission.CAMERA);
        runtime(items, context, declared, "microphone", "Microphone", "Optional: voice and audio recording features.", Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            runtime(items, context, declared, "media", "Photos, videos and audio", "Optional: open media stored on your phone.", Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO);
        } else runtime(items, context, declared, "media", "Files and media", "Optional: open media stored on your phone.", Manifest.permission.READ_EXTERNAL_STORAGE);
        // Android 11+ keeps full storage behind a special-access page instead of a runtime prompt.
        if (Build.VERSION.SDK_INT >= 30 && declared.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE)) {
            items.add(new Access("storage", "All files access", "Optional: let photo and video player apps open any file on your phone.", Environment.isExternalStorageManager(), false));
        }
        if (declared.contains(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)) {
            items.add(new Access("battery", "Background activity", "Optional: keep glasses services available when the phone is idle.", context.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(context.getPackageName()), false));
        }
        return items;
    }
    static void runtime(List<Access> items, Context context, Set<String> declared, String key, String title, String description, String... permissions) {
        List<String> applicable = new ArrayList<>();
        boolean granted = true;
        for (String permission : permissions) if (declared.contains(permission)) {
            applicable.add(permission);
            granted &= context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }
        if (!applicable.isEmpty()) items.add(new Access(key, title, description, granted, false, applicable.toArray(new String[0])));
    }
    public static boolean hasMissing(Context context) {
        for (Access access : accesses(context)) if (!access.granted) return true;
        return false;
    }

    public static Intent settingsIntent(Context context, String key) {
        Uri packageUri = Uri.parse("package:" + context.getPackageName());
        switch (key) {
            case "overlay": return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri);
            case "install": return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri);
            case "notifications": return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            case "notification_access":
                if (Build.VERSION.SDK_INT >= 30) return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, new ComponentName(context.getPackageName(), "com.xreal.evapro.NotifyService").flattenToString());
                return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            case "battery": return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri);
            case "storage": return new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri);
            default: return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri);
        }
    }
    void openSettings(Access access) {
        if (!hasAcceptedDisclaimer(this)) return;
        try { startActivity(settingsIntent(this, access.key)); }
        catch (android.content.ActivityNotFoundException | SecurityException error) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
            catch (android.content.ActivityNotFoundException fallback) { Toast.makeText(this, "Open Android Settings → Apps → " + SettingsUi.APP_NAME + " → Permissions", Toast.LENGTH_LONG).show(); }
        }
    }
    void allow(Access access) {
        if (!hasAcceptedDisclaimer(this)) return;
        if (access.permissions.length == 0 || access.granted) { openSettings(access); return; }
        List<String> missing = new ArrayList<>();
        boolean requested = getPreferences(0).getBoolean("requested_" + access.key, false);
        for (String permission : access.permissions) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            if (requested && !shouldShowRequestPermissionRationale(permission)) { openSettings(access); return; }
            missing.add(permission);
        }
        if (missing.isEmpty()) { openSettings(access); return; }
        getPreferences(0).edit().putBoolean("requested_" + access.key, true).apply();
        requestPermissions(missing.toArray(new String[0]), 41);
    }

    void render() {
        if (leaving) return;
        boolean review = getIntent().getBooleanExtra(REVIEW, false);
        if (hasAcceptedDisclaimer(this) && hasReviewedPermissions(this) && !review && AdbEntry.needsSetup(this)) {
            if (AdbEntry.setup(this)) { leaving = true; finish(); return; }
        }
        countdown.removeCallbacks(updateCountdown);
        acceptButton = null;
        int previous = scroll == null ? 0 : scroll.getScrollY();
        window(getWindow());
        // Reviews return to settings; first-run steps have nowhere to go back to.
        AdaptiveLayout.DetailPage page = new AdaptiveLayout.DetailPage(this, review
                ? back(this, "Back to settings", v -> { if (Settings.canDrawOverlays(this)) proceed(); else finish(); })
                : brand(this));
        scroll = page.scroll;
        body = page.body;
        if (!hasAcceptedDisclaimer(this)) { renderDisclaimer(page); return; }
        if (!review && !AdbEntry.needsSetup(this) && Settings.canDrawOverlays(this) && TermsConsent.required(this)) {
            renderTerms(page); return;
        }
        boolean needsAdb = !review && AdbEntry.needsSetup(this);
        if (AdbEntry.available() && !review) add(page.intro, stepper(this, 1), 8, 16);
        add(page.intro, display(this, "App permissions"), 8, 0);
        add(page.intro, text(this, needsAdb ? "You're in control. Review access, then connect this phone with on-device ADB."
                : "You're in control. Review access, then continue to your glasses.", BODY, TEXT_SECONDARY), 8, 0);
        List<Access> items = accesses(this);
        int granted = 0;
        boolean ready = true;
        LinearLayout required = group(this), optional = group(this);
        for (Access access : items) {
            if (access.granted) granted++;
            else if (access.required) ready = false;
            (access.required ? required : optional).addView(permissionRow(access));
        }
        Status summary = new Status(this, BODY);
        summary.set(granted + " of " + items.size() + " allowed", ready ? POSITIVE : TEXT_TERTIARY, TEXT);
        add(page.intro, summary, 20, 0);
        body.addView(heading(this, "Required"));
        body.addView(required);
        if (optional.getChildCount() > 0) {
            body.addView(heading(this, "Optional"));
            body.addView(optional);
        }
        add(body, text(this, "Glasses USB access is requested when you connect your glasses. Other permissions are optional and can be changed later.",
                CAPTION, TEXT_SECONDARY), 16, 0);
        if (!review) {
            LinearLayout footer = page.footer();
            Button next = button(this, needsAdb ? "Continue to ADB" : "Continue", PRIMARY);
            next.setEnabled(Settings.canDrawOverlays(this));
            if (!next.isEnabled()) {
                TextView hint = text(this, "Allow display over other apps to continue.", CAPTION, TEXT_SECONDARY);
                hint.setGravity(Gravity.CENTER); add(footer, hint, 0, 12);
            }
            next.setOnClickListener(v -> proceed()); add(footer, next, 0, 0);
        }
        setContentView(page.root);
        ScrollView current = scroll; scroll.post(() -> current.scrollTo(0, previous));
    }

    void renderTerms(AdaptiveLayout.DetailPage page) {
        initialize(getApplication());
        add(page.intro, stepper(this, 3), 8, 16);
        add(page.intro, display(this, "Terms of service"), 8, 0);
        add(page.intro, text(this, "One last step. Review the terms and privacy policy before opening your glasses workspace.", BODY, TEXT_SECONDARY), 8, 0);
        Status ready = new Status(this, SUPPORTING);
        ready.set("Phone setup complete", POSITIVE, TEXT);
        add(page.intro, ready, 24, 0);
        page.body.addView(heading(this, "Review the documents"));
        LinearLayout legal = group(this);
        legal.addView(new Row(this, "document", "Terms of service", "Read XREAL's terms for the original software")
                .chevron().onClick(v -> TermsConsent.openDocument(this, false)));
        legal.addView(new Row(this, "privacy", "Privacy policy", "Read XREAL's privacy policy")
                .chevron().onClick(v -> TermsConsent.openDocument(this, true)));
        page.body.addView(legal);
        add(page.body, text(this, SettingsUi.APP_NAME + " is an independent, fan-made project built on XREAL's MyGlasses software. These are the original software's terms and privacy policy.", SUPPORTING, TEXT_SECONDARY), 20, 0);
        LinearLayout footer = page.footer();
        TextView agreement = text(this, "By tapping Agree and finish, you agree to the Terms of service and Privacy policy.", CAPTION, TEXT_SECONDARY);
        agreement.setGravity(Gravity.CENTER); add(footer, agreement, 0, 12);
        Button agree = button(this, "Agree and finish  →", PRIMARY);
        agree.setOnClickListener(v -> {
            if (leaving || AdbEntry.needsSetup(this) || !hasAcceptedDisclaimer(this) || !Settings.canDrawOverlays(this)) return;
            if (TermsConsent.accept(this)) proceed();
        });
        add(footer, agree, 0, 0);
        setContentView(page.root);
    }

    void renderDisclaimer(AdaptiveLayout.DetailPage page) {
        if (acceptAfter == 0) acceptAfter = SystemClock.elapsedRealtime() + ACCEPT_DELAY_MS;
        add(page.intro, display(this, "Before you begin"), 8, 0);
        add(page.intro, text(this, "Please read and accept this disclaimer before setting up the app.", BODY, TEXT_SECONDARY), 8, 0);
        LinearLayout notes = group(this);
        notes.addView(note("phone", "Phone compatibility", "This app requires a phone with USB-C DisplayPort Alt Mode support. It will not work on phones without DisplayPort Alt Mode."));
        notes.addView(note("hdcp", "HDCP not supported", "HDCP is not supported. Protected video from streaming apps such as Netflix and HBO will not play in the glasses."));
        Row update = note("update", "Update your glasses", "Update your glasses to the latest firmware using the XREAL update website. The app may not work with older firmware versions.");
        String updater = "XREAL firmware updater ↗\nxreal.com/ota";
        SpannableString label = new SpannableString(updater);
        int address = updater.indexOf('\n') + 1;
        label.setSpan(new ForegroundColorSpan(TEXT_SECONDARY), address, updater.length(), 0);
        label.setSpan(new TypefaceSpan(REGULAR), address, updater.length(), 0);
        TextView link = medium(this, label, BODY, TEXT);
        link.setPadding(0, dp(10), dp(12), dp(10)); link.setMinHeight(dp(48));
        link.setBackground(ripple(this, dp(8))); link.setFocusable(true);
        link.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.xreal.com/ota/"))); }
            catch (android.content.ActivityNotFoundException error) {
                Toast.makeText(this, "Visit https://www.xreal.com/ota/ on a computer to update your glasses.", Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(-2, -2);
        linkParams.topMargin = dp(4); update.labels.addView(link, linkParams);
        notes.addView(update);
        notes.addView(note("info", "Fan-made project", "This is a fan-made project. All rights to the original XREAL software are reserved by XREAL."));
        add(body, notes, 16, 0);

        // Keep acceptance visible while the disclaimer scrolls on small screens or large text sizes.
        acceptButton = button(this, "I accept (10s)", PRIMARY);
        acceptButton.setOnClickListener(v -> acceptDisclaimer());
        add(page.footer(), acceptButton, 0, 0);
        setContentView(page.root);
        updateAcceptButton();
    }

    Row note(String glyph, String title, String description) {
        Row row = new Row(this, glyph, title, description);
        row.setGravity(Gravity.TOP);
        row.title.setAccessibilityHeading(true);
        return row;
    }

    void updateAcceptButton() {
        countdown.removeCallbacks(updateCountdown);
        if (acceptButton == null || leaving) return;
        long remaining = Math.max(0, acceptAfter - SystemClock.elapsedRealtime());
        acceptButton.setEnabled(remaining == 0);
        acceptButton.setText(remaining == 0 ? "I accept" : "I accept (" + ((remaining + 999) / 1000) + "s)");
        if (remaining > 0) countdown.postDelayed(updateCountdown, Math.min(remaining, 250));
    }

    void acceptDisclaimer() {
        // Enforce the wait here too, independent of the button's current enabled state.
        if (acceptAfter == 0 || SystemClock.elapsedRealtime() < acceptAfter || hasAcceptedDisclaimer(this)) return;
        if (!getSharedPreferences("beampro_disclaimer", MODE_PRIVATE).edit().putBoolean("accepted_v1", true).commit()) {
            Toast.makeText(this, "Could not save acceptance. Please try again.", Toast.LENGTH_LONG).show(); return;
        }
        countdown.removeCallbacks(updateCountdown);
        acceptButton = null; scroll = null;
        if (!getIntent().getBooleanExtra(REVIEW, false) && !AdbEntry.needsSetup(this) && !hasMissing(this)) proceed();
        else render();
    }

    View permissionRow(Access access) {
        Row row = new Row(this, glyph(access.key), access.title, access.description);
        row.setGravity(Gravity.TOP);
        LinearLayout actions = Signature.row(this);
        Status state = new Status(this, SUPPORTING);
        state.set(access.granted ? "Allowed" : access.required ? "Required" : "Not allowed",
                access.granted ? POSITIVE : access.required ? TEXT : TEXT_TERTIARY,
                access.required && !access.granted ? TEXT : TEXT_SECONDARY);
        actions.addView(state, new LinearLayout.LayoutParams(0, -2, 1));
        // Missing access gets the white action; granted access only offers a way to manage it.
        Button action = button(this, access.granted ? "Settings" : access.permissions.length > 0 ? "Allow" : "Open settings",
                access.granted ? SECONDARY : PRIMARY, true);
        action.setContentDescription((access.granted ? "Settings for " : "Allow ") + access.title);
        action.setOnClickListener(v -> allow(access));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
        actionParams.setMarginStart(dp(12)); actions.addView(action, actionParams);
        add(row.labels, actions, 4, 0);
        return row;
    }

    static String glyph(String key) {
        switch (key) {
            case "overlay": return "overlay";
            case "notifications": case "notification_access": return "notifications";
            case "install": return "download";
            case "nearby": return "bluetooth";
            case "camera": return "camera";
            case "microphone": return "mic";
            case "media": return "image";
            case "storage": return "folder";
            case "battery": return "battery";
            default: return "permissions";
        }
    }

    int dp(float value) { return Signature.dp(this, value); }
}
