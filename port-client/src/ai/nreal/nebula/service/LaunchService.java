package ai.nreal.nebula.service;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

/** Replacement for the platform-UID service: preserves the original Binder interface. */
public final class LaunchService extends Service {
    private IBinder remote;

    @Override public IBinder onBind(Intent intent) {
        try {
            Bundle result = getContentResolver().call(Uri.parse("content://dev.beampro.portprobe.bridge"), "get", null, null);
            remote = result == null ? null : result.getBinder("binder");
            if (remote == null || !remote.isBinderAlive()) {
                Log.e("PortLaunchService", "ADB launch helper is not running");
                return null;
            }
            Log.i("PortLaunchService", "Using ADB helper: " + remote.getInterfaceDescriptor());
            return remote;
        } catch (Exception error) {
            Log.e("PortLaunchService", "Cannot acquire launch helper", error);
            return null;
        }
    }

    @Override public boolean onUnbind(Intent intent) {
        if (intent != null && "dev.beampro.port.PROBE_BIND".equals(intent.getAction())) {
            remote = null;
            return super.onUnbind(intent);
        }
        if (remote != null && remote.isBinderAlive()) {
            android.os.Parcel data = android.os.Parcel.obtain(), reply = android.os.Parcel.obtain();
            try {
                data.writeInterfaceToken("dev.beampro.bridge.CONTROL");
                if (!remote.transact(0x00FFFFFE, data, reply, 0)) throw new IllegalStateException("Helper cleanup unsupported");
                reply.readException();
            } catch (Exception error) { Log.w("PortLaunchService", "Helper cleanup failed", error); }
            finally { data.recycle(); reply.recycle(); }
        }
        remote = null;
        return super.onUnbind(intent);
    }
}
