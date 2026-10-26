package dev.beampro.settings.preview;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import dev.beampro.settings.SettingsUi;

public final class PermissionsPreviewActivity extends Activity {
    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        LinearLayout root = new LinearLayout(this);
        SettingsPreviewActivity.Row row = new SettingsPreviewActivity.Row(this,
                "Screen capture", "Allow apps to request screen capture in AR Space", true, "capture");
        row.setId(getResources().getIdentifier("ttv", "id", getPackageName()));
        root.addView(row); setContentView(root);
        SettingsUi.install(this);
    }
}
