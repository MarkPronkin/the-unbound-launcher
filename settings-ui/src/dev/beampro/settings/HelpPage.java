package dev.beampro.settings;

import static dev.beampro.settings.Signature.*;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Support and opt-in diagnostics share the same secondary-page navigation as About. */
final class HelpPage extends MenuPage {
    private static final String DISCORD_INVITE = "https://discord.gg/J7t64xupfQ";
    private final DebugData data;
    private final Runnable changed = this::refresh;
    private File export;
    private boolean busy;
    private String status;

    HelpPage(Activity activity) {
        super(activity);
        data = DebugData.get(activity);
        export = data.latestExport();
    }
    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(BACKGROUND)); window(window);
        data.observe(changed); refresh();
    }
    @Override protected void onStop() { data.remove(changed); super.onStop(); }

    private void refresh() {
        if (export == null || !export.isFile()) export = data.latestExport();
        render();
    }

    void render() {
        int previousScroll = scrollPosition();
        View back = back(activity, "Back", v -> dismiss()); back.setTag("help-back");
        AdaptiveLayout.DetailPage page = new AdaptiveLayout.DetailPage(activity, back);
        page.root.setContentDescription("Help"); scroll = page.scroll;
        add(page.intro, display(activity, "Help"), 8, 0);
        LinearLayout support = group(activity);
        Row help = new Row(activity, "help", "Get help", "Join our Discord community").chevron().onClick(v -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_INVITE)).addCategory(Intent.CATEGORY_BROWSABLE));
            } catch (ActivityNotFoundException error) { toast("Install a browser or Discord to get help."); }
        });
        help.setTag("get-help"); support.addView(help); add(page.intro, support, 20, 0);

        page.body.addView(heading(activity, "Debugging"));
        LinearLayout options = group(activity);
        options.addView(toggle("Debug mode", "Enable extra app diagnostics to help investigate a problem.",
                "debug-enabled", data.enabled(), true, data::setEnabled));
        options.addView(toggle("Collect debug data", "Start before connecting the glasses, then reproduce the problem. Keeps the first startup failure and recent attempts. Turn off to stop.",
                "debug-collecting", data.collecting(), data.enabled(), data::setCollecting));
        page.body.addView(options);

        page.body.addView(heading(activity, "Debug file"));
        long bytes = data.bytes();
        String description = data.collecting() ? "Collecting app logs. The oldest data is replaced as the log fills up."
                : bytes > 0 ? "Collection is stopped. Your recorded data is ready to export."
                : "Enable debug mode and collect data while you reproduce the problem.";
        if (data.error != null) description = data.error;
        add(page.body, text(activity, description, SUPPORTING, TEXT_SECONDARY), 4, 0);
        add(page.body, text(activity, "Create a debug file while the problem is visible and keep the glasses connected until it is ready. It includes the first failed startup, recent SDK and firmware messages, glasses connection snapshots, display and USB/DisplayPort diagnostics. Retrying does not replace the first failure. Some phones restrict socket, interface and kernel data; unavailable sources are recorded. Files stay on your device until you share them. Logs, network addresses, device/display identifiers and app/window names may contain personal information. Each ZIP is limited to 15 MB.",
                SUPPORTING, TEXT_SECONDARY), 12, 0);

        Button create = button(activity, busy ? "Preparing debug file…" : export == null ? "Create debug file" : "Create new debug file", PRIMARY);
        create.setTag("debug-create"); create.setEnabled(!busy && bytes > 0);
        create.setOnClickListener(v -> prepare()); add(page.body, create, 20, 0);
        if (export != null) {
            TextView file = text(activity, export.getName() + "\n" + String.format(Locale.US, "%.2f MB", export.length() / 1_000_000d),
                    SUPPORTING, TEXT_SECONDARY);
            file.setTextIsSelectable(true); file.setTag("debug-file"); add(page.body, file, 12, 0);
        }
        Button share = button(activity, "Share", SECONDARY);
        share.setTag("debug-share"); share.setEnabled(!busy && export != null && export.isFile());
        share.setOnClickListener(v -> {
            try { activity.startActivity(Intent.createChooser(DebugData.share(activity, export), "Share debug file")); }
            catch (ActivityNotFoundException error) { toast("No app is available to share this file. Save it to Downloads instead."); }
            catch (IOException error) { status = error.getMessage(); render(); }
        });
        add(page.body, share, 12, 0);
        Button save = button(activity, "Save to Downloads", SECONDARY);
        save.setTag("debug-save"); save.setEnabled(!busy && export != null && export.isFile());
        save.setOnClickListener(v -> save()); add(page.body, save, 12, 0);
        if (status != null) {
            TextView message = text(activity, status, SUPPORTING, TEXT_SECONDARY);
            message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            add(page.body, message, 12, 0);
        }
        setContentView(page.root);
        scroll.post(() -> scroll.scrollTo(0, previousScroll));
    }

    private Row toggle(String title, String description, String tag, boolean checked, boolean enabled,
                       java.util.function.Consumer<Boolean> change) {
        Row row = new Row(activity, null, title, description);
        SettingsUi.SignatureSwitch toggle = new SettingsUi.SignatureSwitch(activity);
        toggle.setTag(tag); toggle.setContentDescription(title); toggle.setChecked(checked); toggle.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : .45f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(activity, 52), dp(activity, 48));
        params.setMarginStart(dp(activity, 16)); row.addView(toggle, params);
        toggle.setOnCheckedChangeListener((button, value) -> { change.accept(value); render(); });
        row.onClick(v -> { if (toggle.isEnabled()) toggle.toggle(); }); return row;
    }

    private void prepare() {
        busy = true; status = "Preparing logs, USB/DisplayPort, display and network diagnostics. Keep the glasses connected until the file is ready…"; render();
        data.files.execute(() -> {
            File file = null; String error = null;
            try { file = data.export(); }
            catch (IOException | RuntimeException failure) { error = "Could not create debug file: " + failure.getMessage(); }
            final File result = file; final String failure = error;
            activity.runOnUiThread(() -> {
                busy = false;
                if (result != null) export = result;
                status = failure == null ? "Debug file ready to share or save." : failure;
                if (isShowing()) render();
            });
        });
    }

    private void save() {
        final File file = export;
        busy = true; status = "Saving to Downloads…"; render();
        data.files.execute(() -> {
            String message;
            try { data.saveToDownloads(file); message = "Saved to Downloads: " + file.getName(); }
            catch (IOException | RuntimeException failure) { message = "Could not save debug file: " + failure.getMessage(); }
            final String result = message;
            activity.runOnUiThread(() -> { busy = false; status = result; if (isShowing()) render(); });
        });
    }
    private void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
}
