package dev.beampro.bridge;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Looper;

/** Run under app_process to exercise real Android Intent and PackageManager behavior. */
public final class SpatialLaunchIntentTest {
    private static int checks;

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        checks++;
    }

    private static void unchanged(Intent intent, PackageManager packages, String description) {
        String before = intent.toUri(Intent.URI_INTENT_SCHEME);
        Intent result = SpatialLaunchIntent.prepare(intent, packages);
        check(result != intent && before.equals(result.toUri(Intent.URI_INTENT_SCHEME)), description);
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("systemMain").invoke(null);
        Context context = (Context) type.getMethod("getSystemContext").invoke(thread);
        PackageManager packages = context.getPackageManager();
        Intent launcher = packages.getLaunchIntentForPackage("com.android.chrome");
        if (launcher == null) throw new AssertionError("Install Chrome for this regression check");
        Intent icon = new Intent(Intent.ACTION_VIEW).setComponent(launcher.getComponent())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        String original = icon.toUri(Intent.URI_INTENT_SCHEME);
        Intent result = SpatialLaunchIntent.prepare(icon, packages);
        check(Intent.ACTION_MAIN.equals(result.getAction()), "App icon uses MAIN");
        check(result.hasCategory(Intent.CATEGORY_LAUNCHER), "App icon uses LAUNCHER category");
        check(result.getComponent().equals(icon.getComponent()), "Launcher alias preserved");
        check(result.getFlags() == icon.getFlags(), "Spatial task flags preserved");
        check(original.equals(icon.toUri(Intent.URI_INTENT_SCHEME)), "Original Intent untouched");
        unchanged(new Intent(icon).setData(Uri.parse("https://example.com/")), packages, "URL preserved");
        unchanged(new Intent(icon).setType("text/plain"), packages, "MIME type preserved");
        unchanged(new Intent(icon).putExtra("payload", "value"), packages, "Extras preserved");
        unchanged(new Intent(icon).addCategory(Intent.CATEGORY_BROWSABLE), packages, "Explicit categories preserved");
        unchanged(new Intent(icon).setAction(android.provider.Settings.ACTION_WIFI_SETTINGS), packages, "Settings action preserved");
        unchanged(new Intent(icon).setComponent(null), packages, "Implicit Intent preserved");
        unchanged(new Intent(icon).setComponent(new ComponentName("com.android.chrome", "other.Activity")), packages, "Other component preserved");
        unchanged(new Intent(icon).setComponent(new ComponentName("dev.beampro.missing", "missing.Activity")), packages, "Unavailable app preserved");
        Intent clip = new Intent(icon);
        clip.setClipData(ClipData.newPlainText("payload", "value"));
        Intent clippedResult = SpatialLaunchIntent.prepare(clip, packages);
        check(clippedResult.getClipData() != null
                && "value".contentEquals(clippedResult.getClipData().getItemAt(0).getText())
                && Intent.ACTION_VIEW.equals(clippedResult.getAction()), "ClipData preserved");
        Intent selector = new Intent(icon);
        selector.setSelector(new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")));
        unchanged(selector, packages, "Selector preserved");
        unchanged(launcher, packages, "Existing launcher Intent preserved");
        System.out.println("PASS: " + checks + " real Android launch-Intent checks");
        System.exit(0);
    }
}
