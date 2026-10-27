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
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            activity = (SettingsPreviewActivity) startActivitySync(new Intent(getTargetContext(), SettingsPreviewActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            data = DebugData.get(activity);
            runOnMainSync(() -> {
                data.setEnabled(false);
                row(text(activity.findViewById(android.R.id.content), "Help")).performClick();
                try { help = (HelpPage) field(field(activity.findViewById(0x00bea001), "state"), "helpPage"); }
                catch (Exception error) { throw new RuntimeException(error); }
                require(help.isShowing(), "Help opens a menu");
                View page = page();
                require(text(page, "Get help") != null, "Get help is available");
                require(!toggle("debug-collecting").isEnabled(), "collection requires debug mode");
                require(!toggle("debug-enabled").isChecked() && !toggle("debug-collecting").isChecked(), "debugging is opt-in");
                require(!page.findViewWithTag("debug-share").isEnabled(), "share disabled until file exists");
                page.findViewWithTag("get-help").performClick();
                require(activity.externalLaunch != null && Intent.ACTION_VIEW.equals(activity.externalLaunch.getAction())
                        && "https://discord.gg/J7t64xupfQ".equals(activity.externalLaunch.getDataString()), "Get help opens Discord invite");
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
            DebugData.connectionFailure(activity, "test USB network disconnect");
            data.files.submit(() -> {}).get(30, java.util.concurrent.TimeUnit.SECONDS);
            runOnMainSync(() -> {
                toggle("debug-enabled").performClick();
                require(!data.enabled() && !data.collecting(), "turning debug mode off stops collection");
            });
            long size = data.bytes();
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
            }
            runOnMainSync(() -> data.setEnabled(true));
            File fresh = data.files.submit(() -> data.export()).get(30, java.util.concurrent.TimeUnit.SECONDS);
            try (ZipFile zip = new ZipFile(fresh)) {
                String network = read(zip.getInputStream(zip.getEntry("network-diagnostics.txt")));
                require(network.contains("Debug export requested") && network.contains("test USB network disconnect"), "fresh export keeps both failure and current network evidence");
                require(read(zip.getInputStream(zip.getEntry("display.txt"))).contains("Helper display diagnostics unavailable"), "missing helper leaves an explanation in display.txt");
                require(read(zip.getInputStream(zip.getEntry("surfaceflinger.txt"))).contains("Helper display diagnostics unavailable"), "missing helper leaves an explanation in surfaceflinger.txt");
            }
            runOnMainSync(() -> data.setEnabled(false));
            runOnMainSync(() -> page().findViewWithTag("debug-share").performClick());
            Intent share = activity.shareLaunch.getParcelableExtra(Intent.EXTRA_INTENT);
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
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitForIdleSync();
            require(!help.isShowing() && !activity.isFinishing(), "system back returns to settings");
            runOnMainSync(() -> {
                row(text(activity.findViewById(android.R.id.content), "Help")).performClick();
                page().findViewWithTag("help-back").performClick();
                require(!help.isShowing(), "toolbar back returns to settings");
                row(text(activity.findViewById(android.R.id.content), "Help")).performClick();
                activity.finish();
            });
            await(() -> activity.isDestroyed() && !help.isShowing(), "Help dismissed with owning activity");
            result.putString("stream", "Help/debug runtime checks passed: " + passed + "\n"); finish(-1, result);
        } catch (Throwable error) {
            Log.e("HelpPageChecks", "Failed after " + passed, error);
            result.putString("stream", "Help/debug failed after " + passed + ": " + error + "\n"); finish(0, result);
        } finally {
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
