package dev.beampro.settings;

import static dev.beampro.settings.Signature.*;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A full-screen secondary page, retaining the original settings/legal callbacks. */
public final class AboutPage extends Dialog {
    private final Activity activity;
    private final Runnable privacy, terms;
    private ScrollView scroll;
    private LinearLayout body;

    AboutPage(Activity activity, Runnable privacy, Runnable terms) {
        super(activity, android.R.style.Theme_Material_NoActionBar);
        this.activity = activity;
        this.privacy = privacy;
        this.terms = terms;
        setOwnerActivity(activity);
        setTitle("About");
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        window.setLayout(-1, -1);
        window.setBackgroundDrawable(new ColorDrawable(BACKGROUND));
        window(window);
    }

    void render() {
        int previousScroll = scroll == null ? 0 : scroll.getScrollY();
        View back = back(activity, "Back", v -> dismiss());
        back.setTag("about-back");
        AdaptiveLayout.DetailPage page = new AdaptiveLayout.DetailPage(activity, back);
        page.root.setContentDescription("About");
        scroll = page.scroll;
        body = page.body;
        add(page.intro, display(activity, "About"), 8, 0);

        LinearLayout identity = Signature.row(activity);
        ImageView mark = new ImageView(activity);
        mark.setImageDrawable(GlassesArtwork.load(activity, "brand-mark.png"));
        mark.setColorFilter(TEXT, android.graphics.PorterDuff.Mode.SRC_IN);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        identity.addView(mark, new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)));
        TextView name = Signature.text(activity, SettingsUi.APP_NAME, HEADLINE, TEXT);
        name.setAccessibilityHeading(true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
        nameParams.setMarginStart(dp(activity, 16)); identity.addView(name, nameParams);
        add(page.intro, identity, 28, 0);
        TextView description = Signature.text(activity, "A fan-made project that extends the features and device support of the original MyGlasses software.", 15, TEXT);
        add(page.intro, description, 20, 0);
        TextView rights = Signature.text(activity, "This project is independent and is not affiliated with or endorsed by XREAL. All rights to the original XREAL software remain with XREAL.", SUPPORTING, TEXT_SECONDARY);
        add(page.intro, rights, 12, 0);

        body.addView(heading(activity, "Versions"));
        LinearLayout versions = group(activity);
        versions.addView(version("App version", AppVersion.NAME));
        versions.addView(version("Base app version", "MyGlasses " + AppVersion.BASE_NAME));
        body.addView(versions);
        body.addView(heading(activity, "Legal"));
        LinearLayout legal = group(activity);
        legal.addView(new Row(activity, "privacy", "Privacy policy", null).chevron().onClick(v -> privacy.run()));
        legal.addView(new Row(activity, "document", "Terms of service", null).chevron().onClick(v -> terms.run()));
        body.addView(legal);
        setContentView(page.root);
        scroll.post(() -> scroll.scrollTo(0, previousScroll));
    }

    private Row version(String title, String value) {
        Row row = new Row(activity, null, title, value);
        row.subtitle.setTextIsSelectable(true);
        return row;
    }
}
