package dev.beampro.port;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Phone-only connection theatre. Only the successful controller handoff waits for its visual transition. */
public final class ConnectionAnimation extends FrameLayout {
    private static final int WHITE = 0xfff5f5f7, MUTED = 0xffa1a1a6, MINT = 0xffb4ffdc;
    static final long HANDOFF_MS = 650;
    private final DotMatrix matrix;
    private final TextView heading, status, eyebrow;
    private final Button retry, close;
    private final LinearLayout body;
    private final LinearLayout actions;
    private final ScrollView details;
    private final TextView brand;
    private boolean disposed;
    private boolean failedState;

    public ConnectionAnimation(Activity activity, Runnable onRetry, Runnable onClose) {
        super(activity);
        setBackgroundColor(Color.BLACK);
        setFitsSystemWindows(true);
        matrix = new DotMatrix(activity);
        addView(matrix, new LayoutParams(-1, -1));
        brand = text("THE UNBOUND LAUNCHER", 11, MUTED);
        brand.setLetterSpacing(.18f);
        addView(brand);

        details = new ScrollView(activity);
        details.setFillViewport(false);
        details.setVerticalScrollBarEnabled(true);
        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        details.addView(body, new ScrollView.LayoutParams(-1, -2));
        addView(details);
        eyebrow = text("USB CONNECTION", 10, MINT);
        eyebrow.setLetterSpacing(.22f);
        body.addView(eyebrow, row(0));
        heading = text("Connect your\nglasses.", 38, WHITE);
        heading.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        heading.setLineSpacing(0, 1.02f);
        heading.setAccessibilityHeading(true);
        body.addView(heading, row(14));
        status = text("Waiting for USB access", 14, MUTED);
        status.setLineSpacing(0, 1.25f);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status, row(16));
        actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        addView(actions);
        retry = button("Try again", true);
        retry.setOnClickListener(v -> onRetry.run());
        retry.setVisibility(GONE);
        actions.addView(retry, row(16));
        close = button("Cancel connection", false);
        close.setOnClickListener(v -> onClose.run());
        actions.addView(close, row(8));
    }

    public void show(Activity activity) {
        Window window = activity.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setGravity(Gravity.CENTER);
        window.setLayout(-1, -1);
        window.setWindowAnimations(0);
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(true);
        window.getDecorView().setSystemUiVisibility(0);
        activity.setFinishOnTouchOutside(false);
        activity.setContentView(this);
    }

    public void stage(String value) {
        if (disposed) return;
        status.setText(value.equals("Waiting for glasses USB access")
                ? "Connecting to your glasses" : value);
        matrix.stage(value);
    }

    public void failure(String message, boolean busy) {
        if (disposed) return;
        failedState = true;
        matrix.failure();
        eyebrow.setText("LET’S RECONNECT");
        heading.setText("Connection paused.");
        status.setText(message);
        retry.setVisibility(busy ? GONE : VISIBLE);
        close.setText("Close");
        requestLayout();
    }

    public void connected() { connected(null); }

    public void connected(Runnable onHandoff) {
        if (disposed) return;
        failedState = false;
        eyebrow.setText("GLASSES CONNECTED");
        heading.setText("Trackpad ready.");
        status.setText("Opening AR Space");
        retry.setVisibility(GONE);
        close.setVisibility(INVISIBLE);
        matrix.connect(onHandoff);
        requestLayout();
    }

    public void dispose() { disposed = true; matrix.stop(); }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, float size, int color) {
        TextView v = new TextView(getContext());
        v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setGravity(Gravity.CENTER); v.setIncludeFontPadding(false);
        return v;
    }
    private LinearLayout.LayoutParams row(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top); return p;
    }
    private Button button(String label, boolean filled) {
        Button b = new Button(getContext());
        b.setText(label); b.setTextSize(14); b.setAllCaps(false);
        b.setTextColor(filled ? Color.BLACK : MUTED);
        b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(filled ? WHITE : Color.TRANSPARENT); surface.setCornerRadius(dp(14));
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33b4ffdc), surface, null));
        return b;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        boolean wide = w > h && w >= dp(600);
        int gutter = dp(28), top = getPaddingTop(), bottom = getPaddingBottom();
        int usable = h - top - bottom;
        int textWidth = Math.min(dp(420), wide ? w / 2 - 2 * gutter : w - 2 * gutter);
        heading.setTextSize(failedState ? (wide ? 22 : 30) : wide && usable < dp(420) ? 30 : 38);
        eyebrow.setVisibility(failedState && wide ? GONE : VISIBLE);
        matrix.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        brand.measure(MeasureSpec.makeMeasureSpec(w - 2 * gutter, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.AT_MOST));
        actions.measure(MeasureSpec.makeMeasureSpec(Math.max(1, textWidth), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, usable - dp(80)), MeasureSpec.AT_MOST));
        details.measure(MeasureSpec.makeMeasureSpec(Math.max(1, textWidth), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, usable - dp(wide ? 86 : 150) - actions.getMeasuredHeight()), MeasureSpec.AT_MOST));
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t, top = getPaddingTop(), bottom = getPaddingBottom();
        boolean wide = w > h && w >= dp(600);
        matrix.layout(0, 0, w, h);
        brand.layout(dp(28), top + dp(22), w - dp(28), top + dp(22) + brand.getMeasuredHeight());
        int left = wide ? w * 3 / 4 - details.getMeasuredWidth() / 2 : (w - details.getMeasuredWidth()) / 2;
        int groupHeight = details.getMeasuredHeight() + actions.getMeasuredHeight();
        int y = wide ? top + dp(60) + Math.max(0, (h - top - bottom - dp(86) - groupHeight) / 2)
                : h - bottom - dp(24) - groupHeight;
        details.layout(left, y, left + details.getMeasuredWidth(), y + details.getMeasuredHeight());
        int actionTop = y + details.getMeasuredHeight();
        actions.layout(left, actionTop, left + actions.getMeasuredWidth(), actionTop + actions.getMeasuredHeight());
        int artTop = top + dp(64);
        int artBottom = wide ? h - bottom - dp(24) : Math.max(artTop + dp(80), y - dp(12));
        matrix.art(wide ? 0 : dp(16), artTop, wide ? w / 2 : w - dp(16), artBottom);
    }

    /** The same sparse grid carries the USB scene into the phone's trackpad and pointer. */
    static final class DotMatrix extends View {
        private static final float[] POINTER = {-18, -28, 20, 2, 5, 6, 13, 25, 4, 29, -4, 11, -18, 23};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF art = new RectF();
        private final ValueAnimator clock = ValueAnimator.ofFloat(0, 1);
        private final AccessibilityManager access;
        private float tick, age, handoff;
        private int stage;
        private long connectedAt;
        private boolean connected, failed, stopped, handoffDone;
        private Runnable onHandoff;

        DotMatrix(Context c) {
            super(c);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            access = (AccessibilityManager)c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            clock.setDuration(12000); clock.setRepeatCount(ValueAnimator.INFINITE);
            clock.setInterpolator(new LinearInterpolator());
            clock.addUpdateListener(a -> {
                tick = (Float)a.getAnimatedValue();
                age = Math.min(1f, a.getCurrentPlayTime() / 1200f);
                if (connected && !handoffDone) {
                    handoff = Math.min(1f, (SystemClock.uptimeMillis() - connectedAt) / (float)HANDOFF_MS);
                    if (handoff >= 1) completeHandoff();
                }
                invalidate();
            });
        }

        void art(float left, float top, float right, float bottom) {
            art.set(left, top, right, bottom); invalidate();
        }
        void stage(String value) {
            stage = value.equals("Starting AR Space") ? 2 : value.equals("Preparing glasses") ? 1 : 0;
            invalidate();
        }
        void failure() {
            failed = true; connected = false; handoffDone = true; onHandoff = null;
            updateMotion(); invalidate();
        }
        void connect(Runnable completion) {
            if (stopped || connected) return;
            failed = false; handoffDone = false; connected = true;
            connectedAt = SystemClock.uptimeMillis(); onHandoff = completion;
            updateMotion(); invalidate();
        }
        void stop() { stopped = true; onHandoff = null; clock.cancel(); }
        private boolean canAnimate() {
            return !stopped && !failed && (!connected || !handoffDone) && isAttachedToWindow() && isShown()
                    && getWindowVisibility() == VISIBLE && ValueAnimator.areAnimatorsEnabled()
                    && (access == null || !access.isTouchExplorationEnabled());
        }
        private void updateMotion() {
            if (canAnimate()) {
                if (!clock.isStarted()) clock.start();
                return;
            }
            clock.cancel(); age = 1;
            if (connected && !handoffDone) { handoff = 1; completeHandoff(); }
        }
        private void completeHandoff() {
            if (handoffDone) return;
            handoffDone = true; handoff = 1; clock.cancel();
            Runnable completion = onHandoff; onHandoff = null;
            if (completion != null) completion.run();
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateMotion(); }
        @Override protected void onDetachedFromWindow() {
            onHandoff = null; handoffDone = true; clock.cancel(); super.onDetachedFromWindow();
        }
        @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); if (clock != null) updateMotion(); }
        @Override public void onVisibilityAggregated(boolean visible) { super.onVisibilityAggregated(visible); updateMotion(); }

        private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
        private float smooth(float value) { value = Math.max(0, Math.min(1, value)); return value * value * (3 - 2 * value); }
        private float smooth(float start, float end, float value) { return smooth((value - start) / (end - start)); }
        private float rounded(float x, float y, float halfW, float halfH, float radius) {
            float qx = Math.abs(x) - halfW + radius, qy = Math.abs(y) - halfH + radius;
            return (float)Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - radius;
        }
        private float segment(float x, float y, float ax, float ay, float bx, float by) {
            float dx = bx - ax, dy = by - ay;
            float p = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
            return (float)Math.hypot(x - ax - p * dx, y - ay - p * dy);
        }
        private float edge(float distance) { return Math.max(0, 1 - Math.abs(distance) / 5f); }
        private float glasses(float x, float y, float gx, float gy) {
            x -= gx; y -= gy;
            float left = rounded(x + 47, y, 37, 24, 11), right = rounded(x - 47, y, 37, 24, 11);
            float outline = Math.min(Math.abs(left), Math.abs(right));
            float bridge = segment(x, y, -12, -5, 12, -5);
            float temples = Math.min(segment(x, y, -82, -12, -103, -29), segment(x, y, 82, -12, 103, -29));
            return Math.max(edge(outline), Math.max(edge(bridge), edge(temples)));
        }
        private float plug(float x, float y, boolean portrait, float gx, float gy) {
            float enter = 1 - (float)Math.pow(1 - age, 3);
            if (portrait) {
                float py = 150 - 10 * enter;
                return Math.max(edge(rounded(x, y - py, 17, 10, 4)), edge(segment(x, y, -9, py, 9, py)));
            }
            float px = -150 + 10 * enter;
            return Math.max(edge(rounded(x - px, y - 92, 10, 17, 4)), edge(segment(x, y, px, 83, px, 101)));
        }
        private float cable(float x, float y, boolean portrait, float gx, float gy) {
            float enter = 1 - (float)Math.pow(1 - age, 3), p, distance;
            if (portrait) {
                float plugY = 150 - 10 * enter, start = gy + 25, end = plugY - 10;
                p = (end - y) / (end - start);
                if (p < 0 || p > 1) return 0;
                distance = Math.abs(x - 12 * (float)Math.sin(p * Math.PI));
            } else {
                float plugX = -150 + 10 * enter, end = gx - 8;
                p = (x - plugX) / (end - plugX);
                if (p < 0 || p > 1) return 0;
                float curveY = 92 + (gy + 24 - 92) * p - 18 * (float)Math.sin(p * Math.PI);
                distance = Math.abs(y - curveY);
            }
            if (failed && p > .43 && p < .62) return 0;
            float base = Math.max(0, 1 - distance / 5f) * (failed ? .18f : .30f);
            float pulse = Math.max(0, 1 - Math.abs(p - (tick * 2 % 1f)) / .13f);
            return failed ? base : Math.max(base, pulse * Math.max(0, 1 - distance / 6f));
        }
        private boolean insidePointer(float x, float y) {
            boolean inside = false;
            for (int i = 0, j = POINTER.length - 2; i < POINTER.length; j = i, i += 2) {
                float xi = POINTER[i], yi = POINTER[i + 1], xj = POINTER[j], yj = POINTER[j + 1];
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
            }
            return inside;
        }
        private void dot(Canvas c, float x, float y, float strength, int color) {
            if (strength <= .025f) return;
            strength = Math.min(1, strength);
            paint.setColor(((int)(235 * strength) << 24) | (color & 0xffffff));
            c.drawCircle(x, y, dp(1.45f) + dp(.65f) * strength, paint);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (getWidth() <= 0 || getHeight() <= 0) return;
            paint.setStyle(Paint.Style.FILL); paint.setShader(null);
            float spacing = dp(26), startX = (getWidth() % spacing) / 2f, startY = (getHeight() % spacing) / 2f;
            paint.setColor(0xff242424);
            for (float y = startY; y < getHeight(); y += spacing)
                for (float x = startX; x < getWidth(); x += spacing) c.drawCircle(x, y, dp(.9f), paint);
            if (art.width() <= 0 || art.height() <= 0) return;
            boolean portrait = getHeight() >= getWidth();
            float unit = Math.min(art.width() / 300f, art.height() / 330f);
            if (unit <= 0) return;
            float cx = art.centerX(), cy = art.centerY(), gx = portrait ? 0 : 35, gy = portrait ? -55 : -25;
            float enter = 1 - (float)Math.pow(1 - age, 3);
            float reveal = (stage == 0 ? .20f : stage == 1 ? .58f : 1f) * enter;
            float departure = connected ? 1 - smooth(0, .60f, handoff) : 1;
            float track = connected ? smooth(0, .34f, handoff) * (1 - smooth(.64f, 1, handoff)) * .70f : 0;
            float pointerAlpha = connected ? smooth(.20f, .85f, handoff) : 0;
            float pointerTravel = smooth(handoff);
            float pointerX = gx * (1 - pointerTravel), pointerY = gy * (1 - pointerTravel) + 18 * pointerTravel;
            float pointerScale = .75f + .55f * pointerTravel;
            float pitch = dp(10), glyphX = (getWidth() % pitch) / 2f, glyphY = (getHeight() % pitch) / 2f;
            for (float py = glyphY; py < getHeight(); py += pitch) {
                if (py < art.top - pitch || py > art.bottom + pitch) continue;
                for (float px = glyphX; px < getWidth(); px += pitch) {
                    if (px < art.left - pitch || px > art.right + pitch) continue;
                    float x = (px - cx) / unit, y = (py - cy) / unit;
                    float glass = glasses(x, y, gx, gy) * reveal * departure;
                    float wire = Math.max(cable(x, y, portrait, gx, gy), plug(x, y, portrait, gx, gy)) * departure;
                    float pad = edge(rounded(x, y - 18, 85, 90, 22)) * track;
                    float pointer = pointerAlpha > 0 && insidePointer((x - pointerX) / pointerScale,
                            (y - pointerY) / pointerScale) ? pointerAlpha : 0;
                    if (pointer > .02) dot(c, px, py, pointer, WHITE);
                    else if (glass >= wire && glass >= pad) dot(c, px, py, glass, WHITE);
                    else if (wire >= pad) dot(c, px, py, wire, MINT);
                    else dot(c, px, py, pad, MINT);
                }
            }
        }
    }
}
