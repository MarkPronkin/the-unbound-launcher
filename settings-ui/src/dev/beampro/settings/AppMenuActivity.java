package dev.beampro.settings;

import static dev.beampro.settings.Signature.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Phone-side launcher editor. It never requires Unity, the helper, or glasses. */
public class AppMenuActivity extends Activity {
    private final Map<String, App> apps = new LinkedHashMap<>();
    private ArrayList<String> visible = new ArrayList<>(), hidden = new ArrayList<>();
    private ArrayList<String> undoVisible, undoHidden;
    private AppMenuStore store;
    private ListView list;
    private EditText search;
    private TextView status, empty;
    private Button save, undo, allTab, hiddenTab, listMode, headsetMode, applyToGlasses;
    private View menuActions;
    private LinearLayout tabs, editor, previewPanel;
    private LinearLayout body, footer, footerFrame, draft;
    private FrameLayout searchField;
    private TextView pageTitle, pageDescription;
    private AppBar bar;
    private AdaptiveLayout.Panes panes;
    private Segmented modes;
    private TextView listHint, previewHeading;
    private FrameLayout listPanel;
    private HeadsetMenuPreview headsetPreview;
    private MenuAdapter adapter;
    private boolean hiddenOnly, dirty, previewMode, expanded, loading = true;
    private boolean landscape, shortLandscape, keyboardVisible;
    private boolean undoHiddenOnly;
    private Boolean otherAppsHidden, undoOtherAppsHidden;
    private String query = "", dragId;

    public static void open(Activity activity) { activity.startActivity(new Intent(activity, AppMenuActivity.class)); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            query = state.getString("query", ""); hiddenOnly = state.getBoolean("hiddenOnly");
            previewMode = state.getBoolean("previewMode");
            if (state.containsKey("otherAppsHidden")) otherAppsHidden = state.getBoolean("otherAppsHidden");
            undoVisible = state.getStringArrayList("undoVisible"); undoHidden = state.getStringArrayList("undoHidden");
            undoHiddenOnly = state.getBoolean("undoHiddenOnly");
            if (state.containsKey("undoOtherAppsHidden")) undoOtherAppsHidden = state.getBoolean("undoOtherAppsHidden");
        }
        build();
        if (state != null) headsetPreview.restorePage(state.getInt("previewPage"));
        updatePanels();
        new Thread(() -> {
            try {
                AppMenuStore loaded = new AppMenuStore(this);
                if (state != null && state.getBoolean("dirty")) loaded.expectSnapshot(state.getByteArray("layoutSnapshot"));
                Map<String, App> catalog = catalog();
                List<String> order = loaded.order(new ArrayList<>(catalog.keySet()));
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    store = loaded; apps.putAll(catalog);
                    for (String id : order) (loaded.isHidden(id) ? hidden : visible).add(id);
                    if (state != null && state.getBoolean("dirty")) {
                        // Reconcile a recreated draft with installed/uninstalled packages.
                        ArrayList<String> restored = state.getStringArrayList("visible");
                        ArrayList<String> restoredHidden = state.getStringArrayList("hidden");
                        if (restored != null && restoredHidden != null) {
                            ArrayList<String> added = new ArrayList<>(order);
                            added.removeAll(restored); added.removeAll(restoredHidden);
                            restored.retainAll(order); restoredHidden.retainAll(order);
                            visible = restored; hidden = restoredHidden; visible.addAll(added); dirty = true;
                        }
                    }
                    loading = false; search.setText(query); refresh();
                    if (state != null) {
                        search.setSelection(Math.min(search.length(), Math.max(0, state.getInt("searchSelection", search.length()))));
                        if (state.getBoolean("searchFocused")) search.requestFocus();
                        android.os.Parcelable position = state.getParcelable("listPosition");
                        if (position != null) list.post(() -> list.onRestoreInstanceState(position));
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> { loading = false; status.setText(error.getMessage()); empty.setText("App menu unavailable"); });
            }
        }, "AppMenuCatalog").start();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("query", query); state.putBoolean("hiddenOnly", hiddenOnly);
        state.putBoolean("previewMode", previewMode); state.putInt("previewPage", headsetPreview.getPage());
        state.putBoolean("dirty", dirty); state.putStringArrayList("visible", visible); state.putStringArrayList("hidden", hidden);
        if (otherAppsHidden != null) state.putBoolean("otherAppsHidden", otherAppsHidden);
        state.putStringArrayList("undoVisible", undoVisible); state.putStringArrayList("undoHidden", undoHidden);
        state.putBoolean("undoHiddenOnly", undoHiddenOnly);
        if (undoOtherAppsHidden != null) state.putBoolean("undoOtherAppsHidden", undoOtherAppsHidden);
        state.putParcelable("listPosition", list.onSaveInstanceState());
        state.putInt("searchSelection", search.getSelectionStart()); state.putBoolean("searchFocused", search.hasFocus());
        if (store != null) state.putByteArray("layoutSnapshot", store.snapshot());
    }

    private Map<String, App> catalog() {
        List<App> installed = new ArrayList<>();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : getPackageManager().queryIntentActivities(intent, 0)) {
            String id = info.activityInfo.packageName;
            if (!info.activityInfo.enabled || !info.activityInfo.applicationInfo.enabled || !info.activityInfo.exported
                    || id.equals(getPackageName()) || id.equals("dev.beampro.portprobe")) continue;
            installed.add(new App(id, info.loadLabel(getPackageManager()).toString(), false));
        }
        Collator collator = Collator.getInstance();
        Collections.sort(installed, (a, b) -> { int c = collator.compare(a.name, b.name); return c == 0 ? a.id.compareTo(b.id) : c; });
        Map<String, App> result = new LinkedHashMap<>();
        // These three virtual apps are always generated by EPHAppInfoGenerator.CustomTask.
        result.put("TutorialWidgetContent", new App("TutorialWidgetContent", "Tutorial", true));
        result.put("LiveWindowWidgetContent", new App("LiveWindowWidgetContent", "TV", true));
        result.put("PhotoWidgetContent", new App("PhotoWidgetContent", "Photos", true));
        for (App app : installed) if (!result.containsKey(app.id)) result.put(app.id, app);
        return result;
    }

    private void build() {
        window(getWindow());
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root = new AdaptiveLayout.Window(this, (wide, horizontal, shortHeight) -> {
            expanded = wide; landscape = horizontal; shortLandscape = shortHeight;
            arrangeLandscape(); updatePanels();
        });
        root.setBackgroundColor(BACKGROUND);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        bar = new AppBar(this, back(this, "Back", v -> onBackPressed()), AdaptiveLayout.MAX_WIDTH);
        save = button(this, "Save", PRIMARY, true); save.setContentDescription("Save app menu");
        save.setOnClickListener(v -> save()); save.setEnabled(false);
        bar.action(save, -2);
        menuActions = iconButton(this, "more", "App menu actions", v -> showMenuActions());
        menuActions.setEnabled(false);
        bar.action(menuActions, dp(48));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        body = gutters(this, AdaptiveLayout.MAX_WIDTH);
        body.setPadding(body.getPaddingLeft(), 0, body.getPaddingRight(), dp(8));
        root.addView(body, centred(0, 1));
        pageTitle = display(this, "App menu"); add(body, pageTitle, 8, 0);
        pageDescription = text(this, "Find apps, move them anywhere, or hide them from AR Space.", BODY, TEXT_SECONDARY);
        add(body, pageDescription, 8, 0);
        searchField = new FrameLayout(this);
        search = input(this, "Search apps or package names"); search.setContentDescription("Search apps or package names");
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setOnEditorActionListener((v, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            hideKeyboard(); return true;
        });
        search.setPaddingRelative(dp(48), search.getPaddingTop(), dp(16), search.getPaddingBottom());
        searchField.addView(search, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams glassParams = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.START | Gravity.CENTER_VERTICAL);
        glassParams.setMarginStart(dp(16)); searchField.addView(new Icon(this, "search", TEXT_TERTIARY), glassParams);
        add(body, searchField, 20, 0);
        search.addTextChangedListener(watcher(() -> { query = search.getText().toString(); refresh(); }));
        modes = new Segmented(this);
        listMode = modes.add("List", "Show app list", v -> { previewMode = false; refresh(); });
        headsetMode = modes.add("Headset preview", "Show headset preview", v -> { hideKeyboard(); previewMode = true; refresh(); });
        add(body, modes, 12, 0);
        editor = column(this);
        editor.setContentDescription("App list editor");
        tabs = Signature.row(this);
        allTab = chip(this, "All apps", "Show all apps", v -> { hiddenOnly = false; refresh(); });
        hiddenTab = chip(this, "Hidden", "Show hidden apps", v -> { hiddenOnly = true; refresh(); });
        tabs.addView(allTab, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams hiddenParams = new LinearLayout.LayoutParams(-2, -2);
        hiddenParams.setMarginStart(dp(8)); tabs.addView(hiddenTab, hiddenParams);
        add(editor, tabs, 4, 0);
        listHint = text(this, "Menu order · Hold and drag, or tap ⋮ to move", CAPTION, TEXT_TERTIARY);
        add(editor, listHint, 0, 8);
        listPanel = new FrameLayout(this); editor.addView(listPanel, new LinearLayout.LayoutParams(-1, 0, 1));
        list = new ListView(this); list.setDivider(new android.graphics.drawable.ColorDrawable(DIVIDER)); list.setDividerHeight(dp(1));
        list.setClipToPadding(false); list.setBackgroundColor(BACKGROUND); list.setVerticalScrollBarEnabled(false);
        adapter = new MenuAdapter(); list.setAdapter(adapter); listPanel.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = text(this, "Loading apps…", BODY, TEXT_SECONDARY); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(12), dp(28), dp(12), dp(28));
        listPanel.addView(empty, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
        headsetPreview = new HeadsetMenuPreview(this, new HeadsetMenuPreview.Apps() {
            public String name(String id) { return apps.get(id).name; }
            public View icon(String id) { return appIcon(id); }
            public boolean matches(String id, String query) { return AppMenuActivity.this.matches(id, query); }
            public void edit(String id) { actions(id); }
        });
        previewPanel = column(this);
        previewPanel.setContentDescription("Live headset preview");
        previewHeading = medium(this, "Headset preview", TITLE, TEXT);
        add(previewPanel, previewHeading, 16, 8);
        previewPanel.addView(headsetPreview, new LinearLayout.LayoutParams(-1, 0, 1));
        panes = new AdaptiveLayout.Panes(this, editor, previewPanel, wide -> {
            expanded = wide; updatePanels();
        });
        LinearLayout.LayoutParams paneParams = new LinearLayout.LayoutParams(-1, 0, 1);
        paneParams.topMargin = dp(8); body.addView(panes, paneParams);
        list.setOnItemClickListener((parent, view, position, id) -> actions(adapter.ids.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            String app = adapter.ids.get(position); if (hidden.contains(app)) return false;
            hideKeyboard(); dragId = app;
            return view.startDragAndDrop(ClipData.newPlainText("App", apps.get(app).name), new View.DragShadowBuilder(view), app, 0);
        });
        list.setOnDragListener((view, event) -> {
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) return dragId != null && dragId.equals(event.getLocalState());
            if (dragId == null) return false;
            if (event.getAction() == DragEvent.ACTION_DRAG_LOCATION) {
                if (event.getY() < dp(64)) list.smoothScrollBy(-dp(24), 60);
                else if (event.getY() > list.getHeight() - dp(64)) list.smoothScrollBy(dp(24), 60);
            } else if (event.getAction() == DragEvent.ACTION_DROP) {
                int index = list.pointToPosition((int)event.getX(), (int)event.getY());
                String target = index >= 0 && index < adapter.ids.size() ? adapter.ids.get(index) : null;
                if (target == null || hidden.contains(target)) move(dragId, visible.size() - 1);
                else if (!target.equals(dragId)) move(dragId, visible.indexOf(target));
            } else if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) dragId = null;
            return true;
        });

        // Draft state and the apply action stay fixed below the list.
        footerFrame = column(this);
        View line = new View(this); line.setBackgroundColor(DIVIDER);
        footerFrame.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        footer = gutters(this, AdaptiveLayout.MAX_WIDTH);
        footerFrame.addView(footer, centred(-2, 0));
        footer.setPadding(footer.getPaddingLeft(), dp(4), footer.getPaddingRight(), dp(12));
        draft = Signature.row(this);
        status = text(this, "Loading apps…", CAPTION, TEXT_SECONDARY);
        draft.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        undo = button(this, "Undo", QUIET, true); undo.setContentDescription("Undo last menu change"); undo.setVisibility(View.GONE);
        undo.setOnClickListener(v -> {
            visible = undoVisible; hidden = undoHidden; hiddenOnly = undoHiddenOnly; otherAppsHidden = undoOtherAppsHidden;
            undoVisible = null; undoHidden = null; undoOtherAppsHidden = null; dirty = true; refresh();
        });
        LinearLayout.LayoutParams undoParams = new LinearLayout.LayoutParams(-2, -2);
        undoParams.setMarginStart(dp(8)); draft.addView(undo, undoParams);
        draft.setMinimumHeight(dp(48)); add(footer, draft, 0, 0);
        applyToGlasses = button(this, "Apply to glasses", PRIMARY);
        applyToGlasses.setContentDescription("Apply saved app menu to glasses");
        applyToGlasses.setVisibility(View.GONE); applyToGlasses.setOnClickListener(v -> LauncherReload.confirm(this));
        add(footer, applyToGlasses, 4, 0);
        root.addView(footerFrame, new LinearLayout.LayoutParams(-1, -2));
        // Decor fitting can consume IME insets before they reach this content view.
        // Read the window's original insets after layout, rather than the consumed dispatch.
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (android.os.Build.VERSION.SDK_INT < 30 || root.getRootWindowInsets() == null) return;
            boolean visible = root.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime());
            if (visible != keyboardVisible) {
                keyboardVisible = visible; arrangeLandscape(); updatePanels();
            }
        });
        root.requestFocus();
    }

    private void arrangeLandscape() {
        boolean searchFocused = search.hasFocus();
        bar.title(landscape ? "App menu" : null);
        pageTitle.setVisibility(landscape ? View.GONE : View.VISIBLE);
        pageDescription.setVisibility(landscape ? View.GONE : View.VISIBLE);
        body.setPadding(body.getPaddingLeft(), 0, body.getPaddingRight(), dp(shortLandscape ? 0 : 8));
        ((LinearLayout.LayoutParams) panes.getLayoutParams()).topMargin = dp(landscape && keyboardVisible ? 0 : shortLandscape ? 4 : 8);
        // Keep the focused field in one parent as the IME opens/closes; reparenting an active
        // Android editor can end its input connection even if focus is requested again.
        boolean inlineSearch = landscape;
        search.setPaddingRelative(dp(48), dp(inlineSearch ? 2 : 14), dp(16), dp(inlineSearch ? 2 : 14));
        search.setMinHeight(dp(inlineSearch ? 48 : 52)); search.setMinimumHeight(dp(inlineSearch ? 48 : 52));
        save.setPadding(save.getPaddingLeft(), dp(inlineSearch ? 4 : 12), save.getPaddingRight(), dp(inlineSearch ? 4 : 12));
        bar.content(inlineSearch ? searchField : null);
        LinearLayout parent = expanded && landscape ? editor : body;
        if (!inlineSearch && searchField.getParent() != parent) {
            if (searchField.getParent() != null) ((ViewGroup) searchField.getParent()).removeView(searchField);
            parent.addView(searchField, parent == editor ? 0 : 2);
        }
        if (!inlineSearch) {
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2);
            searchParams.topMargin = dp(landscape ? 0 : 20); searchField.setLayoutParams(searchParams);
        }
        headsetPreview.setCompact(shortLandscape);
        footer.setOrientation(landscape && expanded ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        draft.setLayoutParams(new LinearLayout.LayoutParams(landscape && expanded ? 0 : -1, -2, landscape && expanded ? 1 : 0));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(landscape && expanded ? -2 : -1, -2);
        applyParams.setMarginStart(landscape && expanded ? dp(16) : 0);
        applyParams.topMargin = landscape && expanded ? 0 : dp(4);
        applyToGlasses.setLayoutParams(applyParams);
        footer.setPadding(footer.getPaddingLeft(), dp(4), footer.getPaddingRight(), dp(landscape ? 4 : 12));
        footerFrame.setVisibility(landscape && keyboardVisible ? View.GONE : View.VISIBLE);
        if (searchFocused) search.requestFocus();
        updateStatus();
    }

    private void updateStatus() {
        if (loading) return;
        boolean canApply = !dirty && LauncherReload.canApply(this);
        status.setText(landscape ? dirty ? "Unsaved changes" : canApply ? "Ready to apply to glasses" : visible.size() + " apps in AR Space"
                : dirty ? "Unsaved changes · Save when you’re ready" : canApply ? "Saved menu ready. Apply it to refresh AR Space."
                : "Works without glasses. Saved changes load when AR Space starts.");
    }

    private void refresh() {
        if (adapter == null || loading || store == null) return;
        adapter.ids.clear(); String needle = normalize(query.trim());
        if (!hiddenOnly) for (String id : visible) if (matches(id, needle)) adapter.ids.add(id);
        for (String id : hidden) if (matches(id, needle)) adapter.ids.add(id);
        allTab.setText("All apps · " + apps.size()); hiddenTab.setText("Hidden · " + hidden.size());
        allTab.setSelected(!hiddenOnly); hiddenTab.setSelected(hiddenOnly);
        updatePanels();
        headsetPreview.update(visible, needle, dirty);
        menuActions.setEnabled(true);
        save.setEnabled(dirty); undo.setVisibility(undoVisible == null ? View.GONE : View.VISIBLE);
        boolean canApply = !dirty && LauncherReload.canApply(this);
        applyToGlasses.setVisibility(canApply ? View.VISIBLE : View.GONE);
        updateStatus();
        empty.setText(needle.isEmpty() ? (hiddenOnly ? "No hidden apps\nApps you hide will appear here." : "No apps available") : "No matching apps\nTry an app name or package name.");
        adapter.notifyDataSetChanged();
    }

    private void updatePanels() {
        boolean editing = landscape && keyboardVisible;
        boolean showList = expanded || !previewMode || editing, showPreview = expanded || (previewMode && !editing);
        modes.setVisibility(expanded || editing ? View.GONE : View.VISIBLE);
        listMode.setSelected(!previewMode); headsetMode.setSelected(previewMode);
        editor.setVisibility(showList ? View.VISIBLE : View.GONE);
        tabs.setVisibility(showList && !(landscape && keyboardVisible) ? View.VISIBLE : View.GONE);
        listHint.setVisibility(showList && !shortLandscape ? View.VISIBLE : View.GONE);
        listPanel.setVisibility(showList ? View.VISIBLE : View.GONE);
        previewPanel.setVisibility(showPreview ? View.VISIBLE : View.GONE);
        headsetPreview.setVisibility(showPreview ? View.VISIBLE : View.GONE);
        previewHeading.setVisibility(expanded && !shortLandscape ? View.VISIBLE : View.GONE);
    }

    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT); }
    private boolean matches(String id, String needle) { return apps.get(id).searchText.contains(needle); }
    private void checkpoint() {
        undoVisible = new ArrayList<>(visible); undoHidden = new ArrayList<>(hidden);
        undoHiddenOnly = hiddenOnly; undoOtherAppsHidden = otherAppsHidden; dirty = true;
    }
    private void showMenuActions() {
        hideKeyboard();
        Signature.show(dialog().setTitle("App menu actions").setItems(new String[]{"Hide all apps", "Show all apps"},
                (d, which) -> setAllAppsHidden(which == 0)).setNegativeButton("Cancel", null));
    }
    private void setAllAppsHidden(boolean hide) {
        checkpoint();
        ArrayList<String> all = new ArrayList<>(visible); all.addAll(hidden);
        visible = hide ? new ArrayList<>() : all;
        hidden = hide ? all : new ArrayList<>();
        otherAppsHidden = hide; hiddenOnly = hide;
        refresh();
        Toast.makeText(this, (hide ? "All apps hidden." : "All apps shown.") + " Save to keep this change.", Toast.LENGTH_SHORT).show();
    }
    private void move(String id, int position) {
        int old = visible.indexOf(id); if (old < 0) return;
        int target = Math.max(0, Math.min(position, visible.size() - 1)); if (old == target) return;
        checkpoint(); visible.remove(id); visible.add(target, id); headsetPreview.focusApp(id); refresh();
        list.announceForAccessibility(apps.get(id).name + " moved to position " + (target + 1));
    }
    private void actions(String id) {
        hideKeyboard();
        if (hidden.contains(id)) {
            Signature.show(dialog().setTitle(apps.get(id).name).setItems(new String[]{"Show in app menu"}, (d, which) -> {
                checkpoint(); hidden.remove(id); visible.add(id); refresh();
            }).setNegativeButton("Cancel", null));
        } else {
            Signature.show(dialog().setTitle(apps.get(id).name + " · Position " + (visible.indexOf(id) + 1))
                .setItems(new String[]{"Move to…", "Move to beginning", "Move to end", "Hide from app menu"}, (d, which) -> {
                    if (which == 0) destination(id);
                    else if (which == 1) move(id, 0);
                    else if (which == 2) move(id, visible.size() - 1);
                    else { checkpoint(); visible.remove(id); hidden.add(id); refresh(); }
                }).setNegativeButton("Cancel", null));
        }
    }

    private void destination(String id) {
        LinearLayout body = column(this); body.setPadding(dp(GUTTER), dp(8), dp(GUTTER), 0);
        EditText find = input(this, "Find an app to move before");
        body.addView(find, new LinearLayout.LayoutParams(-1, -2));
        ListView destinations = new ListView(this); destinations.setDivider(null);
        LinearLayout.LayoutParams destinationParams = new LinearLayout.LayoutParams(-1, dp(300));
        destinationParams.topMargin = dp(8); body.addView(destinations, destinationParams);
        List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
        ArrayAdapter<String> choices = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView value = (TextView)super.getView(position, recycled, parent); value.setTextColor(TEXT); return value;
            }
        };
        destinations.setAdapter(choices);
        Runnable filter = () -> {
            ids.clear(); labels.clear(); String needle = normalize(find.getText().toString());
            for (String candidate : visible) if (!candidate.equals(id) && matches(candidate, needle)) {
                ids.add(candidate); labels.add("Before " + apps.get(candidate).name + " · " + (visible.indexOf(candidate) + 1));
            }
            choices.notifyDataSetChanged();
        };
        find.addTextChangedListener(watcher(filter)); filter.run();
        AlertDialog picker = dialog().setTitle("Move " + apps.get(id).name).setView(body).setNegativeButton("Cancel", null).create();
        destinations.setOnItemClickListener((p, view, position, item) -> {
            String before = ids.get(position); int target = visible.indexOf(before);
            if (visible.indexOf(id) < target) target--;
            move(id, target); picker.dismiss();
        });
        Signature.show(picker);
    }

    private void save() {
        try {
            store.save(visible, hidden, otherAppsHidden); dirty = false; otherAppsHidden = null;
            undoVisible = null; undoHidden = null; undoOtherAppsHidden = null; refresh();
            Toast.makeText(this, LauncherReload.canApply(this) ? "Menu saved. Tap Apply to glasses to refresh AR Space."
                    : "Menu saved. Your order will load when AR Space starts.", Toast.LENGTH_LONG).show();
        } catch (Exception error) { Signature.show(dialog().setTitle("Menu not saved").setMessage(error.getMessage()).setPositiveButton("OK", null)); }
    }
    @Override public void onBackPressed() {
        if (!dirty) { super.onBackPressed(); return; }
        Signature.show(dialog().setTitle("Save app menu?").setMessage("Your order and hidden apps have unsaved changes.")
            .setPositiveButton("Save", (d, w) -> { save(); if (!dirty) finish(); })
            .setNegativeButton("Discard", (d, w) -> finish()).setNeutralButton("Keep editing", null));
    }

    private final class MenuAdapter extends BaseAdapter {
        final List<String> ids = new ArrayList<>();
        public int getCount() { return ids.size(); }
        public Object getItem(int position) { return ids.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, ViewGroup parent) {
            String id = ids.get(position); App app = apps.get(id); boolean isHidden = hidden.contains(id);
            LinearLayout row = Signature.row(AppMenuActivity.this); row.setPadding(0, dp(10), 0, dp(10)); row.setMinimumHeight(dp(72));
            row.setBackground(ripple(AppMenuActivity.this, 0));
            TextView number = text(AppMenuActivity.this, isHidden ? "—" : Integer.toString(visible.indexOf(id) + 1), CAPTION, TEXT_TERTIARY);
            number.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(number, new LinearLayout.LayoutParams(dp(32), -2));
            View icon = appIcon(id); if (isHidden) icon.setAlpha(.5f);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40)); iconParams.setMarginEnd(dp(16)); row.addView(icon, iconParams);
            LinearLayout labels = column(AppMenuActivity.this);
            TextView name = text(AppMenuActivity.this, app.name, ROW_TITLE, isHidden ? TEXT_SECONDARY : TEXT); name.setMaxLines(2); labels.addView(name);
            TextView detail = text(AppMenuActivity.this, isHidden ? "Hidden · Tap to restore" : app.builtin ? "AR Space app" : app.id, CAPTION, TEXT_SECONDARY);
            detail.setSingleLine(true); detail.setEllipsize(android.text.TextUtils.TruncateAt.END); add(labels, detail, 3, 0);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            View menu = iconButton(AppMenuActivity.this, "more", "Options for " + app.name + (isHidden ? ", hidden" : ", position " + (visible.indexOf(id) + 1)), v -> actions(id));
            LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(dp(48), dp(48)); menuParams.setMarginEnd(-dp(12));
            row.addView(menu, menuParams);
            row.setOnClickListener(v -> actions(id));
            row.setOnLongClickListener(v -> {
                if (isHidden) return false;
                hideKeyboard(); dragId = id;
                return v.startDragAndDrop(ClipData.newPlainText("App", app.name), new View.DragShadowBuilder(v), id, 0);
            });
            return row;
        }
    }
    private View appIcon(String id) {
        App app = apps.get(id);
        if (app.icon == null) {
            if (app.builtin) {
                try (java.io.InputStream stream = getAssets().open("launcher-icons/" + id + ".png")) {
                    app.icon = BitmapFactory.decodeStream(stream);
                } catch (java.io.IOException error) { android.util.Log.e("PortSettingsUi", "Cannot load launcher artwork for " + id, error); }
            } else {
                try { app.icon = LauncherAppIcon.rasterize(getPackageManager().getApplicationIcon(id)); }
                catch (android.content.pm.PackageManager.NameNotFoundException ignored) { /* App uninstalled while editing. */ }
            }
            if (app.icon == null) app.icon = LauncherAppIcon.rasterize(getPackageManager().getDefaultActivityIcon());
        }
        return new LauncherAppIcon(this, app.icon);
    }
    private static final class App {
        final String id, name, searchText; final boolean builtin; Bitmap icon;
        App(String id, String name, boolean builtin) { this.id = id; this.name = name; this.builtin = builtin; searchText = normalize(name + "\n" + id); }
    }
    private AlertDialog.Builder dialog() { return Signature.dialog(this); }
    private int dp(float value) { return Signature.dp(this, value); }
    private static TextWatcher watcher(Runnable action) { return new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) {} public void onTextChanged(CharSequence s, int start, int before, int count) {} public void afterTextChanged(Editable s) { action.run(); } }; }
    private void hideKeyboard() { ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0); search.clearFocus(); }
}
