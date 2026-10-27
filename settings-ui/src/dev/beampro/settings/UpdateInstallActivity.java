package dev.beampro.settings;

import static dev.beampro.settings.Signature.*;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Resumes installation after Android's "allow from this source" settings screen. */
public final class UpdateInstallActivity extends Activity {
    private boolean requested, verifying;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        requested = state != null && state.getBoolean("permission_requested");
        LinearLayout page = column(this);
        page.setBackgroundColor(BACKGROUND);
        int padding = dp(this, 24); page.setPadding(padding, padding, padding, padding);
        page.addView(back(this, "Back", v -> finish()));
        page.addView(display(this, "Install app update"));
        status = text(this, "Verifying the downloaded update…", 16, TEXT_SECONDARY);
        add(page, status, 24, 0);
        setContentView(page); window(getWindow());
    }

    @Override public void onResume() {
        super.onResume();
        if (verifying) return;
        verifying = true;
        android.content.Context context = getApplicationContext();
        new Thread(() -> {
            Exception failure = null;
            try { AppUpdater.verifyApk(context, AppUpdater.readyFile(context)); }
            catch (Exception error) { failure = error; }
            final Exception result = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result != null) { status.setText(result.getMessage()); return; }
                continueInstall();
            });
        }, "unbound-update-verification").start();
    }

    private void continueInstall() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            if (requested) { status.setText("Installation permission was not granted. Return to About and tap Install update to try again."); return; }
            requested = true;
            status.setText("Allow updates from The Unbound Launcher, then return here to continue.");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                verifying = false;
            } catch (RuntimeException error) { status.setText("Open Android Settings and allow this app to install unknown apps, then try again from About."); }
            return;
        }
        try {
            Uri uri = UpdateFileProvider.uri(this);
            Intent install = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.setClipData(ClipData.newRawUri("App update", uri));
            startActivity(install);
            finish();
        } catch (RuntimeException error) { status.setText("Android could not open the package installer. You can try again from About."); }
    }

    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putBoolean("permission_requested", requested);
    }
}
