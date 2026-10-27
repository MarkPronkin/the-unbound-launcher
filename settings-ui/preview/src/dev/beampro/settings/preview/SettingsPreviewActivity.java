package dev.beampro.settings.preview;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import dev.beampro.settings.SettingsUi;

/** Small emulator fixture using the production renderer and the original view contract. */
public class SettingsPreviewActivity extends Activity {
    public LinearLayout mLlRoot;
    public View mLlGlassesInfo;
    public Button mBtnGoLauncher;
    public TextView mTvAppVersion, mTvGlassSN, mTvGlassFw;
    public Row mSystemNotificationView, mCastScreenView, mScreenColorView, mSleepTimeView,
            mPPView, mTosView, mDevOptionView, mForceOtaView;
    public int launches, privacyClicks, termsClicks;
    public Intent externalLaunch;
    public Intent shareLaunch;

    @Override public void startActivity(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction())) { externalLaunch = intent; return; }
        if (Intent.ACTION_CHOOSER.equals(intent.getAction())) { shareLaunch = intent; return; }
        super.startActivity(intent);
    }

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        mLlRoot = new LinearLayout(this); mLlRoot.setOrientation(LinearLayout.VERTICAL);
        mLlGlassesInfo = new View(this); mLlGlassesInfo.setVisibility(View.GONE);
        mBtnGoLauncher = new Button(this); mBtnGoLauncher.setVisibility(View.GONE);
        mBtnGoLauncher.setOnClickListener(v -> launches++);
        mTvGlassSN = new TextView(this); mTvGlassSN.setText("Serial number  XREAL-PREVIEW");
        mTvGlassFw = new TextView(this); mTvGlassFw.setText("Firmware  1.0.0");
        mTvAppVersion = new TextView(this); mTvAppVersion.setText("V3.11.0 (1214)");
        mSystemNotificationView = row("System notifications", "Show phone notifications in AR Space", true, "notifications");
        mCastScreenView = row("Default Air Casting", "", true, "mirroring");
        mScreenColorView = row("Screen color", "Adjust the color of your display", true, "color");
        mScreenColorView.setVisibility(View.GONE);
        mSleepTimeView = row("Sleep timeout", "", false, "sleep"); mSleepTimeView.setVisibility(View.GONE);
        mDevOptionView = row("Developer options", "", false, "dev"); mDevOptionView.setVisibility(View.GONE);
        mForceOtaView = row("Firmware recovery", "", false, "firmware"); mForceOtaView.setVisibility(View.GONE);
        Row permissions = row("authority management", "", false, "permissions");
        permissions.mLlRoot.setOnClickListener(v -> startActivity(new Intent(this, PermissionsPreviewActivity.class)));
        mPPView = row("Privacy policy", "", false, "privacy"); mPPView.mLlRoot.setOnClickListener(v -> privacyClicks++);
        mTosView = row("Terms of service", "", false, "terms"); mTosView.mLlRoot.setOnClickListener(v -> termsClicks++);
        setContentView(mLlRoot);
        if (getIntent().getBooleanExtra("connected", false)) {
            setConnectedModel(getIntent().getIntExtra("glasses_type", com.xreal.glassesdisplayplugevent.GlassesInitSetting.previewType));
        }
        SettingsUi.install(this);
        if (getIntent().getBooleanExtra("install_update", false)) {
            startActivity(new Intent(this, dev.beampro.settings.UpdateInstallActivity.class));
        }
    }

    public void setConnected(boolean value) {
        if (value) { setConnectedModel(com.xreal.glassesdisplayplugevent.GlassesInitSetting.previewType); return; }
        setConnectionViews(false);
        SettingsUi.glassesDisconnected(this);
    }

    public void setConnectedModel(int type) {
        setConnectionViews(true);
        SettingsUi.glassesConnected(this, type);
    }

    private void setConnectionViews(boolean value) {
        mLlGlassesInfo.setVisibility(value ? View.VISIBLE : View.GONE);
        mBtnGoLauncher.setVisibility(value ? View.VISIBLE : View.GONE);
        mScreenColorView.setVisibility(value ? View.VISIBLE : View.GONE);
        mSleepTimeView.setVisibility(value ? View.VISIBLE : View.GONE);
        mSleepTimeView.mTvRightText.setText("10 minutes");
    }
    @Override public void onResume() { super.onResume(); SettingsUi.refresh(this); }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); SettingsUi.refresh(this); }

    Row row(String title, String subtitle, boolean toggle, String key) {
        Row row = new Row(this, title, subtitle, toggle, key);
        mLlRoot.addView(row); return row;
    }

    /** Names and callbacks match the extracted TableTitleView, including its internal root. */
    public static class Row extends FrameLayout {
        public TextView mTvTitle, mTvSubTitle, mTvRightText;
        public LinearLayout mLlRoot, mLlRightSwitch;
        public Switch mIvSwitch;
        public int changes;
        Row(Activity activity, String title, String subtitle, boolean toggle, String key) {
            super(activity);
            mLlRoot = new LinearLayout(activity);
            mTvTitle = new TextView(activity); mTvTitle.setText(title);
            mTvSubTitle = new TextView(activity); mTvSubTitle.setText(subtitle);
            mTvRightText = new TextView(activity);
            mLlRightSwitch = new LinearLayout(activity); mLlRightSwitch.setVisibility(toggle ? VISIBLE : GONE);
            mIvSwitch = new Switch(activity);
            mIvSwitch.setChecked(activity.getPreferences(0).getBoolean(key, key.equals("notifications")));
            mIvSwitch.setOnCheckedChangeListener((button, checked) -> {
                changes++; activity.getPreferences(0).edit().putBoolean(key, checked).apply();
            });
            mLlRoot.addView(mTvTitle); mLlRoot.addView(mTvSubTitle);
            mLlRoot.addView(mTvRightText); mLlRightSwitch.addView(mIvSwitch);
            mLlRoot.addView(mLlRightSwitch); addView(mLlRoot);
        }
        public boolean isChecked() { return mIvSwitch.isChecked(); }
    }
}
