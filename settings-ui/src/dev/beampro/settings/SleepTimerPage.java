package dev.beampro.settings;

import static dev.beampro.settings.Signature.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Automatic sleep uses the same native menu, typography and flat rows as other preferences. */
final class SleepTimerPage extends MenuPage {
    private final SleepTimer timer;
    private final Runnable changed = this::refresh;
    private final Row[] choices = new Row[SleepTimer.count()];
    private final Selection[] indicators = new Selection[SleepTimer.count()];
    private TextView status;

    SleepTimerPage(Activity activity) { super(activity); timer = SleepTimer.get(activity); }
    @Override protected void onStart() { timer.observe(changed); refresh(); }
    @Override protected void onStop() { timer.remove(changed); }

    @Override void render() {
        int previousScroll = scrollPosition();
        View back = back(activity, "Back", v -> dismiss());
        back.setTag("sleep-back");
        AdaptiveLayout.DetailPage page = new AdaptiveLayout.DetailPage(activity, back);
        page.root.setContentDescription("Automatic sleep");
        scroll = page.scroll;
        add(page.intro, display(activity, "Automatic sleep"), 8, 0);
        add(page.intro, text(activity, "Choose when your glasses go to sleep.", BODY, TEXT_SECONDARY), 8, 0);
        page.body.addView(heading(activity, "Sleep after"));
        LinearLayout options = group(activity);
        for (int i = 0; i < choices.length; i++) {
            final int index = i;
            Row row = new Row(activity, null, SleepTimer.label(i), null);
            row.setTag("sleep-choice-" + i);
            row.setContentDescription(SleepTimer.label(i));
            row.labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(view, info);
                    info.setClassName("android.widget.RadioButton");
                    info.setCheckable(true);
                    info.setChecked(timer.selected() == index);
                }
            });
            Selection indicator = new Selection(activity);
            indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(indicator, new LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24)));
            row.onClick(v -> {
                try { timer.select(index); }
                catch (IllegalStateException error) { Toast.makeText(activity, error.getMessage(), Toast.LENGTH_LONG).show(); }
            });
            choices[i] = row; indicators[i] = indicator;
            options.addView(row);
        }
        page.body.addView(options);
        LinearLayout note = Signature.row(activity);
        Icon icon = new Icon(activity, "info", TEXT_TERTIARY);
        note.addView(icon, new LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20)));
        status = text(activity, "", SUPPORTING, TEXT_SECONDARY);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginStart(dp(activity, 12)); note.addView(status, params);
        add(page.body, note, 24, 0);
        setContentView(page.root);
        refresh();
        scroll.post(() -> scroll.scrollTo(0, previousScroll));
    }

    private void refresh() {
        if (status == null) return;
        for (int i = 0; i < choices.length; i++) {
            boolean selected = timer.selected() == i;
            choices[i].setSelected(selected);
            indicators[i].setSelected(selected);
            indicators[i].invalidate();
            choices[i].title.setTypeface(selected ? MEDIUM : REGULAR);
        }
        status.setText(timer.status());
    }

    private static final class Selection extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Selection(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float x = getWidth() / 2f, y = getHeight() / 2f;
            paint.setColor(isSelected() ? TEXT : TEXT_TERTIARY);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f * density);
            canvas.drawCircle(x, y, 9 * density, paint);
            if (isSelected()) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x, y, 4.5f * density, paint);
            }
        }
    }
}
