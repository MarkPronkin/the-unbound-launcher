package dev.beampro.settings;

import static dev.beampro.settings.Signature.*;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Live layout preview using the headset's verified five-column, ten-app pages. */
public final class HeadsetMenuPreview extends ScrollView {
    // EPHViewManager.mMaxPageCount=10; EPHAppPage.layout is a SGridLayoutGroup
    // with UpperLeft/Horizontal/FixedColumnCount and m_ConstraintCount=5.
    public static final int COLUMNS = 5, ROWS = 2, PAGE_SIZE = COLUMNS * ROWS;
    interface Apps {
        String name(String id);
        View icon(String id);
        boolean matches(String id, String query);
        void edit(String id);
    }
    private final Apps apps;
    private final LinearLayout grid, body, card, pager;
    private final TextView title, pageLabel, searchStatus, empty, explanation;
    private final Button previous, next;
    private final List<String> visible = new ArrayList<>();
    private String query = "", focusedApp;
    private int page;
    private boolean restoringPage, compact;
    private float touchX, touchY;

    HeadsetMenuPreview(Context context, Apps apps) {
        super(context); this.apps = apps;
        setFillViewport(true); setVerticalScrollBarEnabled(false); setClipToPadding(false);
        body = column(context); body.setPadding(0, dp(8), 0, dp(12)); addView(body, new ScrollView.LayoutParams(-1, -2));
        card = panel(context); card.setPadding(dp(10), dp(16), dp(10), dp(16)); body.addView(card);
        title = overline(context, "", TEXT_SECONDARY);
        title.setPadding(dp(10), 0, dp(10), dp(16)); card.addView(title);
        grid = column(context); grid.setContentDescription("Headset app grid, five columns and two rows"); card.addView(grid);
        empty = Signature.text(context, "No visible apps\nRestore apps from the Hidden list to fill your launcher.", BODY, TEXT_SECONDARY);
        empty.setGravity(Gravity.CENTER); empty.setPadding(dp(16), dp(28), dp(16), dp(28)); card.addView(empty);
        pageLabel = Signature.text(context, "", CAPTION, TEXT); pageLabel.setGravity(Gravity.CENTER); pageLabel.setPadding(0, dp(12), 0, 0); card.addView(pageLabel);
        pager = Signature.row(context); pager.setPadding(0, dp(8), 0, 0);
        previous = button(context, "‹  Previous", SECONDARY, true); previous.setContentDescription("Previous headset page");
        next = button(context, "Next  ›", SECONDARY, true); next.setContentDescription("Next headset page");
        previous.setOnClickListener(v -> showPage(page - 1)); next.setOnClickListener(v -> showPage(page + 1));
        pager.addView(previous, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, -2, 1);
        nextParams.setMarginStart(dp(8)); pager.addView(next, nextParams); body.addView(pager);
        explanation = Signature.text(context, "5 columns × 2 rows · Left to right, then the next row.\nTap an app to move or hide it. Swipe to change pages.", CAPTION, TEXT_SECONDARY);
        explanation.setPadding(0, dp(8), 0, dp(4)); body.addView(explanation);
        searchStatus = Signature.text(context, "", CAPTION, TEXT); searchStatus.setPadding(0, dp(8), 0, dp(4)); body.addView(searchStatus);
    }

    /** Keep both launcher rows and page controls useful on short landscape displays. */
    void setCompact(boolean value) {
        if (compact == value) return;
        compact = value;
        body.setPadding(0, compact ? 0 : dp(8), 0, compact ? 0 : dp(12));
        card.setPadding(dp(compact ? 8 : 10), dp(compact ? 8 : 16), dp(compact ? 8 : 10), dp(compact ? 8 : 16));
        title.setVisibility(compact ? GONE : VISIBLE);
        explanation.setVisibility(compact ? GONE : VISIBLE);
        searchStatus.setVisibility(compact ? GONE : VISIBLE);
        ((LinearLayout) pageLabel.getParent()).removeView(pageLabel);
        pageLabel.setPadding(0, compact ? 0 : dp(12), 0, 0);
        if (compact) pager.addView(pageLabel, 1, new LinearLayout.LayoutParams(0, -2, 1));
        else card.addView(pageLabel, new LinearLayout.LayoutParams(-1, -2));
        pager.setPadding(0, compact ? 0 : dp(8), 0, 0);
        previous.setText(compact ? "‹" : "‹  Previous"); next.setText(compact ? "›" : "Next  ›");
        previous.setLayoutParams(new LinearLayout.LayoutParams(compact ? dp(48) : 0, -2, compact ? 0 : 1));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(compact ? dp(48) : 0, -2, compact ? 0 : 1);
        nextParams.setMarginStart(compact ? 0 : dp(8)); next.setLayoutParams(nextParams);
        render();
    }

    void update(List<String> ids, String needle, boolean draft) {
        visible.clear(); visible.addAll(ids);
        if (!restoringPage && !query.equals(needle) && !needle.isEmpty()) {
            for (int i = 0; i < visible.size(); i++) if (apps.matches(visible.get(i), needle)) { page = i / PAGE_SIZE; break; }
        }
        query = needle;
        restoringPage = false;
        if (focusedApp != null) {
            int index = visible.indexOf(focusedApp); if (index >= 0) page = index / PAGE_SIZE;
            focusedApp = null;
        }
        page = Math.max(0, Math.min(page, pageCount() - 1));
        title.setText(draft ? "AR SPACE  ·  UNSAVED PREVIEW" : "AR SPACE  ·  LAUNCHER PREVIEW");
        render();
    }
    void focusApp(String id) { focusedApp = id; }
    public int getPage() { return page; }
    public int pageCount() { return Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE); }
    void restorePage(int value) { page = Math.max(0, value); restoringPage = true; }
    public void showPage(int value) {
        page = Math.max(0, Math.min(value, pageCount() - 1)); render();
        announceForAccessibility(pageLabel.getText());
    }
    private void render() {
        grid.removeAllViews();
        grid.setVisibility(visible.isEmpty() ? GONE : VISIBLE); empty.setVisibility(visible.isEmpty() ? VISIBLE : GONE);
        for (int row = 0; row < ROWS; row++) {
            LinearLayout line = new LinearLayout(getContext()); line.setOrientation(LinearLayout.HORIZONTAL); line.setMinimumHeight(dp(compact ? 64 : 98));
            for (int column = 0; column < COLUMNS; column++) {
                int index = page * PAGE_SIZE + row * COLUMNS + column;
                View tile = index < visible.size() ? tile(visible.get(index), index, row, column) : new View(getContext());
                line.addView(tile, new LinearLayout.LayoutParams(0, -2, 1));
            }
            grid.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
        int first = visible.isEmpty() ? 0 : page * PAGE_SIZE + 1, last = Math.min(visible.size(), (page + 1) * PAGE_SIZE);
        String description = "Page " + (page + 1) + " of " + pageCount() + "   ·   Apps " + first + "–" + last;
        pageLabel.setText(compact ? (page + 1) + " / " + pageCount() : description);
        pageLabel.setContentDescription(description);
        previous.setEnabled(page > 0); next.setEnabled(page + 1 < pageCount());
        if (query.isEmpty()) searchStatus.setText("Shows your full draft order. Hidden apps are excluded.");
        else {
            int matches = 0; for (String id : visible) if (apps.matches(id, query)) matches++;
            searchStatus.setText(matches == 0 ? "No visible matches. Hidden apps can be found and restored in List."
                    : matches + (matches == 1 ? " matching app" : " matching apps") + " highlighted. The full layout stays visible.");
        }
    }
    private View tile(String id, int index, int row, int column) {
        LinearLayout tile = column(getContext()); tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(2), dp(compact ? 4 : 7), dp(2), dp(compact ? 4 : 7));
        tile.setTag(id); boolean highlighted = !query.isEmpty() && apps.matches(id, query);
        GradientDrawable fill = shape(highlighted ? SURFACE_HIGH : Color.TRANSPARENT, dp(10));
        if (highlighted) fill.setStroke(dp(1), TEXT_SECONDARY);
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), fill, null));
        TextView position = Signature.text(getContext(), Integer.toString(index + 1), 10, TEXT_TERTIARY); position.setGravity(Gravity.CENTER); tile.addView(position);
        position.setVisibility(compact ? GONE : VISIBLE);
        View icon = apps.icon(id); LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(compact ? 28 : 34), dp(compact ? 28 : 34));
        iconSize.topMargin = compact ? 0 : dp(4); tile.addView(icon, iconSize);
        TextView name = Signature.text(getContext(), apps.name(id), 10, TEXT); name.setLineSpacing(0, 1.1f); name.setGravity(Gravity.CENTER); name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setMinHeight(dp(compact ? 22 : 28));
        LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(-1, -2); labelSize.topMargin = dp(compact ? 2 : 5); tile.addView(name, labelSize);
        tile.setContentDescription(apps.name(id) + ", position " + (index + 1) + ", page " + (page + 1) + ", row " + (row + 1) + ", column " + (column + 1));
        tile.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); tile.setFocusable(true);
        for (int i = 0; i < tile.getChildCount(); i++) tile.getChildAt(i).setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        tile.setOnClickListener(v -> apps.edit(id));
        return tile;
    }
    // Detect horizontal swipes without interfering with taps or vertical scrolling.
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { touchX = event.getX(); touchY = event.getY(); }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && Math.abs(event.getX() - touchX) > dp(40)
                && Math.abs(event.getX() - touchX) > Math.abs(event.getY() - touchY) * 1.5f) return true;
        return super.onInterceptTouchEvent(event);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(event.getX() - touchX) > dp(64)
                && Math.abs(event.getX() - touchX) > Math.abs(event.getY() - touchY) * 1.5f) {
            showPage(page + (event.getX() < touchX ? 1 : -1)); return true;
        }
        return super.onTouchEvent(event);
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
