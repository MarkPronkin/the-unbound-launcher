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
    private static final AtomicReference<ReadPipe> activeControl = new AtomicReference<>();
    private static final AtomicReference<ReadPipe> activeTrace = new AtomicReference<>();
    private static final ThreadPoolExecutor controlWorker = worker("unbound-control-helper");
    private static final ThreadPoolExecutor traceWorker = worker("unbound-trace-helper");

    private static ThreadPoolExecutor worker(String name) {
        return new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
                new SynchronousQueue<Runnable>(),job->{Thread thread=new Thread(job,name);thread.setDaemon(true);return thread;});
    }

    static String[] capture(Context context) { return capture(context, 15_000); }

    static String[] capture(Context context, long timeoutMillis) {
        return new String[]{captureOne(context, DisplayDiagnostics.DISPLAY, timeoutMillis),
                captureOne(context, DisplayDiagnostics.SURFACE_FLINGER, timeoutMillis)};
    }

    static String captureLink(Context context) { return captureOne(context, DisplayDiagnostics.DISPLAY_LINK, 30_000); }
    static String captureControl(Context context) { return captureControl(context,6000); }
    static String captureControl(Context context, long timeoutMillis) { return captureOne(context,DisplayDiagnostics.GLASSES_CONTROL,timeoutMillis); }
    static String captureTrace(Context context) { return captureTrace(context,ControlTrace.DURATION_MS+8000); }
    static String captureTrace(Context context, long timeoutMillis) { return captureOne(context,DisplayDiagnostics.GLASSES_CONTROL_TRACE,timeoutMillis); }

    private static String captureOne(Context context, int kind, long timeoutMillis) {
        ReadPipe pipe = new ReadPipe();
        AtomicReference<ReadPipe> slot = kind==DisplayDiagnostics.GLASSES_CONTROL ? activeControl
                : kind==DisplayDiagnostics.GLASSES_CONTROL_TRACE ? activeTrace : active;
        FutureTask<String> request = null;
        try {
            if (!slot.compareAndSet(null, pipe)) throw new RejectedExecutionException("A "+DisplayDiagnostics.service(kind)+" diagnostic is still finishing");
            request = new FutureTask<>(() -> {
                try { return helper(context, kind, pipe); }
                finally { slot.compareAndSet(pipe, null); }
            });
            final FutureTask<String> task = request;
            Runnable worker = () -> {
                try { task.run(); }
                finally { slot.compareAndSet(pipe, null); }
            };
            try {
                if(kind==DisplayDiagnostics.GLASSES_CONTROL)controlWorker.execute(worker);
                else if(kind==DisplayDiagnostics.GLASSES_CONTROL_TRACE)traceWorker.execute(worker);
                else {Thread thread=new Thread(worker,"unbound-display-helper");thread.setDaemon(true);thread.start();}
            }
            catch (RuntimeException failure) { slot.compareAndSet(pipe, null); throw failure; }
            return request.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return "Captured: " + new Date() + "\nRequested: " + DisplayDiagnostics.service(kind)
                    + "\nHelper display diagnostics unavailable: " + failure
                    + "\nStart or restart the updated helper under Settings > AR connection, then create a new debug file.\n";
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
        if (binder == null || !binder.isBinderAlive()) throw new IOException("AR connection helper is not running");
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
                int limit = kind == DisplayDiagnostics.GLASSES_CONTROL ? DisplayDiagnostics.MAX_CONTROL_BYTES
                        : kind == DisplayDiagnostics.GLASSES_CONTROL_TRACE ? ControlTrace.MAX_BYTES
                        : kind == DisplayDiagnostics.DISPLAY_LINK ? DisplayDiagnostics.MAX_LINK_BYTES : DisplayDiagnostics.MAX_BYTES;
                int keep = Math.min(count, limit - output.size());
                output.write(buffer, 0, keep);
                if (keep < count) return new String(DisplayDiagnostics.encode(kind,output.toString("UTF-8")
                        + "\n[helper output exceeded its byte limit]\n"),java.nio.charset.StandardCharsets.UTF_8);
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
