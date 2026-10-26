package dev.beampro.adb;

import static dev.beampro.settings.Signature.*;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import dev.beampro.settings.PermissionSetupActivity;
import dev.beampro.settings.Signature;
import dev.beampro.settings.AdaptiveLayout;
import dev.beampro.settings.SetupNavigation;

/** Guided on-phone setup and recovery, after the app permission checklist. */
public final class AdbPairingActivity extends Activity {
    static final String SETUP = "dev.beampro.adb.SETUP";
    private boolean onboarding, expanded;
    private TextView status, discovered, footerHint, pairNumber, startNumber;
    private Status state;
    private Row advancedToggle;
    private View advancedArrow;
    private EditText code, pairingPort, connectionPort;
    private Button pair, connect, notification, next;
    private LinearLayout advanced;
    private AdbDiscovery discovery;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() { updateState(); handler.postDelayed(this, 700); }
    };

    @Override public void onCreate(Bundle saved) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(saved);
        onboarding = getIntent().getBooleanExtra(SETUP, false) || AdbBootstrap.needsSetup(this);
        expanded = saved != null && saved.getBoolean("advanced");
        // Notification and floating-panel entry points also respect first-run ordering.
        if (!PermissionSetupActivity.hasAcceptedDisclaimer(this)
                || (onboarding && !PermissionSetupActivity.hasReviewedPermissions(this))) {
            Intent disclaimer = SetupNavigation.forward(this, PermissionSetupActivity.class);
            disclaimer.removeExtra(SETUP);
            disclaimer.removeExtra("dev.beampro.settings.SETUP_PASSED");
            disclaimer.removeExtra("dev.beampro.settings.REVIEW_PERMISSIONS");
            startActivity(disclaimer);
            finish(); return;
        }
        window(getWindow());
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        AdaptiveLayout.DetailPage page = new AdaptiveLayout.DetailPage(this, back(this, onboarding ? "Exit setup" : "Back to settings", v -> finish()));
        LinearLayout body = page.body;
        if (onboarding) add(page.intro, stepper(this, 2), 8, 16);
        add(page.intro, display(this, "Connect this phone"), 8, 0);
        add(page.intro, text(this, "On-device ADB powers your AR app windows. Set it up here, with no computer needed.", BODY, TEXT_SECONDARY), 8, 0);

        LinearLayout live = panel(this);
        state = new Status(this, OVERLINE);
        state.label.setTypeface(MEDIUM); state.label.setLetterSpacing(.16f);
        live.addView(state);
        status = text(this, "", SUPPORTING, TEXT_SECONDARY); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(live, status, 10, 0);
        add(page.intro, live, 24, 8);

        LinearLayout wifi = step(body, "1", "Enable Wireless debugging", "Connect to Wi-Fi, then turn on Wireless debugging in Android's Developer options. Approve your network when asked.");
        Button developer = button(this, "Open Developer options", SECONDARY);
        developer.setOnClickListener(v -> openDeveloperOptions(this));
        add(wifi, developer, 16, 0);
        add(wifi, text(this, PhoneSetupHelp.developerHint(Build.MANUFACTURER, Build.BRAND), CAPTION, TEXT_SECONDARY), 12, 0);
        Button phoneHelp = button(this, "Phone setup tips", SECONDARY);
        phoneHelp.setOnClickListener(v -> Signature.show(dialog(this).setTitle("Phone setup tips")
                .setMessage(PhoneSetupHelp.details(Build.MANUFACTURER, Build.BRAND)).setPositiveButton("Done", null)));
        add(wifi, phoneHelp, 12, 0);

        LinearLayout pairing = step(body, "2", "Pair with Unbound", "In Wireless debugging, choose Pair device with pairing code. Keep that dialog open while you enter the code from your notification shade.");
        pairNumber = (TextView) ((LinearLayout) pairing.getParent()).getChildAt(0);
        notification = button(this, "Start pairing", PRIMARY);
        notification.setOnClickListener(v -> requestNotificationPairing());
        add(pairing, notification, 16, 0);
        add(pairing, text(this, "Tap Enter pairing code on the Unbound notification, then send Android's six-digit code.", CAPTION, TEXT_SECONDARY), 12, 0);

        LinearLayout start = step(body, "3", "Start AR app windows", "Pairing starts the connection automatically. If you've paired before, enable Wireless debugging and reconnect here.");
        startNumber = (TextView) ((LinearLayout) start.getParent()).getChildAt(0);
        connect = button(this, "Connect and start", SECONDARY);
        connect.setOnClickListener(v -> {
            int p = port(connectionPort); if (p >= 0) { AdbBootstrap.connect(this, p, discovery); updateState(); }
        });
        add(start, connect, 16, 0);

        LinearLayout other = group(this);
        advancedToggle = new Row(this, null, "Other ways to pair", "Split screen, manual ports or a floating panel");
        advancedToggle.setTag("adb-advanced-toggle");
        advancedArrow = new Icon(this, "expand", TEXT_SECONDARY);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        arrowParams.setMarginStart(dp(12)); advancedToggle.addView(advancedArrow, arrowParams);
        advancedToggle.onClick(v -> { expanded = !expanded; showAdvanced(); });
        other.addView(advancedToggle);
        add(body, other, 12, 0);
        advanced = panel(this); add(body, advanced, 4, 0);
        TextView manual = medium(this, "Pair in split screen", TITLE, TEXT); manual.setAccessibilityHeading(true); add(advanced, manual, 0, 6);
        add(advanced, text(this, "Keep Android's pairing dialog open beside Unbound. Enter its code below; ports are detected automatically when available.", SUPPORTING, TEXT_SECONDARY), 0, 16);
        add(advanced, overline(this, "Pairing code", TEXT_SECONDARY), 0, 8);
        code = input(this, advanced, "Six-digit code", true);
        add(advanced, overline(this, "Pairing port", TEXT_SECONDARY), 16, 8);
        pairingPort = input(this, advanced, "Automatic", false);
        pairingPort.setContentDescription("Pairing port, automatic if empty");
        pair = button(this, "Pair this phone", SECONDARY);
        pair.setOnClickListener(v -> {
            int p = port(pairingPort), c = port(connectionPort); if (p < 0 || c < 0) return;
            String value = code.getText().toString().trim(); code.setText("");
            AdbBootstrap.pair(this, value, p, c, discovery, null); updateState();
        });
        add(advanced, pair, 16, 20);
        divider(advanced);
        add(advanced, medium(this, "Connection port", TITLE, TEXT), 20, 6);
        add(advanced, text(this, "Use the port on the main Wireless debugging screen if connection detection fails. It differs from the pairing port.", SUPPORTING, TEXT_SECONDARY), 0, 12);
        connectionPort = input(this, advanced, "Automatic", false);
        connectionPort.setContentDescription("Connection port, automatic if empty");
        discovered = text(this, "", CAPTION, TEXT_SECONDARY); add(advanced, discovered, 12, 20);
        divider(advanced);
        add(advanced, medium(this, "Floating pairing panel", TITLE, TEXT), 20, 6);
        add(advanced, text(this, "Requires display-over-apps access. Some phones hide panels over Settings; use notification or split-screen pairing on those devices.", SUPPORTING, TEXT_SECONDARY), 0, 16);
        Button floating = button(this, "Open floating panel", SECONDARY);
        floating.setOnClickListener(v -> openFloatingPanel());
        add(advanced, floating, 0, 0);
        showAdvanced();

        body.addView(heading(this, "Your phone. Your connection."));
        add(body, text(this, "Pairing grants Android shell access to Unbound's built-in backend, on this phone only. Revoke it in Wireless debugging → Paired devices.\n\nAfter a reboot, turn Wireless debugging back on and reconnect. Once Ready appears, you can switch it off until the next restart.", SUPPORTING, TEXT_SECONDARY), 0, 8);
        LinearLayout notices = group(this);
        notices.addView(new Row(this, "document", "Open-source notices", "LibADB, SPAKE2, Bouncy Castle and Conscrypt").chevron()
                .onClick(v -> Signature.show(dialog(this)
                        .setTitle("Open-source components").setMessage("LibADB Android 3.1.1 (Apache 2.0)\nSPAKE2 Android 2.2.1 (LGPL 3)\nBouncy Castle 1.81 (MIT)\nConscrypt 2.5.3 (Apache 2.0)\n\nSources, modifications and notices are included with this app.")
                        .setPositiveButton("Done", null))));
        body.addView(notices);

        LinearLayout footer = page.footer();
        footerHint = text(this, "", CAPTION, TEXT_SECONDARY); footerHint.setGravity(Gravity.CENTER); add(footer, footerHint, 0, 12);
        next = button(this, onboarding ? "Finish setup  →" : "Done", PRIMARY);
        next.setOnClickListener(v -> complete());
        add(footer, next, 0, 0);
        setContentView(page.root); updateState();
    }

    private void updateState() {
        if (status == null) return;
        boolean supported = Build.VERSION.SDK_INT >= 30;
        boolean ready = AdbBootstrap.ready(this), paired = AdbBootstrap.paired(this), busy = AdbBootstrap.busy();
        state.set(ready ? "READY FOR AR SPACE" : busy ? "CONNECTING" : paired ? "PHONE PAIRED" : "WAITING FOR SETUP",
                ready ? POSITIVE : busy || paired ? TEXT : TEXT_TERTIARY, TEXT);
        setText(status, supported ? AdbBootstrap.status(this) : "Wireless pairing requires Android 11 or newer.");
        pair.setEnabled(supported && !busy); notification.setEnabled(supported && !busy);
        connect.setEnabled(supported && !busy && !ready);
        setText(notification, paired ? "Pair again" : "Start pairing");
        setText(connect, ready ? "Connected" : "Connect and start");
        badge(pairNumber, "2", paired); badge(startNumber, "3", ready);
        next.setEnabled(!onboarding || ready);
        setText(footerHint, onboarding ? ready ? "Connected. You're ready to use your glasses." : "Connect this phone to finish setup." : "Reconnect here whenever AR app windows need it.");
        String pairs = discovery == null ? "" : discovery.ports(true).toString();
        String connections = discovery == null ? "" : discovery.ports(false).toString();
        setText(discovered, "Detected pairing ports: " + (pairs.isEmpty() || "[]".equals(pairs) ? "none yet" : pairs)
                + "\nDetected connection ports: " + (connections.isEmpty() || "[]".equals(connections) ? "none yet" : connections));
    }
    private void complete() {
        if (!onboarding) { finish(); return; }
        if (!AdbBootstrap.ready(this)) return;
        if (!getSharedPreferences("unbound-adb", 0).edit().putBoolean("setup-complete", true).commit()) {
            Toast.makeText(this, "Could not save setup. Please try again.", Toast.LENGTH_LONG).show(); return;
        }
        Intent permissions = SetupNavigation.forward(this, PermissionSetupActivity.class);
        permissions.removeExtra(SETUP);
        permissions.putExtra("dev.beampro.settings.SETUP_PASSED", true);
        startActivity(permissions);
        finish();
    }
    private void showAdvanced() {
        advanced.setVisibility(expanded ? View.VISIBLE : View.GONE);
        advancedArrow.setRotation(expanded ? 180 : 0);
        advancedToggle.setContentDescription("Other ways to pair, " + (expanded ? "expanded" : "collapsed"));
    }
    private void requestNotificationPairing() {
        if (Build.VERSION.SDK_INT < 30) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7318);
        } else if (!getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()) {
            Toast.makeText(this, "Allow Unbound notifications, or use Other ways to pair.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } else startNotificationPairing();
    }
    private void openFloatingPanel() {
        if (Build.VERSION.SDK_INT < 30) return;
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "Allow display over other apps, then open the pairing panel again.", Toast.LENGTH_LONG).show(); return;
        }
        startForegroundService(new Intent(this, PairingOverlayService.class)); openDeveloperOptions(this);
    }
    @Override protected void onStart() {
        super.onStart(); if (status == null) return;
        if (Build.VERSION.SDK_INT >= 30) discovery = new AdbDiscovery(getApplicationContext());
        handler.post(refresh);
    }
    @Override protected void onStop() {
        handler.removeCallbacks(refresh);
        if (discovery != null) { discovery.close(); discovery = null; }
        if (code != null) code.setText(""); super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putBoolean("advanced", expanded); super.onSaveInstanceState(state); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 7318 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startNotificationPairing();
        else if (request == 7318) {
            expanded = true; showAdvanced();
            Toast.makeText(this, "Notifications are off. Use split screen or the floating panel to pair.", Toast.LENGTH_LONG).show();
        }
    }
    private void startNotificationPairing() {
        if (Build.VERSION.SDK_INT < 30) return;
        startForegroundService(new Intent(this, PairingOverlayService.class).setAction("notification")); openDeveloperOptions(this);
    }
    static void openDeveloperOptions(Context context) {
        try { context.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            try { context.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (ActivityNotFoundException | SecurityException restricted) {
                Toast.makeText(context, "Open Settings manually and search for Developer options. Your phone may restrict access.", Toast.LENGTH_LONG).show();
            }
        }
    }
    static EditText input(Context context, LinearLayout parent, String hint, boolean secret) {
        EditText edit = Signature.input(context, hint);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | (secret ? InputType.TYPE_NUMBER_VARIATION_PASSWORD : 0));
        edit.setTypeface(REGULAR); // Password input types switch to monospace.
        edit.setSaveEnabled(false); edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        if (secret) edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        edit.setContentDescription(secret ? "Six-digit pairing code" : hint.equals("Automatic") ? "Port, automatic if empty" : hint);
        parent.addView(edit, new LinearLayout.LayoutParams(-1, -2)); return edit;
    }
    static int port(EditText input) {
        String value = input.getText().toString().trim(); if (value.isEmpty()) return 0;
        try { int port = Integer.parseInt(value); if (port > 0 && port <= 65535) return port; }
        catch (NumberFormatException ignored) {}
        input.setError("Use a port from 1 to 65535"); return -1;
    }
    /** A numbered instruction; returns the column that holds its text and actions. */
    private LinearLayout step(LinearLayout parent, String number, String title, String description) {
        LinearLayout row = new LinearLayout(this);
        TextView index = medium(this, number, CAPTION, TEXT); index.setGravity(Gravity.CENTER);
        badge(index, number, false);
        row.addView(index, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout text = column(this);
        TextView heading = medium(this, title, TITLE, TEXT);
        heading.setAccessibilityHeading(true); add(text, heading, 3, 6); add(text, text(this, description, SUPPORTING, TEXT_SECONDARY), 0, 0);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
        textParams.setMarginStart(dp(16)); row.addView(text, textParams);
        add(parent, row, 28, 0); return text;
    }
    /** Step numbers sit in an outlined circle that fills in white once the step is done. */
    private void badge(TextView badge, String number, boolean done) {
        // Called on every status poll; only touch the view when the step actually changes.
        if (Boolean.valueOf(done).equals(badge.getTag())) return;
        badge.setTag(done);
        setText(badge, done ? "✓" : number);
        badge.setTextColor(done ? ON_PRIMARY : TEXT);
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL);
        circle.setColor(done ? TEXT : BACKGROUND);
        if (!done) circle.setStroke(dp(1), OUTLINE);
        badge.setBackground(circle);
        badge.setContentDescription("Step " + number + (done ? ", done" : ""));
    }
    private void divider(LinearLayout parent) { View line = new View(this); line.setBackgroundColor(DIVIDER); parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1))); }
    private static void setText(TextView view, String value) { if (!value.contentEquals(view.getText())) view.setText(value); }
    private int dp(float value) { return Signature.dp(this, value); }
}
