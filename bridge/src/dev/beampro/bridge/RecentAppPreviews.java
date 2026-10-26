package dev.beampro.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/** Captures thumbnails only from displays currently owned by our launch manager. */
final class RecentAppPreviews {
    private final Context context;
    private final Object manager;
    private final RecentAppsStore history;
    private final File directory;
    private HeadlessScreenCapture capture;

    RecentAppPreviews(Context context, Object manager, RecentAppsStore history, File directory) {
        this.context = context;
        this.manager = manager;
        this.history = history;
        this.directory = directory;
    }

    private File file(int id) { return new File(directory, "beampro-recent-preview-" + id + ".jpg"); }

    synchronized byte[] get(int id) {
        RecentAppsStore.Entry target = null;
        for (RecentAppsStore.Entry entry : history.list()) if (entry.id == id) target = entry;
        if (target == null) return null;
        capture(target);
        try {
            File cached = file(id);
            return cached.isFile() && cached.length() <= 262144 ? Files.readAllBytes(cached.toPath()) : null;
        } catch (Exception error) { return null; }
    }

    synchronized void capturePackage(String name) {
        for (RecentAppsStore.Entry entry : history.list()) {
            if (entry.packageName.equals(name)) { capture(entry); return; }
        }
    }

    synchronized void captureAll() {
        for (RecentAppsStore.Entry entry : history.list()) capture(entry);
    }

    synchronized void prune() {
        Set<String> retained = new HashSet<>();
        for (RecentAppsStore.Entry entry : history.list()) retained.add(file(entry.id).getName());
        File[] files = directory.listFiles((dir, name) -> name.matches("beampro-recent-preview--[0-9]+\\.jpg"));
        if (files == null) return;
        for (File cached : files) if (!retained.contains(cached.getName())) cached.delete();
    }

    private void capture(RecentAppsStore.Entry entry) {
        Bitmap bitmap = null;
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(entry.packageName);
            if (launch == null || launch.getComponent() == null) return;
            Method find = manager.getClass().getDeclaredMethod("findActivityContent", ComponentName.class);
            find.setAccessible(true);
            Object content = find.invoke(manager, launch.getComponent());
            if (content == null) return; // Closed windows retain their last saved preview.
            Method getDisplay = content.getClass().getDeclaredMethod("getDisplayId");
            getDisplay.setAccessible(true);
            int id = (Integer) getDisplay.invoke(content);
            if (id <= 0) return;
            if(capture==null)capture=new HeadlessScreenCapture();
            HeadlessScreenCapture.Screen screen=capture.readVirtualDisplay(id);
            if(screen==null)return;
            bitmap=capture.capture(screen);
            if (bitmap == null) {
                System.err.println("BEAM_RECENT_PREVIEW_EMPTY "+entry.packageName+" display="+id);
                return;
            }
            // A window can close while capture is in flight. Never replace an entry
            // that was dismissed or record a different display after that happens.
            if (!history.list().stream().anyMatch(current -> current.id == entry.id)) return;
            if(find.invoke(manager,launch.getComponent())!=content
                    ||(Integer)getDisplay.invoke(content)!=id
                    ||!screen.same(capture.readVirtualDisplay(id)))return;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bytes) || bytes.size() > 262144) return;
            File cached = file(entry.id), pending = new File(cached.getPath() + ".new");
            Files.write(pending.toPath(), bytes.toByteArray());
            Files.move(pending.toPath(), cached.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("BEAM_RECENT_PREVIEW_SAVED "+entry.packageName+" "+bitmap.getWidth()+"x"+bitmap.getHeight()+" bytes="+bytes.size());
        } catch (Exception error) {
            Throwable cause=error;
            while(cause instanceof java.lang.reflect.InvocationTargetException&&cause.getCause()!=null)cause=cause.getCause();
            System.err.println("BEAM_RECENT_PREVIEW_FAILED " + entry.packageName + " " + cause);
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }
}
