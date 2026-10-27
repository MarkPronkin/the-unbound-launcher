package dev.beampro.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/**
 * The Signature OLED design system: one set of tokens and components for every phone screen.
 * Pages compose these instead of styling views locally, so they cannot drift apart.
 */
public final class Signature {
    private Signature() {}

    // Colour: a true-black canvas, graphite surfaces, white controls and one accent for healthy states.
    public static final int BACKGROUND = Color.BLACK;
    public static final int SURFACE = 0xff111113;       // panels, inputs and segmented tracks
    public static final int SURFACE_HIGH = 0xff1c1c1e;  // dialogs and disabled primary buttons
    public static final int OUTLINE = 0xff2c2c2e;       // borders, selected segments and chips
    public static final int DIVIDER = 0xff232325;       // separators between flat rows
    public static final int TEXT = 0xfff5f5f7;
    public static final int TEXT_SECONDARY = 0xffa1a1a6;
    public static final int TEXT_TERTIARY = 0xff7c7c82; // hints, chevrons and disabled labels (AA on black)
    public static final int ON_PRIMARY = 0xff111111;
    public static final int POSITIVE = 0xff70d69c;      // connected, ready and allowed
    private static final int RIPPLE = 0x22ffffff, RIPPLE_ON_PRIMARY = 0x1f000000;

    // Type scale in sp.
    public static final float DISPLAY = 30, HEADLINE = 22, TITLE = 16, ROW_TITLE = 15, BODY = 14,
            SUPPORTING = 13, CAPTION = 12, OVERLINE = 11;
    // Layout in dp: page gutters, readable width on large screens, and two corner radii.
    public static final int GUTTER = 24, MAX_WIDTH = 640, RADIUS = 12, RADIUS_PANEL = 16;

    public static final int PRIMARY = 0, SECONDARY = 1, QUIET = 2;
    public static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    public static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Black system bars with light icons; the design is intentionally fixed in every system theme. */
    public static void window(Window window) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(true);
        window.setStatusBarColor(BACKGROUND);
        window.setNavigationBarColor(BACKGROUND);
        window.setNavigationBarDividerColor(BACKGROUND);
        window.setNavigationBarContrastEnforced(false);
        int flags = window.getDecorView().getSystemUiVisibility();
        flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        window.getDecorView().setSystemUiVisibility(flags);
    }

    // ---- Text ----

    public static TextView text(Context context, CharSequence value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(REGULAR); view.setIncludeFontPadding(false);
        view.setFontFeatureSettings("kern"); view.setLineSpacing(0, 1.2f);
        return view;
    }

    public static TextView medium(Context context, CharSequence value, float size, int color) {
        TextView view = text(context, value, size, color); view.setTypeface(MEDIUM); return view;
    }

    /** The one large title each page has, directly below its app bar. */
    public static TextView display(Context context, CharSequence value) {
        TextView view = text(context, value, DISPLAY, TEXT);
        view.setLineSpacing(0, 1.1f); view.setAccessibilityHeading(true);
        return view;
    }

    /** Small spaced capitals for the product name, section headings and status labels. */
    public static TextView overline(Context context, CharSequence value, int color) {
        TextView view = medium(context, value.toString().toUpperCase(Locale.ROOT), OVERLINE, color);
        view.setLetterSpacing(.16f);
        return view;
    }

    /** A section heading with the standard space above and below it. */
    public static TextView heading(Context context, CharSequence value) {
        TextView view = overline(context, value, TEXT_SECONDARY);
        view.setAccessibilityHeading(true);
        view.setPadding(0, dp(context, 28), 0, dp(context, 8));
        return view;
    }

    // ---- Layout ----

    public static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view;
    }

    public static LinearLayout row(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    public static void add(LinearLayout parent, View child, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(parent.getContext(), top); params.bottomMargin = dp(parent.getContext(), bottom);
        parent.addView(child, params);
    }

    /** A column that never grows past the readable width; its parent centres it. */
    public static LinearLayout bounded(Context context) {
        return bounded(context, MAX_WIDTH);
    }

    public static LinearLayout bounded(Context context, int maxWidth) {
        LinearLayout view = new LinearLayout(context) {
            @Override protected void onMeasure(int width, int height) {
                int max = dp(getContext(), maxWidth);
                if (MeasureSpec.getMode(width) != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(width) > max) {
                    width = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
                }
                super.onMeasure(width, height);
            }
        };
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    /** A bounded column with the page gutters; add it to a vertical parent with {@link #centred}. */
    public static LinearLayout gutters(Context context) {
        return gutters(context, MAX_WIDTH);
    }

    public static LinearLayout gutters(Context context, int maxWidth) {
        LinearLayout body = bounded(context, maxWidth);
        body.setPadding(dp(context, GUTTER), 0, dp(context, GUTTER), 0);
        return body;
    }

    /** Full-width params that centre a bounded child in a vertical LinearLayout. */
    public static LinearLayout.LayoutParams centred(int height, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, height, weight);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        return params;
    }

    public static ScrollView scroll(Context context) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        return scroll;
    }

    /** A screen with a fixed app bar, a scrolling centred body and an optional fixed footer. */
    public static final class Page {
        public final LinearLayout root, body;
        public final AppBar appBar;
        public final ScrollView scroll;
        private LinearLayout footer;

        public Page(Context context, View navigation) {
            root = column(context); root.setBackgroundColor(BACKGROUND);
            appBar = new AppBar(context, navigation);
            root.addView(appBar, new LinearLayout.LayoutParams(-1, -2));
            scroll = scroll(context);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            body = gutters(context);
            scroll.addView(body, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
            body.setPadding(body.getPaddingLeft(), dp(context, 8), body.getPaddingRight(), dp(context, 32));
            body.setClipToPadding(false); // Product photos may run edge to edge through the gutters.
            appBar.liftWith(scroll);
        }

        /** A fixed action area for the page's main button, separated from scrolling content. */
        public LinearLayout footer() {
            if (footer != null) return footer;
            Context context = root.getContext();
            LinearLayout frame = column(context);
            View line = new View(context); line.setBackgroundColor(DIVIDER);
            frame.addView(line, new LinearLayout.LayoutParams(-1, dp(context, 1)));
            footer = gutters(context);
            frame.addView(footer, centred(-2, 0));
            footer.setPadding(footer.getPaddingLeft(), dp(context, 12), footer.getPaddingRight(), dp(context, 16));
            root.addView(frame, new LinearLayout.LayoutParams(-1, -2));
            return footer;
        }
    }

    /** The same bar on every screen: navigation (back arrow or brand mark), the product name, then actions. */
    public static final class AppBar extends LinearLayout {
        final LinearLayout bar;
        final View divider;
        final TextView name;
        private View content;

        public AppBar(Context context, View navigation) {
            this(context, navigation, MAX_WIDTH);
        }

        public AppBar(Context context, View navigation, int maxWidth) {
            super(context);
            setOrientation(VERTICAL); setBackgroundColor(BACKGROUND);
            bar = row(context);
            bar.setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 4));
            bar.setMinimumHeight(dp(context, 56));
            bar.addView(navigation, new LayoutParams(dp(context, 48), dp(context, 48)));
            name = overline(context, SettingsUi.APP_NAME, TEXT);
            name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LayoutParams nameParams = new LayoutParams(0, -2, 1);
            nameParams.setMarginStart(dp(context, 8)); bar.addView(name, nameParams);
            LinearLayout bounded = bounded(context, maxWidth); bounded.addView(bar);
            addView(bounded, centred(-2, 0));
            divider = new View(context); divider.setBackgroundColor(DIVIDER); divider.setAlpha(0);
            addView(divider, new LayoutParams(-1, dp(context, 1)));
        }

        public void action(View view, int width) {
            LayoutParams params = new LayoutParams(width, view instanceof TextView ? -2 : dp(getContext(), 48));
            params.setMarginStart(dp(getContext(), 4)); bar.addView(view, params);
        }

        /** Short landscape pages put their title in the bar to leave room for actual content. */
        public void title(CharSequence title) {
            name.setText(title == null ? SettingsUi.APP_NAME.toUpperCase(Locale.ROOT) : title);
            name.setTextSize(title == null ? OVERLINE : TITLE);
            name.setLetterSpacing(title == null ? .16f : 0);
            name.setImportantForAccessibility(title == null ? IMPORTANT_FOR_ACCESSIBILITY_NO : IMPORTANT_FOR_ACCESSIBILITY_YES);
            name.setAccessibilityHeading(title != null);
        }

        /** Landscape search stays beside the page title and actions, including while the IME opens. */
        public void content(View view) {
            if (content == view) return;
            if (content != null) bar.removeView(content);
            content = view;
            LayoutParams nameParams = new LayoutParams(view == null ? 0 : -2, -2, view == null ? 1 : 0);
            nameParams.setMarginStart(dp(getContext(), 8)); name.setLayoutParams(nameParams);
            name.setMaxWidth(view == null ? Integer.MAX_VALUE : dp(getContext(), 160));
            bar.setPadding(bar.getPaddingLeft(), view == null ? dp(getContext(), 4) : 0,
                    bar.getPaddingRight(), view == null ? dp(getContext(), 4) : 0);
            bar.setMinimumHeight(dp(getContext(), view == null ? 56 : 48));
            if (view != null) {
                if (view.getParent() != null) ((android.view.ViewGroup) view.getParent()).removeView(view);
                LayoutParams params = new LayoutParams(0, -2, 1);
                params.setMarginStart(dp(getContext(), 8));
                bar.addView(view, 2, params);
            }
        }

        /** Show a hairline under the bar only once content has scrolled beneath it. */
        void liftWith(ScrollView scroll) {
            scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> divider.setAlpha(y > 0 ? 1 : 0));
        }
    }

    public static View back(Context context, String description, View.OnClickListener action) {
        return iconButton(context, "back", description, action);
    }

    public static View brand(Context context) {
        ImageView mark = new ImageView(context);
        mark.setImageDrawable(GlassesArtwork.load(context, "brand-mark.png"));
        mark.setColorFilter(TEXT, PorterDuff.Mode.SRC_IN);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int inset = dp(context, 11); mark.setPadding(inset, inset, inset, inset);
        mark.setContentDescription(SettingsUi.APP_NAME);
        return mark;
    }

    // ---- Surfaces ----

    public static GradientDrawable shape(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(radius);
        return drawable;
    }

    /** A raised graphite container for status, forms and previews. Lists use flat rows instead. */
    public static LinearLayout panel(Context context) {
        LinearLayout panel = column(context);
        GradientDrawable background = shape(SURFACE, dp(context, RADIUS_PANEL));
        background.setStroke(dp(context, 1), OUTLINE);
        panel.setBackground(background);
        int pad = dp(context, 20); panel.setPadding(pad, pad, pad, pad);
        return panel;
    }

    public static Drawable ripple(Context context, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, shape(Color.WHITE, radius));
    }

    /** Flat rows separated by subtle dividers, the main way settings-like lists are grouped. */
    public static LinearLayout group(Context context) {
        LinearLayout group = column(context);
        GradientDrawable divider = shape(DIVIDER, 0); divider.setSize(1, dp(context, 1));
        group.setDividerDrawable(divider); group.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return group;
    }

    /** One list row: optional 24dp icon, a title, and optional supporting text. Callers append trailing views. */
    public static final class Row extends LinearLayout {
        public final LinearLayout labels;
        public final TextView title, subtitle;

        public Row(Context context, String glyph, CharSequence title, CharSequence subtitle) {
            super(context);
            setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, dp(context, 14), 0, dp(context, 14));
            setMinimumHeight(dp(context, 64));
            if (glyph != null) addView(new Icon(context, glyph, TEXT_SECONDARY), new LayoutParams(dp(context, 24), dp(context, 24)));
            labels = column(context);
            this.title = text(context, title, ROW_TITLE, TEXT);
            labels.addView(this.title);
            this.subtitle = text(context, subtitle == null ? "" : subtitle, SUPPORTING, TEXT_SECONDARY);
            add(labels, this.subtitle, 3, 0);
            this.subtitle.setVisibility(subtitle == null || subtitle.length() == 0 ? GONE : VISIBLE);
            LayoutParams params = new LayoutParams(0, -2, 1);
            if (glyph != null) params.setMarginStart(dp(context, 16));
            addView(labels, params);
        }

        public Row chevron() {
            Icon arrow = new Icon(getContext(), "chevron", TEXT_TERTIARY);
            LayoutParams params = new LayoutParams(dp(getContext(), 18), dp(getContext(), 24));
            params.setMarginStart(dp(getContext(), 8)); addView(arrow, params);
            return this;
        }

        public TextView value(CharSequence text) {
            TextView value = text(getContext(), text, SUPPORTING, TEXT_SECONDARY);
            value.setGravity(Gravity.END); value.setMaxWidth(dp(getContext(), 120));
            LayoutParams params = new LayoutParams(-2, -2);
            params.setMarginStart(dp(getContext(), 12)); addView(value, params);
            return value;
        }

        public Row onClick(View.OnClickListener action) {
            setBackground(ripple(getContext(), 0)); setFocusable(true); setOnClickListener(action);
            return this;
        }
    }

    // ---- Controls ----

    /** Primary is the page's main action, secondary sits beside it, quiet is for low-emphasis actions. */
    public static Button button(Context context, CharSequence label, int style) { return button(context, label, style, false); }

    public static Button button(Context context, CharSequence label, int style, boolean compact) {
        Button button = new Button(context);
        button.setText(label); button.setAllCaps(false); button.setGravity(Gravity.CENTER);
        button.setTypeface(MEDIUM); button.setTextSize(compact ? SUPPORTING : 15); button.setLetterSpacing(0);
        button.setIncludeFontPadding(false);
        button.setBackgroundTintList(null); button.setStateListAnimator(null); button.setElevation(0);
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{TEXT_TERTIARY, style == PRIMARY ? ON_PRIMARY : TEXT}));
        button.setBackground(buttonBackground(context, style, compact));
        int horizontal = dp(context, compact ? 16 : 20), vertical = dp(context, compact ? 12 : 14);
        button.setPadding(horizontal, vertical, horizontal, vertical);
        int height = dp(context, compact ? 48 : 52);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(height); button.setMinimumHeight(height);
        return button;
    }

    private static Drawable buttonBackground(Context context, int style, boolean compact) {
        float radius = dp(context, compact ? 10 : RADIUS);
        GradientDrawable enabled = shape(style == PRIMARY ? TEXT : Color.TRANSPARENT, radius);
        GradientDrawable disabled = shape(style == PRIMARY ? SURFACE_HIGH : Color.TRANSPARENT, radius);
        if (style == SECONDARY) { enabled.setStroke(dp(context, 1), OUTLINE); disabled.setStroke(dp(context, 1), DIVIDER); }
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, disabled);
        states.addState(new int[0], new RippleDrawable(ColorStateList.valueOf(style == PRIMARY ? RIPPLE_ON_PRIMARY : RIPPLE),
                enabled, shape(Color.WHITE, radius)));
        // Compact buttons draw 40dp tall inside a 48dp touch target.
        return compact ? new InsetDrawable(states, 0, dp(context, 4), 0, dp(context, 4)) : states;
    }

    /** A 48dp touch target around a 24dp glyph. */
    public static Icon iconButton(Context context, String glyph, String description, View.OnClickListener action) {
        Icon icon = new Icon(context, glyph, TEXT);
        icon.setContentDescription(description);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        icon.setFocusable(true); icon.setClickable(true);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null);
        ripple.setRadius(dp(context, 20)); icon.setBackground(ripple);
        icon.setOnClickListener(action);
        return icon;
    }

    /** Two or more equal choices in one graphite track; the selected one is lifted. */
    public static final class Segmented extends LinearLayout {
        public Segmented(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setBackground(shape(SURFACE, dp(context, RADIUS)));
            int pad = dp(context, 4); setPadding(pad, pad, pad, pad);
        }

        public Button add(String label, String description, View.OnClickListener action) {
            Context context = getContext();
            Button segment = new Button(context);
            segment.setText(label); segment.setAllCaps(false); segment.setTypeface(MEDIUM);
            segment.setTextSize(SUPPORTING); segment.setIncludeFontPadding(false); segment.setGravity(Gravity.CENTER);
            segment.setBackgroundTintList(null); segment.setStateListAnimator(null); segment.setElevation(0);
            segment.setTextColor(selectable(TEXT, TEXT_SECONDARY));
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_selected}, shape(OUTLINE, dp(context, RADIUS - 3)));
            states.addState(new int[0], new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, shape(Color.WHITE, dp(context, RADIUS - 3))));
            segment.setBackground(states);
            segment.setPadding(dp(context, 8), 0, dp(context, 8), 0);
            segment.setMinWidth(0); segment.setMinimumWidth(0);
            segment.setMinHeight(dp(context, 40)); segment.setMinimumHeight(dp(context, 40));
            segment.setContentDescription(description); segment.setOnClickListener(action);
            addView(segment, new LayoutParams(0, -2, 1));
            return segment;
        }
    }

    /** A compact filter; selection uses the same lifted graphite as segmented controls. */
    public static Button chip(Context context, String label, String description, View.OnClickListener action) {
        Button chip = new Button(context);
        chip.setText(label); chip.setAllCaps(false); chip.setTypeface(MEDIUM);
        chip.setTextSize(SUPPORTING); chip.setIncludeFontPadding(false); chip.setGravity(Gravity.CENTER);
        chip.setBackgroundTintList(null); chip.setStateListAnimator(null); chip.setElevation(0);
        chip.setTextColor(selectable(TEXT, TEXT_SECONDARY));
        float radius = dp(context, 18);
        GradientDrawable idle = shape(Color.TRANSPARENT, radius); idle.setStroke(dp(context, 1), OUTLINE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, shape(OUTLINE, radius));
        states.addState(new int[0], new RippleDrawable(ColorStateList.valueOf(RIPPLE), idle, shape(Color.WHITE, radius)));
        chip.setBackground(new InsetDrawable(states, 0, dp(context, 6), 0, dp(context, 6)));
        chip.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        chip.setMinWidth(0); chip.setMinimumWidth(0);
        chip.setMinHeight(dp(context, 48)); chip.setMinimumHeight(dp(context, 48));
        chip.setContentDescription(description); chip.setOnClickListener(action);
        return chip;
    }

    private static ColorStateList selectable(int selected, int idle) {
        return new ColorStateList(new int[][]{{android.R.attr.state_selected}, {}}, new int[]{selected, idle});
    }

    /** Text field on a graphite surface whose border brightens while focused. */
    public static EditText input(Context context, String hint) {
        EditText edit = new EditText(context);
        edit.setHint(hint); edit.setSingleLine(true); edit.setTextSize(16);
        edit.setTextColor(TEXT); edit.setHintTextColor(TEXT_TERTIARY); edit.setTypeface(REGULAR);
        edit.setHighlightColor(0x55ffffff);
        float radius = dp(context, RADIUS);
        GradientDrawable idle = shape(SURFACE, radius); idle.setStroke(dp(context, 1), OUTLINE);
        GradientDrawable focused = shape(SURFACE, radius); focused.setStroke(dp(context, 1), TEXT_SECONDARY);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[0], idle);
        edit.setBackground(states);
        edit.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        edit.setMinHeight(dp(context, 52)); edit.setMinimumHeight(dp(context, 52));
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cursor = shape(TEXT, 0); cursor.setSize(dp(context, 2), dp(context, 20));
            edit.setTextCursorDrawable(cursor);
        }
        return edit;
    }

    // ---- Status ----

    /** A coloured dot and label: green for healthy states, white for in progress, grey for idle. */
    public static final class Status extends LinearLayout {
        public final TextView label;
        private final Dot dot;

        public Status(Context context, float size) {
            super(context);
            setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            dot = new Dot(context);
            LayoutParams dotParams = new LayoutParams(dp(context, 8), dp(context, 8));
            dotParams.setMarginEnd(dp(context, 10)); addView(dot, dotParams);
            label = text(context, "", size, TEXT_SECONDARY);
            addView(label, new LayoutParams(0, -2, 1));
        }

        public void set(CharSequence text, int dotColor, int textColor) {
            if (!text.toString().contentEquals(label.getText())) label.setText(text);
            label.setTextColor(textColor); dot.setColor(dotColor);
        }
    }

    static final class Dot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Dot(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void setColor(int color) { if (paint.getColor() != color) { paint.setColor(color); invalidate(); } }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f, paint);
        }
    }

    /** First-run progress shared by permissions, connection and agreement. */
    public static LinearLayout stepper(Context context, int current) {
        LinearLayout progress = column(context);
        LinearLayout bars = row(context);
        String[] steps = {"01  Permissions", "02  Connection", "03  Terms"};
        for (int i = 1; i <= steps.length; i++) {
            View bar = new View(context);
            bar.setBackground(shape(i <= current ? TEXT : OUTLINE, dp(context, 1)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(context, 2), 1);
            if (i > 1) params.setMarginStart(dp(context, 8));
            bars.addView(bar, params);
        }
        progress.addView(bars);
        // One full-width label stays readable on cover screens and at large text sizes.
        TextView label = overline(context, steps[current - 1], TEXT);
        label.setLetterSpacing(.12f); label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        add(progress, label, 10, 0);
        progress.setContentDescription("Setup step " + current + " of 3, " + (current == 3 ? "terms of service" : current == 2 ? "connection" : "app permissions"));
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return progress;
    }

    // ---- Dialogs ----

    public static AlertDialog.Builder dialog(Context context) {
        return new AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert);
    }

    public static AlertDialog show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create(); show(dialog); return dialog;
    }

    /** A graphite sheet with sentence-case white actions instead of Material's grey and teal. */
    public static void show(AlertDialog dialog) {
        dialog.show();
        Context context = dialog.getContext();
        GradientDrawable sheet = shape(SURFACE_HIGH, dp(context, 20));
        sheet.setStroke(dp(context, 1), OUTLINE);
        dialog.getWindow().setBackgroundDrawable(new InsetDrawable(sheet, dp(context, 16)));
        int[] buttons = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL};
        for (int which : buttons) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            button.setAllCaps(false); button.setTypeface(MEDIUM); button.setTextSize(15);
            button.setTextColor(which == AlertDialog.BUTTON_POSITIVE ? TEXT : TEXT_SECONDARY);
        }
    }

    // ---- Icons ----

    /** Small vector icons keep the APK independent of fonts and network assets. */
    public static final class Icon extends View {
        final String glyph;
        final int color;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Icon(Context context, String glyph, int color) {
            super(context); this.glyph = glyph; this.color = color;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override public CharSequence getAccessibilityClassName() {
            return isClickable() ? Button.class.getName() : super.getAccessibilityClassName();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float density = getResources().getDisplayMetrics().density;
            // Buttons centre a standard 24dp glyph; inline icons fill their own bounds.
            float size = Math.min(getWidth(), getHeight());
            if (size > 36 * density) size = 24 * density;
            c.save(); c.translate((getWidth() - size) / 2, (getHeight() - size) / 2); c.scale(size / 24f, size / 24f);
            paint.setColor(isEnabled() ? color : TEXT_TERTIARY);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.7f);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            switch (glyph) {
                case "info":
                    c.drawCircle(12, 12, 9, paint); line(c, 12, 11, 12, 17); line(c, 12, 7, 12, 7.1f); break;
                case "help":
                    c.drawCircle(12, 12, 9, paint);
                    Path question = new Path(); question.moveTo(9, 8);
                    question.cubicTo(9, 4, 17, 5, 15, 10);
                    question.cubicTo(14, 12, 12, 11, 12, 14); c.drawPath(question, paint);
                    line(c, 12, 17, 12, 17.1f); break;
                case "grid":
                    c.drawRoundRect(3, 3, 9, 9, 1, 1, paint); c.drawRoundRect(15, 3, 21, 9, 1, 1, paint);
                    c.drawRoundRect(3, 15, 9, 21, 1, 1, paint); c.drawRoundRect(15, 15, 21, 21, 1, 1, paint); break;
                case "notifications":
                    path(c, 5, 16, 7, 13, 7, 9, 8, 6, 10, 4, 14, 4, 16, 6, 17, 9, 17, 13, 19, 16, 5, 16);
                    c.drawArc(9, 17, 15, 21, 0, 180, false, paint); line(c, 12, 2, 12, 4); break;
                case "mirror":
                    c.drawRoundRect(3, 3, 21, 17, 2, 2, paint); line(c, 8, 21, 16, 21); line(c, 12, 17, 12, 21);
                    path(c, 9, 10, 12, 7, 15, 10); line(c, 12, 7, 12, 13); break;
                case "hdcp":
                    c.drawRoundRect(2, 3, 22, 18, 2, 2, paint); line(c, 8, 22, 16, 22); line(c, 12, 18, 12, 22);
                    c.drawRoundRect(8.5f, 10, 15.5f, 15, 1, 1, paint); c.drawArc(9.5f, 6, 14.5f, 12, 180, 180, false, paint);
                    break;
                case "privacy": case "permissions": case "capture":
                    path(c, 12, 2, 20, 5, 20, 12, 18, 17, 12, 22, 6, 17, 4, 12, 4, 5, 12, 2);
                    if (glyph.equals("privacy")) { c.drawRoundRect(9, 10, 15, 15, 1, 1, paint); c.drawArc(10, 6, 14, 12, 180, 180, false, paint); }
                    else path(c, 8, 12, 11, 15, 16, 9);
                    break;
                case "key":
                    c.drawCircle(7, 10, 4, paint);
                    path(c, 11, 10, 21, 10, 21, 15, 18, 15, 18, 10);
                    break;
                case "document":
                    path(c, 6, 2, 15, 2, 20, 7, 20, 22, 4, 22, 4, 2, 6, 2); path(c, 14, 2, 14, 8, 20, 8);
                    line(c, 8, 12, 16, 12); line(c, 8, 16, 14, 16); break;
                case "moon":
                    Path moon = new Path(); moon.moveTo(19.5f, 15.5f); moon.cubicTo(8, 19, 5, 9, 10, 3);
                    moon.cubicTo(-1, 5, 1, 22, 13, 21); moon.cubicTo(17, 21, 20, 18, 19.5f, 15.5f); c.drawPath(moon, paint); break;
                case "sun": case "color":
                    c.drawCircle(12, 12, 4, paint);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        line(c, 12 + (float) Math.cos(a) * 8, 12 + (float) Math.sin(a) * 8, 12 + (float) Math.cos(a) * 10, 12 + (float) Math.sin(a) * 10);
                    }
                    break;
                case "sleep":
                    c.drawCircle(12, 12, 9, paint); path(c, 12, 6, 12, 12, 16, 14); break;
                case "update":
                    c.drawArc(3, 3, 21, 21, 35, 290, false, paint); path(c, 21, 3, 21, 9, 15, 9);
                    path(c, 12, 17, 12, 8, 9, 11); line(c, 12, 8, 15, 11); break;
                case "game":
                    c.drawRoundRect(2, 6, 22, 19, 5, 5, paint);
                    line(c, 6, 12, 12, 12); line(c, 9, 9, 9, 15);
                    c.drawCircle(16, 11, .8f, paint); c.drawCircle(19, 14, .8f, paint); break;
                case "code": path(c, 7, 6, 1, 12, 7, 18); path(c, 17, 6, 23, 12, 17, 18); line(c, 14, 4, 10, 20); break;
                case "camera": c.drawRoundRect(2, 6, 22, 20, 3, 3, paint); c.drawCircle(12, 13, 4, paint); path(c, 7, 6, 9, 3, 15, 3, 17, 6); break;
                case "back": path(c, 11, 5, 4, 12, 11, 19); line(c, 4, 12, 20, 12); break;
                case "search": c.drawCircle(10.5f, 10.5f, 6.5f, paint); line(c, 15.5f, 15.5f, 20.5f, 20.5f); break;
                case "more":
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(12, 5, 1.8f, paint); c.drawCircle(12, 12, 1.8f, paint); c.drawCircle(12, 19, 1.8f, paint); break;
                case "external": path(c, 14, 4, 20, 4, 20, 10); line(c, 20, 4, 11, 13); path(c, 17, 14, 17, 20, 4, 20, 4, 7, 10, 7); break;
                case "phone": c.drawRoundRect(6, 2, 18, 22, 3, 3, paint); line(c, 10.5f, 18.5f, 13.5f, 18.5f); break;
                case "overlay": c.drawRoundRect(3, 8, 16, 21, 2, 2, paint); path(c, 8, 8, 8, 5, 10, 3, 19, 3, 21, 5, 21, 14, 19, 16, 16, 16); break;
                case "download": line(c, 12, 3, 12, 15); path(c, 7, 10, 12, 15, 17, 10); line(c, 4, 20, 20, 20); break;
                case "bluetooth": path(c, 7, 7, 17, 17, 12, 22, 12, 2, 17, 7, 7, 17); break;
                case "mic":
                    c.drawRoundRect(9, 2, 15, 14, 3, 3, paint); c.drawArc(5, 4, 19, 18, 0, 180, false, paint);
                    line(c, 12, 18, 12, 22); break;
                case "image": c.drawRoundRect(3, 4, 21, 20, 2, 2, paint); c.drawCircle(8.5f, 9.5f, 1.5f, paint); path(c, 21, 15, 16, 10, 5, 20); break;
                case "battery": c.drawRoundRect(2, 7, 19, 17, 2, 2, paint); line(c, 22, 10.5f, 22, 13.5f); path(c, 11, 9, 8.5f, 12, 12.5f, 12, 10, 15); break;
                case "expand": path(c, 6, 9, 12, 15, 18, 9); break;
                case "collapse": path(c, 6, 15, 12, 9, 18, 15); break;
                default: path(c, 9, 6, 15, 12, 9, 18);
            }
            c.restore();
        }

        void line(Canvas c, float a, float b, float d, float e) { c.drawLine(a, b, d, e, paint); }
        void path(Canvas c, float... points) {
            Path p = new Path(); p.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) p.lineTo(points[i], points[i + 1]);
            c.drawPath(p, paint);
        }
    }
}
