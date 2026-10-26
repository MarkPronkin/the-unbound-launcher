package dev.beampro.settings.preview;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import dev.beampro.settings.AppMenuStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/** Real AtomicFile migration in isolated app storage, including an existing native layout. */
public final class AppMenuMigrationChecks extends Instrumentation {
    int passed;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        File root = new File(getTargetContext().getExternalFilesDir(null), "migration-check-" + System.nanoTime());
        Context isolated = new ContextWrapper(getTargetContext()) { @Override public File getExternalFilesDir(String type) { return root; } };
        try {
            File nativeFile = new File(root, "AppDetails/EPHInfoConfig/LayoutConfig.json");
            File legacyFile = new File(root, "EPHInfoConfig/LayoutConfig.json");
            nativeFile.getParentFile().mkdirs(); legacyFile.getParentFile().mkdirs();
            String original = "{\"native.old\":0}", chosen = "{\"chosen\":0,\"hidden\":2147483647}";
            write(nativeFile, original); write(legacyFile, chosen);
            AppMenuStore migrated = new AppMenuStore(isolated);
            require(read(nativeFile).equals(chosen), "phone choices reach actual AppDetails consumer path");
            require(migrated.order(Arrays.asList("native.old", "chosen")).get(0).equals("chosen"), "recovered order is loaded");
            require(migrated.isHidden("hidden"), "hidden choices recovered");
            require(!legacyFile.exists(), "wrong path is no longer an active writer");
            require(read(new File(legacyFile + ".migrated")).equals(chosen), "legacy choices archived");
            require(read(new File(nativeFile + ".before-phone-migration")).equals(original), "previous native layout backed up");
            String changedInGlasses = "{\"chosen\":5,\"native.old\":0}";
            write(nativeFile, changedInGlasses);
            AppMenuStore reopened = new AppMenuStore(isolated);
            require(reopened.order(Arrays.asList("chosen", "native.old")).get(0).equals("native.old"), "later AR edits remain authoritative");
            require(read(nativeFile).equals(changedInGlasses), "migration is not reapplied on next open");
            reopened.save(Arrays.asList("chosen", "native.old"), Collections.emptyList());
            require(read(nativeFile).contains("\"chosen\":0"), "future saves target native path");
            require(!legacyFile.exists(), "future saves never recreate wrong path");
            write(legacyFile, "invalid json"); String working = read(nativeFile); boolean rejected = false;
            try { new AppMenuStore(isolated); } catch (java.io.IOException expected) { rejected = true; }
            require(rejected, "malformed legacy choices are reported");
            require(read(nativeFile).equals(working), "malformed legacy file never replaces native data");
            require(read(legacyFile).equals("invalid json"), "malformed legacy data is retained");
            legacyFile.delete(); nativeFile.delete();
            AppMenuStore fresh = new AppMenuStore(isolated); fresh.save(Arrays.asList("first", "second"), Collections.emptyList());
            require(nativeFile.exists(), "fresh installs create the actual native path");
            require(!legacyFile.exists(), "fresh installs do not create the old path");
            result.putString("stream", "App menu migration runtime checks passed: " + passed + "\n"); finish(-1, result);
        } catch (Throwable error) { result.putString("stream", "Migration check failed after " + passed + ": " + error + "\n"); finish(0, result); }
        finally { remove(root); }
    }
    static void write(File file, String value) throws Exception { Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8)); }
    static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    static void remove(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); file.delete(); }
    void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
}
