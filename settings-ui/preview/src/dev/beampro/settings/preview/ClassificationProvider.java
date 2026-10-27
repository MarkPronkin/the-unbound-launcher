package dev.beampro.settings.preview;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

/** Emulator fixture: publish the real classification Binder under shell identity. */
public final class ClassificationProvider extends ContentProvider {
    private IBinder bridge;
    public boolean onCreate() { return true; }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        int caller = Binder.getCallingUid();
        if ("publish".equals(method)) {
            if (caller != 2000) throw new SecurityException("Shell fixture only");
            bridge = extras.getBinder("binder");
        } else if (!"get".equals(method) || caller != getContext().getApplicationInfo().uid) {
            throw new SecurityException("Preview consumer only");
        }
        Bundle result = new Bundle(); result.putBinder("binder", bridge); return result;
    }
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    public String getType(Uri u) { return null; }
    public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
