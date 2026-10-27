package dev.beampro.settings;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Worker-only optional dumps. A dead helper or stalled pipe cannot block ZIP export. */
final class DebugDisplay {
    private static final AtomicReference<ReadPipe> active = new AtomicReference<>();

    static String[] capture(Context context) { return capture(context, 15_000); }

    static String[] capture(Context context, long timeoutMillis) {
        return new String[]{captureOne(context, DisplayDiagnostics.DISPLAY, timeoutMillis),
                captureOne(context, DisplayDiagnostics.SURFACE_FLINGER, timeoutMillis)};
    }

    private static String captureOne(Context context, int kind, long timeoutMillis) {
        ReadPipe pipe = new ReadPipe();
        FutureTask<String> request = null;
        try {
            if (!active.compareAndSet(null, pipe)) throw new RejectedExecutionException("A display diagnostic is still finishing");
            request = new FutureTask<>(() -> {
                try { return helper(context, kind, pipe); }
                finally { active.compareAndSet(pipe, null); }
            });
            final FutureTask<String> task = request;
            Thread worker = new Thread(() -> {
                try { task.run(); }
                finally { active.compareAndSet(pipe, null); }
            }, "unbound-display-helper");
            worker.setDaemon(true);
            try { worker.start(); }
            catch (RuntimeException failure) { active.compareAndSet(pipe, null); throw failure; }
            return request.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return "Captured: " + new Date() + "\nRequested: dumpsys " + DisplayDiagnostics.service(kind)
                    + "\nHelper display diagnostics unavailable: " + failure
                    + "\nStart or restart the updated helper under Settings > On-device ADB, then create a new debug file.\n";
        } finally {
            pipe.close();
            if (request != null) request.cancel(true);
        }
    }

    private static String helper(Context context, int kind, ReadPipe pipe) throws Exception {
        IBinder binder = null;
        for (String authority : new String[]{context.getPackageName() + ".unbound.bridge", "dev.beampro.portprobe.bridge"}) {
            try {
                Bundle bundle = context.getContentResolver().call(Uri.parse("content://" + authority), "get", null, null);
                binder = bundle == null ? null : bundle.getBinder("binder");
                if (binder != null && binder.isBinderAlive()) break;
            } catch (Exception unavailable) { binder = null; }
        }
        if (binder == null || !binder.isBinderAlive()) throw new IOException("On-device ADB helper is not running");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DisplayDiagnostics.TOKEN); data.writeInt(kind);
            if (!binder.transact(DisplayDiagnostics.TRANSACTION, data, reply, 0))
                throw new IOException("The running helper does not support display diagnostics");
            reply.readException();
            pipe.attach(ParcelFileDescriptor.CREATOR.createFromParcel(reply));
        } finally { data.recycle(); reply.recycle(); }
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe.descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                int keep = Math.min(count, DisplayDiagnostics.MAX_BYTES - output.size());
                output.write(buffer, 0, keep);
                if (keep < count) return output.toString("UTF-8") + "\n[helper output exceeded its byte limit]\n";
            }
            if (output.size() == 0) throw new IOException("The helper returned an empty diagnostic pipe");
            return output.toString("UTF-8");
        }
    }

    private static final class ReadPipe {
        ParcelFileDescriptor descriptor;
        private boolean closed;
        synchronized void attach(ParcelFileDescriptor value) throws IOException {
            if (value == null) throw new IOException("The helper returned no diagnostic pipe");
            if (closed) { value.close(); throw new IOException("Display diagnostic was cancelled"); }
            descriptor = value;
        }
        synchronized void close() {
            closed = true;
            if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
        }
    }

    private DebugDisplay() { }
}
