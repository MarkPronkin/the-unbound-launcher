package dev.beampro.settings;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import java.io.IOException;
import java.util.concurrent.*;

/** Worker-only capture. An unavailable or older helper never prevents exporting app logs. */
final class DebugNetwork {
    private static final ThreadPoolExecutor remote = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<Runnable>(), task -> {
                Thread thread = new Thread(task, "unbound-network-helper"); thread.setDaemon(true); return thread;
            });

    static String capture(Context context) { return capture(context, 20_000); }

    static String capture(Context context, long timeoutMillis) {
        StringBuilder report = new StringBuilder();
        Future<String> request = null;
        try {
            request = remote.submit(() -> helper(context));
            report.append(NetworkDiagnostics.capture(false));
            report.append('\n').append(request.get(timeoutMillis, TimeUnit.MILLISECONDS));
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            if (request == null) report.append(NetworkDiagnostics.capture(false));
            report.append("\nHelper diagnostics unavailable: ").append(error)
                    .append("\nStart or restart the updated helper under Settings > AR connection, then create a new debug file.\n");
        } finally { if (request != null) request.cancel(true); }
        return report.toString();
    }

    private static String helper(Context context) throws Exception {
        IBinder binder = null;
        for (String authority : new String[]{context.getPackageName() + ".unbound.bridge", "dev.beampro.portprobe.bridge"}) {
            try {
                Bundle bundle = context.getContentResolver().call(Uri.parse("content://" + authority), "get", null, null);
                binder = bundle == null ? null : bundle.getBinder("binder");
                if (binder != null && binder.isBinderAlive()) break;
            } catch (Exception unavailable) { binder = null; }
        }
        if (binder == null || !binder.isBinderAlive()) throw new IOException("AR connection helper is not running");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(NetworkDiagnostics.TOKEN);
            if (!binder.transact(NetworkDiagnostics.TRANSACTION, data, reply, 0))
                throw new IOException("The running helper does not support network diagnostics");
            reply.readException();
            String result = reply.readString();
            if (result == null || result.length() > 48_000) throw new IOException("Invalid helper diagnostics response");
            return result;
        } finally { data.recycle(); reply.recycle(); }
    }

    private DebugNetwork() { }
}
