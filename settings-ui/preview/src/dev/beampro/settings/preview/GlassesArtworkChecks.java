package dev.beampro.settings.preview;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import dev.beampro.settings.SettingsUi;
import com.xreal.glassesdisplayplugevent.GlassesInitSetting;
import java.io.InputStream;

/** Exercise actual photo pixels through stale getters, swaps, unknown models and unplug. */
public final class GlassesArtworkChecks extends Instrumentation {
    int passed;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            getTargetContext().getSharedPreferences("beampro_settings_ui", 0).edit().remove("last_glasses_type").commit();
            GlassesInitSetting.previewType = 7;
            SettingsPreviewActivity activity = open(false, 0);
            waitForIdleSync();
            runOnMainSync(() -> {
                requirePhoto(activity, "XREAL One", "widget_one");
                activity.getSharedPreferences("beampro_settings_ui", 0).edit().putInt("last_glasses_type", 999).commit();
                SettingsUi.refresh(activity);
                requirePhoto(activity, "XREAL One", "widget_one");
                // Opening settings with an already-connected device before any new callback.
                activity.mLlGlassesInfo.setVisibility(View.VISIBLE);
                activity.mBtnGoLauncher.setVisibility(View.VISIBLE);
                GlassesInitSetting.previewType = 6;
                SettingsUi.refresh(activity);
                requirePhoto(activity, "XREAL One Pro", "widget_one_pro");
                int writes = activity.mSystemNotificationView.changes;
                // Keep the SDK getter stale throughout these real connection notifications.
                GlassesInitSetting.previewType = 7;
                int[] types = {2,3,4,5,6,7,9,10,11,20};
                String[] names = {"XREAL Air","XREAL Air 2 Pro","XREAL Air 2","XREAL Air 2 Ultra","XREAL One Pro","XREAL One","XREAL 1S","xbx a01","xbx a01+","XREAL 1 Pro+"};
                String[] pictures = {"widget_air","ota_air2pro","ota_air2","widget_flora","widget_one_pro","widget_one","widget_1s","ota_xbx_a01","ota_xbx_a01","ota_one_pro_plus"};
                for (int i=0; i<types.length; i++) {
                    activity.setConnectedModel(types[i]);
                    requirePhoto(activity, names[i], pictures[i]);
                    SettingsUi.refresh(activity);
                    requirePhoto(activity, names[i], pictures[i]);
                }
                for (int unknown : new int[]{0,-1,999}) {
                    activity.setConnectedModel(unknown);
                    requireHidden(activity, "unidentified connected model " + unknown);
                    SettingsUi.refresh(activity);
                    requireHidden(activity, "stale getter cannot identify model " + unknown);
                }
                require(activity.getSharedPreferences("beampro_settings_ui", 0).getInt("last_glasses_type", 0) == 20, "unknown connection cannot overwrite recognized offline photo");
                activity.setConnectedModel(7);
                requirePhoto(activity, "XREAL One", "widget_one");
                activity.setConnected(false);
                requirePhoto(activity, "XREAL One", "widget_one");
                require(!SettingsUiChecks.findText(activity.findViewById(android.R.id.content), "Connect your glasses").isEnabled(), "unplug keeps launch disabled");
                activity.setConnectedModel(6);
                requirePhoto(activity, "XREAL One Pro", "widget_one_pro");
                require(activity.mSystemNotificationView.changes == writes, "model changes never write other settings");
            });
            runOnMainSync(activity::finish);
            // A synchronous SDK notification can arrive before the replacement UI is installed.
            SettingsPreviewActivity early = open(true, 4);
            waitForIdleSync();
            runOnMainSync(() -> requirePhoto(early, "XREAL Air 2", "ota_air2"));
            runOnMainSync(early::finish);
            SettingsPreviewActivity offline = open(false, 0);
            waitForIdleSync();
            runOnMainSync(() -> requirePhoto(offline, "XREAL Air 2", "ota_air2"));
            runOnMainSync(offline::finish);
            result.putString("stream", "Glasses artwork runtime checks passed: " + passed + "\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "Glasses artwork check failed after " + passed + ": " + error + "\n");
            finish(0, result);
        } finally { GlassesInitSetting.previewType = 7; }
    }
    SettingsPreviewActivity open(boolean connected, int type) {
        return (SettingsPreviewActivity)startActivitySync(new Intent(getTargetContext(), SettingsPreviewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("connected", connected).putExtra("glasses_type", type));
    }
    ImageView photo(SettingsPreviewActivity activity) { return activity.findViewById(0x00bea003); }
    void requirePhoto(SettingsPreviewActivity activity, String name, String asset) {
        ImageView photo = photo(activity);
        require(SettingsUiChecks.findText(activity.findViewById(android.R.id.content), name) != null, "model label " + name);
        require(((View)photo.getParent()).getVisibility() == View.VISIBLE && photo.getDrawable() instanceof BitmapDrawable, "photo visible for " + name);
        try (InputStream stream = activity.getAssets().open("settings/" + asset + ".webp");
                InputStream originalStream = activity.getAssets().open("settings/" + asset + ".webp")) {
            Bitmap expected = BitmapFactory.decodeStream(stream);
            require(((BitmapDrawable)photo.getDrawable()).getBitmap().sameAs(expected), "exact original photo pixels for " + name);
            require(photo.getColorFilter() == null && photo.getImageAlpha() == 255 && photo.getAlpha() == 1f,
                    "original brightness and opacity for " + name);
            // Source pixels alone miss ImageView filters, such as the former 1.8x gain.
            // Model swaps happen in this same main-thread callback; settle the new scale type.
            photo.layout(photo.getLeft(), photo.getTop(), photo.getRight(), photo.getBottom());
            ImageView reference = new ImageView(activity);
            reference.setImageDrawable(Drawable.createFromStream(originalStream, asset));
            reference.setScaleType(photo.getScaleType());
            reference.layout(0, 0, photo.getWidth(), photo.getHeight());
            Bitmap rendered = render(photo), original = render(reference);
            require(rendered.sameAs(original), "rendered photo matches original widget/device art for " + name);
            rendered.recycle(); original.recycle();
            expected.recycle();
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        require(photo.getScaleType() == (asset.startsWith("widget_") ? ImageView.ScaleType.FIT_XY : ImageView.ScaleType.FIT_CENTER), "photo proportions for " + name);
    }
    Bitmap render(ImageView image) {
        Bitmap output = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(android.graphics.Color.BLACK);
        image.draw(canvas);
        return output;
    }
    void requireHidden(SettingsPreviewActivity activity, String reason) {
        ImageView image = photo(activity);
        require(image.getDrawable() == null && ((View)image.getParent()).getVisibility() == View.GONE, "no guessed or stale photo: " + reason);
        require(SettingsUiChecks.findText(activity.findViewById(android.R.id.content), "Your glasses") != null, "no stale model label: " + reason);
    }
    void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
}
