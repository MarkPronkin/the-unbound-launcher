package dev.beampro.settings;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.CompoundButton;
import dev.beampro.settings.preview.SettingsPreviewActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline HTTP fixtures plus the actual About page, APK verifier, and URI boundary. */
public final class AppUpdateChecks extends Instrumentation {
    private int passed;
    private Bundle arguments;
    interface Check { void run() throws Exception; }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (Boolean.parseBoolean(arguments.getString("about_only", "false"))) aboutChecks();
            else { releaseChecks(); downloadChecks(); packageChecks(); controllerChecks(); aboutChecks(); }
            result.putString("stream", "App updater checks passed: " + passed + "\n"); finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "App updater checks failed after " + passed + ": " + error + "\n"); finish(0, result);
        }
    }

    private JSONObject release(String tag) throws Exception {
        JSONObject asset = new JSONObject().put("name", "the-unbound-launcher-" + tag + ".apk")
                .put("state", "uploaded").put("size", 3).put("browser_download_url", url(tag));
        return new JSONObject().put("tag_name", tag).put("published_at", "2026-10-27T00:00:00Z")
                .put("prerelease", tag.contains("alpha")).put("assets", new JSONArray().put(asset));
    }
    private static String url(String tag) { return UpdateClient.REPOSITORY + "/releases/download/" + tag + "/the-unbound-launcher.apk"; }
    private void releaseChecks() throws Exception {
        JSONObject old = release("v0.1.0-alpha"), newer = release("v0.1.1-alpha"), highest = release("v0.2.0-alpha");
        UpdateClient.Release selected = UpdateClient.select(new JSONArray().put(newer).put(old).put(highest), "0.1.0 alpha");
        require(selected.tag.equals("v0.2.0-alpha"), "highest version wins irrespective of API ordering");
        require(UpdateClient.select(new JSONArray().put(old), "0.1.0 alpha") == null, "same version is not an update");
        require(UpdateClient.select(new JSONArray().put(highest), "0.1.0") == null, "stable installs do not receive alpha releases");
        highest.put("draft", true);
        require(UpdateClient.select(new JSONArray().put(highest).put(newer), "0.1.0 alpha").tag.equals("v0.1.1-alpha"), "draft releases skipped");
        newer.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/app.apk");
        require(UpdateClient.select(new JSONArray().put(newer), "0.1.0 alpha") == null, "foreign download rejected");
        newer = release("v0.1.1-alpha"); newer.getJSONArray("assets").getJSONObject(0).put("state", "new");
        require(UpdateClient.select(new JSONArray().put(newer), "0.1.0 alpha") == null, "unfinished upload skipped");
        newer = release("v0.1.1-alpha"); newer.put("assets", new JSONArray());
        require(UpdateClient.select(new JSONArray().put(newer), "0.1.0 alpha") == null, "release without APK skipped");
        require(!UpdateClient.validAssetUrl("http://github.com/MarkPronkin/the-unbound-launcher/releases/download/v1/a.apk"), "HTTPS required");
        require(!UpdateClient.validAssetUrl("https://github.com:123/MarkPronkin/the-unbound-launcher/releases/download/v1/a.apk"), "unexpected port rejected");
        require(!UpdateClient.validAssetUrl("https://github.com/another/repository/releases/download/v1/a.apk"), "foreign repository rejected");
    }

    private void downloadChecks() throws Exception {
        File target = new File(getTargetContext().getCacheDir(), "test-update.apk");
        String digest = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        UpdateClient.Release release = new UpdateClient.Release("v0.1.1-alpha", "", url("v0.1.1-alpha"), digest, 3);
        UpdateClient good = new UpdateClient(u -> new Response(u, 200, "abc"));
        AtomicInteger progress = new AtomicInteger();
        good.download(release, target, (received, total) -> progress.set((int) received));
        require(target.length() == 3 && progress.get() == 3, "verified download and byte progress"); target.delete();
        fails(() -> new UpdateClient(u -> new Response(u, 200, "xyz")).download(release, target, (a,b) -> {}), "checksum");
        require(!target.exists(), "corrupt download removed");
        Response truncated = new Response(new URL(release.url), 200, "ab"); truncated.length = 3;
        fails(() -> new UpdateClient(u -> truncated).download(release, target, (a,b) -> {}), "interrupted");
        require(!target.exists(), "partial download removed");
        fails(() -> good.download(release, target, (a,b) -> { throw new IOException("cancelled"); }), "cancelled");
        require(!target.exists(), "cancelled download removed");
        fails(() -> new UpdateClient(u -> new Response(u, 429, "")).latest("0.1.0 alpha"), "limiting");
        fails(() -> new UpdateClient(u -> new Response(u, 200, "invalid json")).latest("0.1.0 alpha"), "invalid release list");
        Response redirect = new Response(new URL(release.url), 302, ""); redirect.location = "http://example.com/app.apk";
        fails(() -> new UpdateClient(u -> redirect).download(release, target, (a,b) -> {}), "unsafe");
        String json = new JSONArray().put(release("v0.1.1-alpha")).toString();
        require(new UpdateClient(u -> new Response(u, 200, json)).latest("0.1.0 alpha").tag.equals("v0.1.1-alpha"), "API response parsed through real transport");
    }

    private void packageChecks() throws Exception {
        Context context = getTargetContext();
        File installed = new File(context.getApplicationInfo().sourceDir);
        fails(() -> AppUpdater.verifyApk(context, installed), "not newer");
        File valid = new File(arguments.getString("valid"));
        android.content.pm.PackageInfo installedInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
        android.content.pm.PackageInfo archiveInfo = context.getPackageManager().getPackageArchiveInfo(valid.getAbsolutePath(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES | android.content.pm.PackageManager.GET_SIGNATURES);
        require(installedInfo.signingInfo != null, "installed signing info present");
        require(archiveInfo.signingInfo != null, "archive signing info present");
        require(AppUpdater.verifyApk(context, valid).versionName.equals("0.1.1 alpha"), "newer signed APK accepted");
        fails(() -> AppUpdater.verifyApk(context, new File(arguments.getString("foreign"))), "signing certificate");
        fails(() -> AppUpdater.verifyApk(context, new File(arguments.getString("package"))), "not an update");
        File staged = AppUpdater.readyFile(context); staged.getParentFile().mkdirs();
        Files.copy(valid.toPath(), staged.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (android.os.ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(UpdateFileProvider.uri(context), "r")) {
            require(fd != null && fd.getStatSize() == staged.length(), "provider reads exactly the staged APK");
        }
        fails(() -> context.getContentResolver().openFileDescriptor(UpdateFileProvider.uri(context).buildUpon().path("/../shared_prefs/secrets.xml").build(), "r"), "Unknown update file");
        fails(() -> context.getContentResolver().openFileDescriptor(UpdateFileProvider.uri(context), "w"), "read-only");
        staged.delete();
    }

    private void aboutChecks() throws Exception {
        SettingsPreviewActivity activity = (SettingsPreviewActivity) startActivitySync(new Intent(getTargetContext(), SettingsPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        AppUpdater updater = AppUpdater.get(activity);
        runOnMainSync(() -> { updater.setEnabled(true); updater.setOnStartup(true); });
        MenuActivity about = MenuChecks.open(this, activity, "About");
        runOnMainSync(() -> {
            require(about.findViewById(android.R.id.content).findViewWithTag("updates-check") != null, "manual check in About");
            CompoundButton startup = about.getWindow().getDecorView().findViewWithTag("updates-startup");
            require(startup.isChecked(), "startup checks enabled by default"); startup.performClick();
            require(!updater.onStartup(), "startup preference saved");
            CompoundButton enabled = about.getWindow().getDecorView().findViewWithTag("updates-enabled"); enabled.performClick();
            require(!updater.enabled(), "updater disabled preference saved");
            require(!about.getWindow().getDecorView().findViewWithTag("updates-check").isEnabled(), "manual checks disabled with updater");
            require(!about.getWindow().getDecorView().findViewWithTag("updates-startup").isEnabled(), "startup toggle disabled with updater");
            about.finish();
        });
        MenuChecks.awaitClosed(this, about);
        MenuActivity reopened = MenuChecks.open(this, activity, "About");
        runOnMainSync(() -> {
            require(!((CompoundButton)reopened.getWindow().getDecorView().findViewWithTag("updates-enabled")).isChecked(), "preference survives reopening");
            ((CompoundButton)reopened.getWindow().getDecorView().findViewWithTag("updates-enabled")).performClick();
            updater.setOnStartup(true); reopened.finish(); activity.finish();
        });
    }

    private void controllerChecks() throws Exception {
        Context context = getTargetContext().getApplicationContext();
        byte[] apk = Files.readAllBytes(new File(arguments.getString("valid")).toPath());
        JSONObject metadata = release("v0.1.1-alpha");
        metadata.getJSONArray("assets").getJSONObject(0).put("size", apk.length);
        String json = new JSONArray().put(metadata).toString();
        AtomicInteger checks = new AtomicInteger(), downloads = new AtomicInteger();
        AppUpdater updater = new AppUpdater(context, new UpdateClient(url -> {
            if ("api.github.com".equals(url.getHost())) { checks.incrementAndGet(); return new Response(url, 200, json); }
            downloads.incrementAndGet(); return new Response(url, 200, apk);
        }));
        Field singleton = AppUpdater.class.getDeclaredField("instance"); singleton.setAccessible(true); singleton.set(null, updater);
        runOnMainSync(() -> { updater.setEnabled(false); updater.setOnStartup(true); AppUpdater.initialize((android.app.Application) context); });
        android.app.Activity activity = startupActivity();
        SystemClock.sleep(1800); waitForIdleSync();
        require(checks.get() == 0, "disabled updater makes no startup request");
        runOnMainSync(activity::finish); waitForIdleSync();
        runOnMainSync(() -> { updater.setEnabled(true); updater.setOnStartup(false); });
        activity = startupActivity(); SystemClock.sleep(1800); waitForIdleSync();
        require(checks.get() == 0, "startup option off makes no request");
        runOnMainSync(activity::finish); waitForIdleSync();
        runOnMainSync(() -> updater.setOnStartup(true));
        activity = startupActivity();
        awaitPhase(updater, AppUpdater.Phase.AVAILABLE);
        require(checks.get() == 1 && downloads.get() == 0, "one startup check and no download before approval");
        runOnMainSync(activity::finish); waitForIdleSync();
        activity = startupActivity(); SystemClock.sleep(1800); waitForIdleSync();
        require(checks.get() == 1, "activity resume does not repeat startup requests");
        runOnMainSync(activity::finish); waitForIdleSync();
        runOnMainSync(updater::download);
        awaitPhase(updater, AppUpdater.Phase.READY);
        require(downloads.get() == 1 && AppUpdater.readyFile(context).length() == apk.length, "approved download is verified and staged while app is backgrounded");
        require(AppUpdater.verifyApk(context, AppUpdater.readyFile(context)) != null, "staged update retains valid package signature");
        AppUpdater.readyFile(context).delete();
        runOnMainSync(() -> { updater.phase = AppUpdater.Phase.IDLE; updater.release = null; });
    }

    private android.app.Activity startupActivity() {
        return startActivitySync(new Intent(getTargetContext(), ai.nreal.nebula.activity.setting.SettingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private void awaitPhase(AppUpdater updater, AppUpdater.Phase expected) {
        long end = SystemClock.uptimeMillis() + 12000;
        do {
            waitForIdleSync();
            if (updater.phase == expected) return;
            if (updater.phase == AppUpdater.Phase.ERROR) throw new AssertionError(updater.message);
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Expected updater phase " + expected + ", got " + updater.phase + ": " + updater.message);
    }

    private void fails(Check action, String text) throws Exception {
        try { action.run(); throw new AssertionError("Expected failure: " + text); }
        catch (IOException error) { require(error.getMessage().contains(text), "failure reason: " + text + " (" + error + ")"); }
    }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }

    static final class Response extends HttpURLConnection {
        byte[] body; long length; String location;
        Response(URL url, int code, String body) { this(url, code, body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        Response(URL url, int code, byte[] body) { super(url); responseCode = code; this.body = body; length = body.length; }
        @Override public int getResponseCode() { return responseCode; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
        @Override public long getContentLengthLong() { return length; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public void connect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() {}
    }
}
