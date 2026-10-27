package dev.beampro.settings;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The system category is the source of truth; no preference can claim an unapplied change. */
final class AppDetection {
    private static final int TRANSACTION = 0x00FFFFF6;
    private static final String TOKEN = "dev.beampro.bridge.APP_CLASSIFICATION";
    private static AppDetection instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final List<Runnable> observers = new ArrayList<>();
    boolean busy, known, game;
    String error;

    static AppDetection get(Context context) {
        if (instance == null) instance = new AppDetection(context.getApplicationContext());
        return instance;
    }
    private AppDetection(Context context) { this.context = context; }
    void observe(Runnable observer) { if (!observers.contains(observer)) observers.add(observer); refresh(); }
    void remove(Runnable observer) { observers.remove(observer); }
    private void changed() { for (Runnable observer : new ArrayList<>(observers)) observer.run(); }
    void refresh() { request(-1); }
    void setGame(boolean enabled) { request(enabled ? 1 : 0); }

    private void request(int operation) {
        if (busy) return;
        busy = true; changed();
        work.execute(() -> {
            Integer category = null;
            String failure = null;
            try {
                PackageManager packages = context.getPackageManager();
                String own = context.getPackageName();
                // Updates installed by Unbound make it its own recorded installer.
                if (operation != -1 && own.equals(packages.getInstallerPackageName(own))) {
                    int requested = operation == 1 ? ApplicationInfo.CATEGORY_GAME : ApplicationInfo.CATEGORY_UNDEFINED;
                    packages.setApplicationCategoryHint(own, requested);
                    category = packages.getApplicationInfo(own, 0).category;
                    if (category != requested) throw new IOException("Android did not apply the requested app detection.");
                } else {
                    IBinder bridge = bridge();
                    if (bridge != null) category = transact(bridge, operation);
                    else if (operation != -1) throw new IOException("Start the helper in On-device ADB, then try again.");
                    else category = packages.getApplicationInfo(own, 0).category;
                }
            } catch (Exception problem) {
                android.util.Log.w("UnboundDetection", "App category request failed", problem);
                if (operation != -1) failure = problem instanceof IOException ? problem.getMessage()
                        : "This phone does not allow changing app detection for this installation.";
                // Always re-read after failure, including a partially applied request.
                try { category = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0).category; }
                catch (Exception ignored) { }
            }
            final Integer actual = category;
            final String message = failure;
            main.post(() -> {
                busy = false;
                if (actual != null) { known = true; game = actual == ApplicationInfo.CATEGORY_GAME; }
                if (operation != -1) error = message;
                changed();
            });
        });
    }

    private IBinder bridge() {
        // Settings previews have no helper provider. Developer and single-APK builds do.
        for (String authority : new String[]{context.getPackageName() + ".unbound.bridge", "dev.beampro.portprobe.bridge"}) {
            try {
                Bundle result = context.getContentResolver().call(Uri.parse("content://" + authority), "get", null, null);
                IBinder binder = result == null ? null : result.getBinder("binder");
                if (binder != null && binder.isBinderAlive()) return binder;
            } catch (Exception absent) { }
        }
        return null;
    }
    private int transact(IBinder bridge, int operation) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOKEN); data.writeInt(operation);
            if (!bridge.transact(TRANSACTION, data, reply, 0)) throw new IOException("Restart the helper in On-device ADB to use app detection.");
            reply.readException();
            String error = reply.readString();
            int category = reply.readInt();
            if (error != null) throw new IOException(error);
            return category;
        } finally { data.recycle(); reply.recycle(); }
    }
}
