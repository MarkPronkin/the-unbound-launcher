package dev.beampro.port;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A native toolbar action shared by the launcher's three controller layouts. */
public final class PhoneScreenControl extends LinearLayout {
    public static final int ID = 0x00bea010;
    // Resource IDs are shared by all three pinned controller layouts.
    private static final int CONTENT = 0x7f09009e, MENU = 0x7f0900c7, MODE = 0x7f0900a0;
    private static final int BACK = 0x7f09005b, PAD = 0x7f090140, LABEL = 0x7f090275;
    private final ViewGroup content;
    public final boolean horizontal;

    private PhoneScreenControl(ViewGroup content, boolean horizontal, OnClickListener action) {
        super(content.getContext());
        this.content = content;
        this.horizontal = horizontal;
        setId(ID);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setMinimumWidth(dp(horizontal ? 120 : 50));
        setPadding(dp(horizontal ? 14 : 0), 0, dp(horizontal ? 14 : 0), 0);
        setContentDescription("Turn off phone screen");
        setTooltipText("Turn off phone screen");
        setFocusable(true);
        setOnClickListener(action);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setAccessibilityTraversalAfter(MODE);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff191919, 0xff111111});
        shape.setCornerRadius(dp(22));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(horizontal ? 22 : 12));
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff), horizontal ? shape : null, mask));
        View icon = new Icon(getContext());
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(icon, new LayoutParams(dp(24), dp(24)));
        if (horizontal) {
            TextView label = new TextView(getContext());
            label.setText("Screen off");
            label.setTextColor(0xffcccccc);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setSingleLine(true);
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams text = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            text.setMarginStart(dp(8));
            addView(label, text);
        }
    }

    /** Called after hierarchy changes, including controller-mode switches and rotation. */
    public static PhoneScreenControl attach(View root, OnClickListener action) {
        View candidate = root.findViewById(CONTENT);
        if (!(candidate instanceof ViewGroup)) return null;
        ViewGroup content = (ViewGroup) candidate;
        View menu = content.findViewById(MENU), mode = content.findViewById(MODE), back = content.findViewById(BACK);
        if (menu == null || mode == null || back == null || menu.getParent() != content || mode.getParent() != content)
            return null;
        PhoneScreenControl existing = content.findViewById(ID);
        if (existing != null) return existing;
        boolean horizontal = back.getParent() == content;
        PhoneScreenControl button = new PhoneScreenControl(content, horizontal, action);
        try {
            // Use the APK's own ConstraintLayout params without bundling a second AndroidX copy.
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) menu.getLayoutParams().getClass()
                    .getConstructor(int.class, int.class).newInstance(horizontal ? LayoutParams.WRAP_CONTENT : button.dp(50), button.dp(50));
            constraint(params, "topToTop", MENU);
            constraint(params, "bottomToBottom", MENU);
            if (horizontal) {
                constraint(params, "startToEnd", MENU);
                params.setMarginStart(button.dp(12));
            } else {
                constraint(params, "endToEnd", 0);
                params.setMarginEnd(button.dp(7));
            }
            ViewGroup.MarginLayoutParams title = (ViewGroup.MarginLayoutParams) mode.getLayoutParams();
            constraint(title, "startToStart", -1);
            constraint(title, "startToEnd", horizontal ? ID : MENU);
            if (!horizontal) {
                constraint(title, "endToEnd", -1);
                constraint(title, "endToStart", ID);
            }
            title.getClass().getField("constrainedWidth").setBoolean(title, true);
            title.getClass().getField("horizontalBias").setFloat(title, horizontal ? 1f : .5f);
            if (horizontal) title.setMarginStart(button.dp(12));
            mode.setLayoutParams(title);
            TextView label = content.findViewById(LABEL);
            if (label != null) {
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                // Keep the mode icon and chevron visible on narrow phones as well.
                content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    int reserved = horizontal ? button.getWidth() + button.dp(182) : button.dp(180);
                    int max = Math.max(button.dp(48), r - l - reserved);
                    if (label.getMaxWidth() != max) label.setMaxWidth(max);
                });
            }
            content.addView(button, params);
            return button;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Unsupported controller toolbar layout", error);
        }
    }

    private static void constraint(Object params, String name, int value) throws ReflectiveOperationException {
        params.getClass().getField(name).setInt(params, value);
    }

    public boolean belongsTo(View root) { return root.findViewById(CONTENT) == content; }

    /** The compatibility hit target must follow the actual view and never cover a drawer or lock sheet. */
    public boolean touchBounds(View root, Rect bounds) {
        if (!isShown() || !isAttachedToWindow() || !getGlobalVisibleRect(bounds)
                || bounds.width() != getWidth() || bounds.height() != getHeight()) return false;
        for (String name : new String[]{"drawer", "mask_view", "screen_lock_layout", "screen_lock_layout_landscape"}) {
            int id = getResources().getIdentifier(name, "id", getResources().getResourcePackageName(CONTENT));
            View blocker = id == 0 ? null : root.findViewById(id);
            if (blocker != null && blocker.isShown() && blocker.getGlobalVisibleRect(new Rect())) return false;
        }
        int[] location = new int[2];
        getLocationOnScreen(location);
        bounds.set(location[0], location[1], location[0] + getWidth(), location[1] + getHeight());
        return true;
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(Button.class.getName());
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class Icon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 24f, getHeight() / 24f);
            paint.setColor(0xffcccccc);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.65f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            // Broken phone outline leaves a clean gap around the diagonal, even on a transparent toolbar.
            canvas.drawLine(8, 2.75f, 16, 2.75f, paint);
            canvas.drawArc(14, 2.75f, 18, 6.75f, -90, 90, false, paint);
            canvas.drawLine(18, 4.75f, 18, 14, paint);
            canvas.drawLine(6, 9, 6, 19.25f, paint);
            canvas.drawArc(6, 17.25f, 10, 21.25f, 90, 90, false, paint);
            canvas.drawLine(8, 21.25f, 16, 21.25f, paint);
            canvas.drawLine(10.5f, 18, 13.5f, 18, paint);
            canvas.drawLine(3, 3, 21, 21, paint);
            canvas.restore();
        }
    }
}
