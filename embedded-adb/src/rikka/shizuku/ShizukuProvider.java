package rikka.shizuku;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import dev.beampro.adb.ShizukuClient;
import moe.shizuku.api.BinderContainer;

/** Receives the Shizuku service Binder pushed by Shizuku Server (UID 2000 or UID 0). */
public class ShizukuProvider extends ContentProvider {
    public static final String METHOD_SEND_BINDER = "sendBinder";
    public static final String METHOD_GET_BINDER = "getBinder";
    public static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context != null) {
            ShizukuClient.tryInitSui(context);
        }
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null || method == null) return null;
        int caller = Binder.getCallingUid();
        int own = context.getApplicationInfo().uid;
        boolean sameApp = caller == own
                || context.getPackageManager().checkSignatures(caller, own) == PackageManager.SIGNATURE_MATCH;
        if (METHOD_SEND_BINDER.equals(method)) {
            if (caller != 2000 && caller != 0 && !sameApp) {
                throw new SecurityException("Only ADB shell, root, or this app may provide a Shizuku binder");
            }
            if (extras == null) return null;
            extras.setClassLoader(BinderContainer.class.getClassLoader());
            BinderContainer container = extras.getParcelable(EXTRA_BINDER);
            if (container != null && container.binder != null) {
                ShizukuClient.onBinderReceived(container.binder, context);
            }
            return new Bundle();
        }
        if (METHOD_GET_BINDER.equals(method)) {
            if (!sameApp) {
                throw new SecurityException("Only this app may query its Shizuku binder");
            }
            Bundle reply = new Bundle();
            IBinder binder = ShizukuClient.peekBinder();
            if (binder != null && binder.pingBinder()) {
                reply.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
            }
            return reply;
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
