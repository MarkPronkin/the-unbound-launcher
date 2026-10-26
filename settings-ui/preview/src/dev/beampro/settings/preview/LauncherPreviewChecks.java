package dev.beampro.settings.preview;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import dev.beampro.settings.AppMenuActivity;
import dev.beampro.settings.HeadsetMenuPreview;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Checks the rendered grid, page boundaries, live edits, search, and recreation. */
public final class LauncherPreviewChecks extends Instrumentation {
    int passed;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        File file = new File(getTargetContext().getExternalFilesDir(null), "AppDetails/EPHInfoConfig/LayoutConfig.json");
        byte[] backup = null; boolean existed = file.exists();
        try {
            new dev.beampro.settings.AppMenuStore(getTargetContext());
            if (existed) backup = Files.readAllBytes(file.toPath());
            file.getParentFile().mkdirs(); Files.write(file.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
            AppMenuActivity activity = open(); awaitCatalog(activity); final AppMenuActivity initial = activity;
            List<String> original = new ArrayList<>(ids(activity, "visible")); require(original.size() > 10, "multiple-page real app catalog");
            runOnMainSync(() -> {
                HeadsetMenuPreview preview = preview(initial);
                require(preview.getVisibility() == View.GONE, "list remains default");
                control(initial, "Show headset preview").performClick();
                require(preview.getVisibility() == View.VISIBLE, "preview opens without glasses");
                require(!control(initial, "Save app menu").isEnabled(), "opening preview does not change saved order");
                require(tiles(preview).equals(original.subList(0, 10)), "first page follows exact global order");
                require(tagged(preview, original.get(0)).getContentDescription().toString().endsWith("row 1, column 1"), "upper-left starting slot");
                require(tagged(preview, original.get(9)).getContentDescription().toString().endsWith("row 2, column 5"), "row-major fill through tenth slot");
                control(initial, "Next headset page").performClick();
                require(preview.getPage() == 1, "next page navigation");
                require(tiles(preview).get(0).equals(original.get(10)), "eleventh app starts next page");
                require(!control(initial, "Next headset page").isEnabled(), "last page disables next");
                require(control(initial, "Previous headset page").isEnabled(), "last page can go back");
                control(initial, "Previous headset page").performClick();
                require(preview.getPage() == 0, "previous page navigation");
                ((EditText)control(initial, "Search apps or package names")).setText(original.get(original.size() - 1));
                require(preview.getPage() == (original.size() - 1) / 10, "search locates app on its real page");
                require(tiles(preview).size() > 1, "search retains surrounding apps");
                require(tagged(preview, original.get(original.size() - 1)) != null, "matching app remains in its actual slot");
                ((EditText)control(initial, "Search apps or package names")).setText("");
                try { AppMenuChecks.invoke(initial, "move", new Class[]{String.class, int.class}, original.get(0), original.size() - 1); }
                catch (Exception e) { throw new RuntimeException(e); }
                require(tiles(preview).get(tiles(preview).size() - 1).equals(original.get(0)), "unsaved move updates preview immediately");
                require(control(initial, "Save app menu").isEnabled(), "preview reflects draft state");
                control(initial, "Undo last menu change").performClick(); preview.showPage(0);
                require(tiles(preview).get(0).equals(original.get(0)), "undo restores rendered order");
                tagged(preview, original.get(0)).performClick();
            });
            waitForIdleSync();
            for (int i = 0; i < 4; i++) sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN);
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER); waitForIdleSync();
            runOnMainSync(() -> {
                HeadsetMenuPreview preview = preview(initial);
                require(!tiles(preview).contains(original.get(0)), "tile menu hides app from preview");
                require(tiles(preview).get(0).equals(original.get(1)), "hide compacts remaining icons");
                require(tiles(preview).get(9).equals(original.get(10)), "hide pulls next-page app into vacated page");
                preview.showPage(preview.pageCount() - 1);
            });
            require(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).equals("{}"), "preview edits never auto-save");
            ActivityMonitor monitor = addMonitor(AppMenuActivity.class.getName(), null, false);
            runOnMainSync(initial::recreate);
            AppMenuActivity recreated = (AppMenuActivity)waitForMonitorWithTimeout(monitor, 5000); removeMonitor(monitor);
            require(recreated != null && recreated != initial, "Activity recreated"); awaitCatalog(recreated);
            runOnMainSync(() -> {
                HeadsetMenuPreview preview = preview(recreated);
                require(preview.getVisibility() == View.VISIBLE, "preview mode survives recreation");
                require(preview.getPage() == preview.pageCount() - 1, "preview page survives recreation");
                try {
                    require(ids(recreated, "hidden").contains(original.get(0)), "hidden draft survives recreation");
                    List<String> visible = ids(recreated, "visible"), hidden = ids(recreated, "hidden"); hidden.addAll(visible); visible.clear();
                    AppMenuChecks.invoke(recreated, "refresh", new Class[]{});
                } catch (Exception e) { throw new RuntimeException(e); }
                require(tiles(preview).isEmpty(), "hiding all apps produces empty preview");
                require(preview.getPage() == 0 && preview.pageCount() == 1, "empty preview clamps page");
                require(!control(recreated, "Next headset page").isEnabled(), "empty preview has no next page");
                require(!control(recreated, "Previous headset page").isEnabled(), "empty preview has no previous page");
                require(AppMenuChecks.findDescription(preview, "Headset app grid, five columns and two rows").getVisibility() == View.GONE, "empty grid hidden");
                recreated.finish();
            });
            result.putString("stream", "Headset preview runtime checks passed: " + passed + "\n"); finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "Headset preview check failed after " + passed + ": " + error + "\n"); finish(0, result);
        } finally {
            try { if (existed) Files.write(file.toPath(), backup); else file.delete(); } catch (Exception ignored) {}
        }
    }
    AppMenuActivity open() { return (AppMenuActivity)startActivitySync(new Intent(getTargetContext(), AppMenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    void awaitCatalog(AppMenuActivity activity) throws Exception {
        java.lang.reflect.Field field = AppMenuActivity.class.getDeclaredField("loading"); field.setAccessible(true);
        for (int i = 0; i < 100; i++) { waitForIdleSync(); boolean[] loading = {true}; runOnMainSync(() -> { try { loading[0] = field.getBoolean(activity); } catch (Exception e) { throw new RuntimeException(e); } }); if (!loading[0]) return; Thread.sleep(50); }
        throw new AssertionError("catalog timeout");
    }
    @SuppressWarnings("unchecked") static List<String> ids(AppMenuActivity activity, String name) throws Exception { java.lang.reflect.Field field = AppMenuActivity.class.getDeclaredField(name); field.setAccessible(true); return (List<String>)field.get(activity); }
    static View control(AppMenuActivity activity, String desc) { View value = AppMenuChecks.findDescription(activity, desc); if (value == null) throw new AssertionError("Missing " + desc); return value; }
    static HeadsetMenuPreview preview(View root) { if (root instanceof HeadsetMenuPreview) return (HeadsetMenuPreview)root; if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { HeadsetMenuPreview found = preview(((ViewGroup)root).getChildAt(i)); if (found != null) return found; } return null; }
    static HeadsetMenuPreview preview(AppMenuActivity activity) { return preview(activity.getWindow().getDecorView()); }
    static View tagged(View root, String id) { if (id.equals(root.getTag())) return root; if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { View found = tagged(((ViewGroup)root).getChildAt(i), id); if (found != null) return found; } return null; }
    static List<String> tiles(View root) { List<String> values = new ArrayList<>(); if (root.getTag() instanceof String) values.add((String)root.getTag()); if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) values.addAll(tiles(((ViewGroup)root).getChildAt(i))); return values; }
    void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
}
