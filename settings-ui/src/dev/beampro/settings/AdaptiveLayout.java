package dev.beampro.settings;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Window-based layouts for inner displays, rotation and multi-window; no device-name checks. */
public final class AdaptiveLayout {
    public static final int TWO_PANE_WIDTH = 600;
    public static final int MAX_WIDTH = Signature.MAX_WIDTH * 2;
    public static final int PANE_GAP = 24;

    private AdaptiveLayout() {}

    public static boolean expanded(Context context, int windowWidthPixels) {
        // Leave room for readable labels when the user chooses large text.
        float fontScale = context.getResources().getConfiguration().fontScale;
        float minimum = TWO_PANE_WIDTH * Math.max(1f, fontScale / 1.3f);
        return windowWidthPixels >= Signature.dp(context, minimum);
    }

    /** Use available window space, including system bars and the keyboard, rather than device rotation. */
    public static boolean landscape(Context context, int width, int height) {
        return width >= Signature.dp(context, 480) && height > 0 && width >= height * 1.35f;
    }

    public static boolean shortLandscape(Context context, int width, int height) {
        return landscape(context, width, height) && height < Signature.dp(context, 520);
    }

    /** Reflows existing views before measuring, keeping focus, drafts and scroll containers intact. */
    public static final class Window extends LinearLayout {
        public interface Listener { void onModeChanged(boolean expanded, boolean landscape, boolean shortHeight); }
        private final Listener listener;
        private boolean measured, expanded, landscape, shortHeight;

        public Window(Context context, Listener listener) {
            super(context); this.listener = listener; setOrientation(VERTICAL);
        }

        @Override protected void onMeasure(int width, int height) {
            int w = MeasureSpec.getSize(width), h = MeasureSpec.getSize(height);
            boolean wide = AdaptiveLayout.expanded(getContext(), w);
            boolean horizontal = AdaptiveLayout.landscape(getContext(), w, h);
            boolean shortWindow = AdaptiveLayout.shortLandscape(getContext(), w, h);
            if (!measured || wide != expanded || horizontal != landscape || shortWindow != shortHeight) {
                measured = true; expanded = wide; landscape = horizontal; shortHeight = shortWindow;
                listener.onModeChanged(wide, horizontal, shortWindow);
            }
            super.onMeasure(width, height);
        }
    }

    /** Overview and a pinned action beside scrolling details on wide landscape windows. */
    public static final class DetailPage {
        public final LinearLayout root, intro, body;
        public final ScrollView scroll;
        private final LinearLayout content, overview;
        private final ScrollView introScroll;
        private final View divider;
        private LinearLayout footer, footerFrame;
        private boolean split;

        public DetailPage(Context context, View navigation) {
            root = new Window(context, (wide, landscape, shortHeight) -> arrange(wide && landscape));
            root.setBackgroundColor(Signature.BACKGROUND);
            Signature.AppBar bar = new Signature.AppBar(context, navigation, MAX_WIDTH);
            root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
            content = Signature.bounded(context, MAX_WIDTH);
            content.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(content, Signature.centred(0, 1));
            overview = Signature.column(context); overview.setVisibility(View.GONE);
            content.addView(overview, new LinearLayout.LayoutParams(0, -1, 1));
            introScroll = Signature.scroll(context); introScroll.setTag("landscape-page-overview");
            overview.addView(introScroll, new LinearLayout.LayoutParams(-1, 0, 1));
            divider = new View(context); divider.setBackgroundColor(Signature.DIVIDER); divider.setVisibility(View.GONE);
            content.addView(divider, new LinearLayout.LayoutParams(Signature.dp(context, 1), -1));
            scroll = Signature.scroll(context); scroll.setTag("landscape-page-details");
            content.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
            body = Signature.gutters(context);
            body.setPadding(body.getPaddingLeft(), Signature.dp(context, 8), body.getPaddingRight(), Signature.dp(context, 24));
            scroll.addView(body, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
            intro = Signature.column(context); body.addView(intro);
            bar.liftWith(scroll);
        }

        private void arrange(boolean wide) {
            if (wide == split) return;
            split = wide;
            Context context = root.getContext();
            ((ViewGroup) intro.getParent()).removeView(intro);
            int gutter = Signature.dp(context, Signature.GUTTER);
            intro.setPadding(wide ? gutter : 0, wide ? Signature.dp(context, 8) : 0,
                    wide ? gutter : 0, wide ? Signature.dp(context, 16) : 0);
            if (wide) introScroll.addView(intro, new FrameLayout.LayoutParams(-1, -2));
            else body.addView(intro, 0, new LinearLayout.LayoutParams(-1, -2));
            overview.setVisibility(wide ? View.VISIBLE : View.GONE);
            divider.setVisibility(wide ? View.VISIBLE : View.GONE);
            if (footerFrame != null) {
                ((ViewGroup) footerFrame.getParent()).removeView(footerFrame);
                (wide ? overview : root).addView(footerFrame, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        public LinearLayout footer() {
            if (footer != null) return footer;
            Context context = root.getContext();
            footerFrame = Signature.column(context); footerFrame.setTag("landscape-page-actions");
            View line = new View(context); line.setBackgroundColor(Signature.DIVIDER);
            footerFrame.addView(line, new LinearLayout.LayoutParams(-1, Signature.dp(context, 1)));
            footer = Signature.gutters(context);
            footer.setPadding(footer.getPaddingLeft(), Signature.dp(context, 12), footer.getPaddingRight(), Signature.dp(context, 12));
            footerFrame.addView(footer, Signature.centred(-2, 0));
            (split ? overview : root).addView(footerFrame, new LinearLayout.LayoutParams(-1, -2));
            return footer;
        }
    }

    /** Equal panes keep their controls away from the centre seam on book-style displays. */
    public static final class Panes extends LinearLayout {
        public interface Listener { void onModeChanged(boolean expanded); }
        private final View first, second;
        private final Listener listener;
        private boolean expanded, measured;

        public Panes(Context context, View first, View second, Listener listener) {
            super(context);
            this.first = first; this.second = second; this.listener = listener;
            addView(first); addView(second);
            arrange(false);
        }

        private void arrange(boolean wide) {
            setOrientation(wide ? HORIZONTAL : VERTICAL);
            first.setLayoutParams(new LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 1));
            LayoutParams trailing = new LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 1);
            trailing.setMarginStart(wide ? Signature.dp(getContext(), PANE_GAP) : 0);
            second.setLayoutParams(trailing);
        }

        @Override protected void onMeasure(int width, int height) {
            // Panes sit inside the shared page gutters; classify the whole window width.
            int windowWidth = MeasureSpec.getSize(width) + Signature.dp(getContext(), Signature.GUTTER * 2);
            boolean wide = AdaptiveLayout.expanded(getContext(), windowWidth);
            if (!measured || wide != expanded) {
                measured = true; expanded = wide;
                arrange(wide);
                listener.onModeChanged(wide);
            }
            super.onMeasure(width, height);
        }
    }
}
