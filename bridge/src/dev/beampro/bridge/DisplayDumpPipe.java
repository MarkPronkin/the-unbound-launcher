package dev.beampro.bridge;

import android.os.ParcelFileDescriptor;
import dev.beampro.settings.DisplayDiagnostics;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded dump at a time, accessible only to the installed launcher UID. */
public final class DisplayDumpPipe {
    public interface Capture { String run(int kind); }
    private final int launcherUid;
    private final Capture capture;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean controlBusy = new AtomicBoolean();

    public DisplayDumpPipe(int uid) { this(uid, DisplayDiagnostics::capture); }
    public DisplayDumpPipe(int uid, Capture capture) { launcherUid = uid; this.capture = capture; }

    public ParcelFileDescriptor open(int caller, int kind) throws IOException {
        if (caller != launcherUid) throw new SecurityException("Display diagnostics are restricted to the installed launcher");
        DisplayDiagnostics.service(kind); // Validate before allocating a pipe or starting a worker.
        AtomicBoolean slot=kind==DisplayDiagnostics.GLASSES_CONTROL ? controlBusy : busy;
        if (!slot.compareAndSet(false, true)) throw new IOException("A "+DisplayDiagnostics.service(kind)+" diagnostic is still finishing; create a new debug file shortly");
        ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch (IOException | RuntimeException failure) { slot.set(false); throw failure; }
        Thread worker = new Thread(() -> {
            OutputStream output=null;
            try {
                output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                output.write(DisplayDiagnostics.encode(kind, capture.run(kind)));
            } catch (Exception failure) { System.err.println("BEAM_DISPLAY_DIAGNOSTICS " + failure); }
            finally {
                // Release once before EOF; a close error must not clear a newer producer's slot.
                slot.set(false);
                try { if(output!=null)output.close();else pipe[1].close(); }
                catch(IOException failure){System.err.println("BEAM_DISPLAY_DIAGNOSTICS close "+failure);}
            }
        }, "unbound-display-dump");
        worker.setDaemon(true);
        try { worker.start(); }
        catch (RuntimeException failure) {
            try { pipe[0].close(); } finally { pipe[1].close(); slot.set(false); }
            throw failure;
        }
        return pipe[0];
    }
}
