package dev.beampro.portprobe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

/** Local Binder rendezvous: shell may publish; only our signing certificate may consume. */
public final class PortBridgeProvider extends ContentProvider {
    private static IBinder bridge;
    private static final Object LOCK = new Object();

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int caller = Binder.getCallingUid();
        if ("publish".equals(method)) {
            if (caller != 2000) throw new SecurityException("Only the user-started ADB shell may publish");
            if (extras == null || extras.getBinder("binder") == null) throw new IllegalArgumentException("Missing binder");
            IBinder published = extras.getBinder("binder");
            synchronized (LOCK) {
                try {
                    published.linkToDeath(() -> {
                        synchronized (LOCK) { if (bridge == published) bridge = null; }
                    }, 0);
                } catch (android.os.RemoteException error) { throw new IllegalStateException("Helper already stopped", error); }
                bridge = published;
            }
            Bundle result = new Bundle();
            result.putBoolean("published", true);
            return result;
        }
        int own = getContext().getApplicationInfo().uid;
        if (caller != own && getContext().getPackageManager().checkSignatures(caller, own) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Bridge consumer must share the local port signing certificate");
        }
        if ("flag-put".equals(method) || "flag-get".equals(method)) {
            if (arg == null || !java.util.Arrays.asList("xreal.navigation_enable", "xreal.navigation_focus",
                    "xreal.flag.not_focusable", "xreal.launcher_is_ready", "xreal_vids",
                    "xreal_glass_connected", "xreal_glass_connected_plugin").contains(arg)) {
                throw new IllegalArgumentException("Unknown Beam-only flag");
            }
            android.content.SharedPreferences state = getContext().getSharedPreferences("port-beam-flags", 0);
            Bundle result = new Bundle();
            if ("flag-put".equals(method)) {
                android.content.SharedPreferences.Editor editor = state.edit();
                String value = extras == null ? null : extras.getString("value");
                if (value == null) editor.remove(arg); else editor.putString(arg, value);
                result.putBoolean("saved", editor.commit());
            } else result.putString("value", state.getString(arg, null));
            return result;
        }
        if (!"get".equals(method)) throw new IllegalArgumentException("Unknown bridge operation");
        Bundle result = new Bundle();
        synchronized (LOCK) {
            if (bridge != null && bridge.isBinderAlive()) result.putBinder("binder", bridge);
        }
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
