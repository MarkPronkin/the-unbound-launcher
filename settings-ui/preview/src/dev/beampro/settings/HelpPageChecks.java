package dev.beampro.settings;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import dev.beampro.settings.preview.SettingsPreviewActivity;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

/** Real Android collection, navigation, share grants and Downloads checks without glasses. */
public final class HelpPageChecks extends Instrumentation {
    int passed;
    HelpPage help;
    DebugData data;
    SettingsPreviewActivity activity;
    Uri saved;
    MenuActivity menu;
    Intent externalLaunch, shareLaunch;
    ActivityMonitor external;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            activity = (SettingsPreviewActivity) startActivitySync(new Intent(getTargetContext(), SettingsPreviewActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            data = DebugData.get(activity);
            File[] previous = new File(getTargetContext().getFilesDir(), "debug/exports").listFiles();
            if (previous != null) for (File file : previous) file.delete();
            external = new ActivityMonitor() {
                @Override public ActivityResult onStartActivity(Intent intent) {
                    if (Intent.ACTION_VIEW.equals(intent.getAction())) { externalLaunch = intent; return new ActivityResult(0, null); }
                    if (Intent.ACTION_CHOOSER.equals(intent.getAction())) { shareLaunch = intent; return new ActivityResult(0, null); }
                    return null;
                }
            };
            addMonitor(external);
            runOnMainSync(() -> data.setEnabled(false));
            menu = MenuChecks.open(this, activity, "Help");
            help = (HelpPage)menu.page;
            runOnMainSync(() -> {
                require(help.isShowing(), "Help opens a menu");
                View page = page();
                require(text(page, "Get help") != null, "Get help is available");
                require(!toggle("debug-collecting").isEnabled(), "collection requires debug mode");
                require(!toggle("debug-enabled").isChecked() && !toggle("debug-collecting").isChecked(), "debugging is opt-in");
                require(!page.findViewWithTag("debug-share").isEnabled(), "share disabled until file exists");
                page.findViewWithTag("get-help").performClick();
                require(externalLaunch != null && Intent.ACTION_VIEW.equals(externalLaunch.getAction())
                        && "https://discord.gg/J7t64xupfQ".equals(externalLaunch.getDataString()), "Get help opens Discord invite");
                toggle("debug-enabled").performClick();
                require(toggle("debug-collecting").isEnabled(), "debug mode enables collection control");
                toggle("debug-collecting").performClick();
                require(data.collecting(), "collection switch starts recording");
            });
            waitForIdleSync();
            runOnMainSync(() -> {
                int[] helpAt = new int[2], debugAt = new int[2];
                page().findViewWithTag("get-help").getLocationOnScreen(helpAt);
                text(page(), "DEBUGGING").getLocationOnScreen(debugAt);
                require(helpAt[1] < debugAt[1], "Get help precedes debugging options");
            });
            String marker = "help-debug-runtime-" + SystemClock.uptimeMillis();
            long deadline = SystemClock.uptimeMillis() + 8000;
            while (!logsContain(marker) && SystemClock.uptimeMillis() < deadline) {
                Log.i("HelpPageChecks", marker); SystemClock.sleep(100);
            }
            require(logsContain(marker), "real app-UID logcat data captured");
            DebugData.connectionEvent(activity,"one-s-first","begin","model=9; fixture first attempt");
            DebugData.connectionEvent(activity,"one-s-first","sdk_start","fixture passive state capture");
            Log.i("XREAL","[NRService] NRSDK Version: 3.1.2.fixture");
            Log.i("XREAL","[NRService] HMD version: system:3563, software:fixture");
            Log.e("XREAL","[NRService] 333 get rsp timeout");
            Log.d("myGlasses","(NRServiceControl.java:263)#startSdk | [startupState=, -1]");
            DebugData.connectionEvent(activity,"one-s-first","failure","fixture startup-state timeout");
            deadline=SystemClock.uptimeMillis()+5000;
            while(!logsContain("333 get rsp timeout")&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50);
            require(logsContain("333 get rsp timeout"),"native-style timeout reaches the startup collector");
            DebugData.connectionEvent(activity,"one-s-retry","begin","fixture retry; 1s-second-attempt-marker");
            DebugData.connectionEvent(activity,"one-s-retry","sdk_start","fixture retry");
            data.files.submit(() -> {}).get(30, java.util.concurrent.TimeUnit.SECONDS);
            DebugData.connectionFailure(activity, "test USB network disconnect");
            data.files.submit(() -> {}).get(30, java.util.concurrent.TimeUnit.SECONDS);
            runOnMainSync(() -> {
                toggle("debug-enabled").performClick();
                require(!data.enabled() && !data.collecting(), "turning debug mode off stops collection");
            });
            long size = data.bytes();
            DebugData.connectionEvent(activity,"one-s-stopped","failure","opt-out-startup-marker");
            Log.i("HelpPageChecks", "after-collection-stop"); SystemClock.sleep(150);
            require(data.bytes() == size, "no new bytes after stop");
            runOnMainSync(() -> page().findViewWithTag("debug-create").performClick());
            await(() -> page().findViewWithTag("debug-share").isEnabled(), "export ready through UI");
            File export = (File) field(help, "export");
            require(export.length() > 0 && export.length() <= 15_000_000, "ZIP meets 15 MB limit");
            try (ZipFile zip = new ZipFile(export)) {
                require(read(zip.getInputStream(zip.getEntry("app.log"))).contains(marker), "export contains reproduced event");
                require(read(zip.getInputStream(zip.getEntry("device-and-session.txt"))).contains("Android:"), "device details in report");
                String network = read(zip.getInputStream(zip.getEntry("network-diagnostics.txt")));
                require(network.contains("test USB network disconnect") && network.contains("app UID"), "failure network snapshot survives stopping collection");
                require(network.contains("169.254.2.1") && network.contains("Helper diagnostics unavailable"), "missing or older helper preserves ping diagnostics and export");
                String link = read(zip.getInputStream(zip.getEntry("display-link.txt")));
                require(link.contains("baseline before reproducing") && link.contains("test USB network disconnect"), "baseline and failure link snapshots survive stopping collection");
                String startup=read(zip.getInputStream(zip.getEntry("glasses-startup.txt")));
                require(startup.contains("333 get rsp timeout")&&startup.contains("3.1.2.fixture")&&startup.contains("system:3563"),"export retains SDK, firmware and native timeout evidence");
                require(startup.contains("First failure window")&&startup.contains("one-s-first")&&startup.contains("one-s-retry"),"first failure remains alongside a separately identified retry");
                require(!startup.contains("opt-out-startup-marker"),"disabled collection cannot add startup events");
            }
            runOnMainSync(() -> data.setEnabled(true));
            File fresh = data.files.submit(() -> data.export()).get(30, java.util.concurrent.TimeUnit.SECONDS);
            try (ZipFile zip = new ZipFile(fresh)) {
                String network = read(zip.getInputStream(zip.getEntry("network-diagnostics.txt")));
                require(network.contains("Debug export requested") && network.contains("test USB network disconnect"), "fresh export keeps both failure and current network evidence");
                require(read(zip.getInputStream(zip.getEntry("display.txt"))).contains("Helper display diagnostics unavailable"), "missing helper leaves an explanation in display.txt");
                require(read(zip.getInputStream(zip.getEntry("surfaceflinger.txt"))).contains("Helper display diagnostics unavailable"), "missing helper leaves an explanation in surfaceflinger.txt");
                require(read(zip.getInputStream(zip.getEntry("display-link.txt"))).contains("Debug export requested"), "new ZIP includes current display-link evidence or its availability explanation");
                require(read(zip.getInputStream(zip.getEntry("glasses-control.txt"))).contains("Glasses control channel"),"control snapshot includes unavailable-helper evidence in a usable export");
            }
            runOnMainSync(() -> data.setEnabled(false));
            runOnMainSync(() -> page().findViewWithTag("debug-share").performClick());
            Intent share = shareLaunch.getParcelableExtra(Intent.EXTRA_INTENT);
            require(Intent.ACTION_SEND.equals(share.getAction()) && "application/zip".equals(share.getType()), "Share opens Android share chooser with ZIP");
            require((share.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
                    && (share.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0, "temporary read permission only");
            Uri shared = share.getParcelableExtra(Intent.EXTRA_STREAM);
            require(shared.equals(share.getClipData().getItemAt(0).getUri()), "URI also in ClipData for grant propagation");
            ProviderInfo provider = getTargetContext().getPackageManager().resolveContentProvider(shared.getAuthority(), 0);
            require(!provider.exported && provider.grantUriPermissions, "provider is private with explicit URI grants");
            try (InputStream input = getTargetContext().getContentResolver().openInputStream(shared)) {
                require(digest(input).equals(digest(new FileInputStream(export))), "shared content matches exported file");
            }
            fails(() -> getTargetContext().getContentResolver().openFileDescriptor(shared, "w"), "provider refuses writes");
            fails(() -> getTargetContext().getContentResolver().openFileDescriptor(shared.buildUpon().path("/../shared_prefs/unbound_debug.xml").build(), "r"), "provider refuses traversal");
            runOnMainSync(() -> page().findViewWithTag("debug-save").performClick());
            await(() -> text(page(), "Saved to Downloads: " + export.getName()) != null, "save completes through UI");
            try (Cursor cursor = getTargetContext().getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads._ID, MediaStore.Downloads.IS_PENDING},
                    MediaStore.Downloads.DISPLAY_NAME + "=?", new String[]{export.getName()}, null)) {
                require(cursor != null && cursor.moveToFirst(), "file exists in Downloads");
                require(cursor.getInt(1) == 0, "Downloads file published after complete write");
                saved = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0));
            }
            try (InputStream input = getTargetContext().getContentResolver().openInputStream(saved)) {
                require(digest(input).equals(digest(new FileInputStream(export))), "Downloads copy matches exported ZIP");
            }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            await(() -> !help.isShowing(), "system back exit animation finishes");
            require(!help.isShowing() && !activity.isFinishing(), "system back returns to settings");
            MenuChecks.awaitClosed(this, menu);
            menu = MenuChecks.open(this, activity, "Help");
            help = (HelpPage)menu.page;
            runOnMainSync(() -> {
                require(page().findViewWithTag("debug-share").isEnabled(), "completed export remains available after reopening Help");
                page().findViewWithTag("help-back").performClick();
            });
            await(() -> !help.isShowing(), "toolbar exit animation finishes");
            MenuChecks.awaitClosed(this, menu);
            require(!activity.isFinishing(), "toolbar Back returns to settings");
            runOnMainSync(activity::finish);
            await(() -> activity.isDestroyed(), "settings closes without a leaked menu window");
            result.putString("stream", "Help/debug runtime checks passed: " + passed + "\n"); finish(-1, result);
        } catch (Throwable error) {
            Log.e("HelpPageChecks", "Failed after " + passed, error);
            result.putString("stream", "Help/debug failed after " + passed + ": " + error + "\n"); finish(0, result);
        } finally {
            if (external != null) removeMonitor(external);
            if (menu != null) runOnMainSync(menu::finish);
            if (activity != null) runOnMainSync(activity::finish);
            if (data != null) runOnMainSync(() -> data.setEnabled(false));
            if (saved != null) getTargetContext().getContentResolver().delete(saved, null, null);
        }
    }
    View page() { return help.getWindow().getDecorView(); }
    CompoundButton toggle(String tag) { return page().findViewWithTag(tag); }
    boolean logsContain(String text) throws Exception {
        File[] logs = new File(getTargetContext().getFilesDir(), "debug/logs").listFiles();
        if (logs != null) for (File file : logs) if (read(new FileInputStream(file)).contains(text)) return true;
        return false;
    }
    interface Check { boolean done(); }
    void await(Check check, String message) {
        AtomicBoolean done = new AtomicBoolean(); long deadline = SystemClock.uptimeMillis() + 10000;
        do { runOnMainSync(() -> done.set(check.done())); if (!done.get()) SystemClock.sleep(50); }
        while (!done.get() && SystemClock.uptimeMillis() < deadline);
        require(done.get(), message);
    }
    interface Action { ParcelFileDescriptor run() throws Exception; }
    void fails(Action action, String message) throws Exception {
        try (ParcelFileDescriptor ignored = action.run()) { throw new AssertionError(message); }
        catch (java.io.FileNotFoundException expected) { passed++; }
    }
    static String read(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }
    static String digest(InputStream input) throws Exception {
        try (InputStream stream = input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            return java.util.Arrays.toString(digest.digest());
        }
    }
    static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    static View row(View label) { return (View)label.getParent().getParent(); }
    static TextView text(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView)view).getText())) return (TextView)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            TextView found = text(((ViewGroup)view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
}
