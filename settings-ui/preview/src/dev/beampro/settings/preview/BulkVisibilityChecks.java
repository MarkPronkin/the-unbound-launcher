package dev.beampro.settings.preview;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import dev.beampro.settings.AppMenuActivity;
import dev.beampro.settings.AppMenuStore;
import dev.beampro.settings.HeadsetMenuPreview;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Bulk actions through actual dialogs, with persistence, Undo, filtering and native sentinels. */
public final class BulkVisibilityChecks extends Instrumentation {
    int passed;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        File file = new File(getTargetContext().getExternalFilesDir(null), "AppDetails/EPHInfoConfig/LayoutConfig.json");
        SharedPreferences preferences = getTargetContext().getSharedPreferences("beampro_app_menu", 0);
        String previousOrder = preferences.getString("hidden_order", null); byte[] backup = null; boolean existed = false;
        try {
            new AppMenuStore(getTargetContext()); existed = file.exists(); if (existed) backup = Files.readAllBytes(file.toPath());
            file.getParentFile().mkdirs();
            String seed = "{\"PhotoWidgetContent\":0,\"TutorialWidgetContent\":1,\"LiveWindowWidgetContent\":2147483647,\"missing.visible\":42,\"missing.hidden\":2147483647}";
            Files.write(file.toPath(), seed.getBytes(StandardCharsets.UTF_8));
            AppMenuActivity first = open(); await(first);
            List<String> originalVisible = new ArrayList<>(ids(first, "visible")), originalHidden = new ArrayList<>(ids(first, "hidden"));
            List<String> all = new ArrayList<>(originalVisible); all.addAll(originalHidden);
            require(originalVisible.get(0).equals("PhotoWidgetContent"), "fixture imports custom order");
            require(originalHidden.contains("LiveWindowWidgetContent"), "fixture starts partially hidden");
            runOnMainSync(() -> ((EditText)control(first, "Search apps or package names")).setText("TutorialWidgetContent"));
            action(first, true);
            runOnMainSync(() -> {
                require(ids(first, "visible").isEmpty(), "Hide all ignores active search");
                require(ids(first, "hidden").equals(all), "Hide all retains curated order before previously hidden apps");
                require(control(first, "Save app menu").isEnabled(), "bulk hide is an unsaved draft");
                control(first, "Show headset preview").performClick();
                HeadsetMenuPreview preview = LauncherPreviewChecks.preview(first);
                require(LauncherPreviewChecks.tiles(preview).isEmpty(), "preview immediately removes every icon");
                require(preview.pageCount() == 1 && preview.getPage() == 0, "empty preview has one valid page");
                control(first, "Undo last menu change").performClick();
                require(ids(first, "visible").equals(originalVisible), "Undo restores original visible order");
                require(ids(first, "hidden").equals(originalHidden), "Undo restores prior hidden choices");
            });
            require(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).equals(seed), "bulk draft and Undo never auto-save");
            action(first, true);
            runOnMainSync(() -> control(first, "Save app menu").performClick());
            JSONObject hidden = saved(file);
            for (String id : all) require(hidden.getInt(id) == Integer.MAX_VALUE, "native hidden sentinel for " + id);
            require(hidden.getInt("missing.visible") == Integer.MAX_VALUE, "bulk hide includes unavailable saved apps");
            runOnMainSync(first::finish);
            AppMenuActivity second = open(); await(second);
            require(ids(second, "visible").isEmpty(), "all-hidden state survives reopening");
            require(ids(second, "hidden").equals(all), "hidden order survives saved-file reload");
            runOnMainSync(() -> ((EditText)control(second, "Search apps or package names")).setText("PhotoWidgetContent"));
            action(second, false);
            runOnMainSync(() -> {
                require(ids(second, "visible").equals(all), "Show all ignores search and preserves order");
                require(ids(second, "hidden").isEmpty(), "Show all restores every hidden app");
                control(second, "Show headset preview").performClick();
                require(!LauncherPreviewChecks.tiles(LauncherPreviewChecks.preview(second)).isEmpty(), "Show all repopulates preview");
                control(second, "Undo last menu change").performClick();
                require(ids(second, "visible").isEmpty() && ids(second, "hidden").equals(all), "Undo reverses Show all");
            });
            action(second, false);
            ActivityMonitor monitor = addMonitor(AppMenuActivity.class.getName(), null, false);
            runOnMainSync(second::recreate);
            AppMenuActivity recreated = (AppMenuActivity)waitForMonitorWithTimeout(monitor, 5000); removeMonitor(monitor); require(recreated != null, "draft recreated"); await(recreated);
            runOnMainSync(() -> {
                require(ids(recreated, "visible").equals(all), "bulk restore draft survives recreation");
                require(Boolean.FALSE.equals(field(recreated, "otherAppsHidden")), "restore scope survives recreation");
                control(recreated, "Save app menu").performClick();
            });
            JSONObject shown = saved(file);
            for (int i = 0; i < all.size(); i++) require(shown.getInt(all.get(i)) == i, "restored native position " + i);
            require(shown.getInt("missing.hidden") < Integer.MAX_VALUE, "Show all restores unavailable hidden app choices");
            require(shown.getInt("missing.visible") < Integer.MAX_VALUE, "Show all reverses unavailable bulk hiding");
            AppMenuStore reopened = new AppMenuStore(getTargetContext());
            require(reopened.order(all).equals(all), "full hide/save/reopen/show/save round trip preserves order");
            runOnMainSync(recreated::finish);
            result.putString("stream", "Bulk visibility runtime checks passed: " + passed + "\n"); finish(-1, result);
        } catch (Throwable error) { result.putString("stream", "Bulk visibility check failed after " + passed + ": " + error + "\n"); finish(0, result); }
        finally {
            try { if (existed) Files.write(file.toPath(), backup); else file.delete(); } catch (Exception ignored) {}
            SharedPreferences.Editor editor = preferences.edit(); if (previousOrder == null) editor.remove("hidden_order"); else editor.putString("hidden_order", previousOrder); editor.commit();
        }
    }
    void action(AppMenuActivity activity, boolean hide) {
        runOnMainSync(() -> control(activity, "App menu actions").performClick()); waitForIdleSync();
        int key = hide ? android.view.KeyEvent.KEYCODE_DPAD_UP : android.view.KeyEvent.KEYCODE_DPAD_DOWN;
        sendKeyDownUpSync(key); sendKeyDownUpSync(key); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER); waitForIdleSync();
    }
    AppMenuActivity open() { return (AppMenuActivity)startActivitySync(new Intent(getTargetContext(), AppMenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    void await(AppMenuActivity activity) throws Exception {
        for (int i = 0; i < 100; i++) { waitForIdleSync(); boolean[] loading = {true}; runOnMainSync(() -> loading[0] = (Boolean)field(activity, "loading")); if (!loading[0]) return; Thread.sleep(50); } throw new AssertionError("catalog timeout");
    }
    static Object field(Object owner, String name) { try { java.lang.reflect.Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); } catch (Exception error) { throw new RuntimeException(error); } }
    @SuppressWarnings("unchecked") static List<String> ids(AppMenuActivity activity, String name) { return (List<String>)field(activity, name); }
    static View control(AppMenuActivity activity, String text) { View view = AppMenuChecks.findDescription(activity, text); if (view == null) throw new AssertionError("Missing " + text); return view; }
    static JSONObject saved(File file) throws Exception { return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
    void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
}
