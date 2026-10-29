package dev.beampro.settings;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A bounded, on-disk ring. The final ZIP limit includes headers and compression overhead. */
final class DebugArchive {
    static final long MAX_EXPORT_BYTES = 15_000_000;
    static final int CHUNK_BYTES = 1_000_000, MAX_CHUNKS = 12;
    private final File logs, exports;
    private final StartupTrace startup;
    private File current;
    private long sequence;
    static final int MAX_NETWORK_BYTES = 64_000, MAX_NETWORK_SNAPSHOTS = 3;
    static final int MAX_LINK_SNAPSHOTS = 3;
    static final int MAX_STARTUP_SNAPSHOTS = 3, MAX_STARTUP_SNAPSHOT_BYTES = 32_000;
    // The first trace since collection began is kept beside the latest two, like the pinned first failure.
    static final int MAX_TRACE_SNAPSHOTS = 3;
    static final int STARTUP_LOG_RESERVE = 380_000;

    DebugArchive(File directory) throws IOException {
        logs = new File(directory, "logs"); exports = new File(directory, "exports");
        if ((!logs.isDirectory() && !logs.mkdirs()) || (!exports.isDirectory() && !exports.mkdirs()))
            throw new IOException("Could not create debug storage.");
        File[] incomplete = exports.listFiles((dir, name) -> name.endsWith(".zip.tmp"));
        if (incomplete != null) for (File file : incomplete)
            if (!file.delete()) throw new IOException("Could not remove an incomplete debug file.");
        File[] existing = chunks();
        if (existing.length > 0) sequence = Long.parseLong(existing[existing.length - 1].getName().substring(4, 16));
        trim();
        startup = new StartupTrace(logs);
    }

    private File[] chunks() {
        File[] files = logs.listFiles((dir, name) -> name.matches("log-[0-9]{12}\\.txt"));
        if (files == null) return new File[0];
        Arrays.sort(files); return files;
    }

    private void trim() throws IOException {
        File[] files = chunks();
        for (int i = 0; i < files.length - MAX_CHUNKS; i++)
            if (!files[i].delete()) throw new IOException("Could not rotate debug data.");
    }

    synchronized void append(byte[] bytes, int count) throws IOException {
        int at = 0;
        while (at < count) {
            if (current == null || current.length() >= CHUNK_BYTES) {
                current = new File(logs, String.format(Locale.US, "log-%012d.txt", ++sequence));
                if (!current.createNewFile()) throw new IOException("Could not create debug log.");
                trim();
            }
            int size = Math.min(count - at, CHUNK_BYTES - (int) current.length());
            try (OutputStream stream = new FileOutputStream(current, true)) { stream.write(bytes, at, size); }
            at += size;
        }
    }

    synchronized long bytes() {
        long size = 0; for (File file : chunks()) size += file.length(); return size;
    }

    private File[] networks() {
        return snapshots("network");
    }

    private File[] snapshots(String prefix) {
        File[] files = logs.listFiles((dir, name) -> name.matches(prefix + "-[0-9]{20}\\.txt"));
        if (files == null) return new File[0];
        Arrays.sort(files); return files;
    }

    synchronized void network(String report) throws IOException {
        byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
        snapshot("network", Arrays.copyOf(bytes, Math.min(bytes.length, MAX_NETWORK_BYTES)), MAX_NETWORK_SNAPSHOTS);
    }

    synchronized void link(String report) throws IOException {
        snapshot("display-link", DisplayDiagnostics.encode(DisplayDiagnostics.DISPLAY_LINK, report), MAX_LINK_SNAPSHOTS);
    }

    synchronized void startupBegin(boolean reset) throws IOException {
        startup.begin(reset);
        if (reset) for (String prefix : new String[]{"glasses-control", "glasses-control-trace"})
            for (File file : snapshots(prefix))
                if (!file.delete()) throw new IOException("Could not reset glasses control diagnostics.");
    }

    synchronized boolean startupLog(byte[] bytes, int count) throws IOException { return startup.log(bytes, count); }

    synchronized void startupEvent(String attempt, String event, String detail) throws IOException { startup.event(attempt, event, detail); }

    synchronized void startupSnapshot(String text) throws IOException {
        snapshot("glasses-control", StartupTrace.encode(text, MAX_STARTUP_SNAPSHOT_BYTES), MAX_STARTUP_SNAPSHOTS);
    }

    synchronized void startupTrace(String text) throws IOException {
        snapshot("glasses-control-trace", StartupTrace.encode(text, ControlTrace.MAX_BYTES), MAX_TRACE_SNAPSHOTS, true);
    }

    private void snapshot(String prefix, byte[] bytes, int retain) throws IOException { snapshot(prefix, bytes, retain, false); }

    private void snapshot(String prefix, byte[] bytes, int retain, boolean keepFirst) throws IOException {
        File[] previous = snapshots(prefix);
        int start = prefix.length() + 1;
        long sequence = previous.length == 0 ? 1
                : Long.parseLong(previous[previous.length - 1].getName().substring(start, start + 20)) + 1;
        File file = new File(logs, String.format(Locale.US, prefix + "-%020d.txt", sequence));
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        File[] files = snapshots(prefix);
        int first = keepFirst ? 1 : 0;
        for (int i = first; i < files.length - retain + first; i++)
            if (!files[i].delete()) throw new IOException("Could not rotate " + prefix + " diagnostics.");
    }

    synchronized File export(String report) throws IOException {
        return export(report, null, null);
    }

    synchronized File export(String report, String display, String surfaceFlinger) throws IOException {
        return export(report, display, surfaceFlinger, null);
    }

    synchronized File export(String report, String display, String surfaceFlinger, String renderer) throws IOException {
        return export(report, display, surfaceFlinger, renderer, null);
    }

    synchronized File export(String report, String display, String surfaceFlinger, String renderer, String windows) throws IOException {
        // Retain the latest three immutable exports so already granted URIs stay usable.
        File[] previous = exports.listFiles((dir, name) -> validName(name));
        if (previous != null) {
            Arrays.sort(previous);
            for (int i = 0; i < previous.length - 2; i++)
                if (!previous[i].delete()) throw new IOException("Could not remove an old debug file.");
        }
        SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        stamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        String name = "unbound-debug-" + stamp.format(new Date()) + "-" + UUID.randomUUID().toString().substring(0, 8) + ".zip";
        File file = new File(exports, name), temporary = new File(exports, name + ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                    new LimitedOutput(new FileOutputStream(temporary), MAX_EXPORT_BYTES)))) {
                byte[] metadata = report.getBytes(StandardCharsets.UTF_8);
                if (metadata.length > 64_000) throw new IOException("Debug report is too large.");
                zip.putNextEntry(new ZipEntry("device-and-session.txt")); zip.write(metadata); zip.closeEntry();
                zip.putNextEntry(new ZipEntry("app.log"));
                byte[] buffer = new byte[8192];
                boolean startupData = startup.hasData() || snapshots("glasses-control").length > 0
                        || snapshots("glasses-control-trace").length > 0;
                long skip = Math.max(0, bytes() - (long) CHUNK_BYTES * MAX_CHUNKS + (startupData ? STARTUP_LOG_RESERVE : 0)
                        + (renderer == null ? 0 : RendererTrace.MAX_BYTES + 1024) + (windows == null ? 0 : 49_024));
                for (File chunk : chunks()) try (FileInputStream input = new FileInputStream(chunk)) {
                    if (skip >= chunk.length()) { skip -= chunk.length(); continue; }
                    while (skip > 0) {
                        long skipped = input.skip(skip);
                        if (skipped == 0) { if (input.read() == -1) break; skipped = 1; }
                        skip -= skipped;
                    }
                    int count; while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
                }
                zip.closeEntry();
                if (renderer != null) {
                    zip.putNextEntry(new ZipEntry("renderer-geometry.txt"));
                    zip.write(StartupTrace.encode(renderer, RendererTrace.MAX_BYTES)); zip.closeEntry();
                }
                if (windows != null) {
                    zip.putNextEntry(new ZipEntry("immersive-windows.txt"));
                    zip.write(StartupTrace.encode(windows, 48_000)); zip.closeEntry();
                }
                if (startup.hasData()) {
                    zip.putNextEntry(new ZipEntry("glasses-startup.txt")); zip.write(startup.report()); zip.closeEntry();
                }
                writeSnapshots(zip, "glasses-control.txt", snapshots("glasses-control"), MAX_STARTUP_SNAPSHOT_BYTES, "Glasses control snapshot");
                writeSnapshots(zip, "glasses-control-trace.txt", snapshots("glasses-control-trace"), ControlTrace.MAX_BYTES, "Glasses control trace");
                writeSnapshots(zip, "network-diagnostics.txt", networks(), MAX_NETWORK_BYTES, "Network snapshot");
                writeSnapshots(zip, "display-link.txt", snapshots("display-link"), DisplayDiagnostics.MAX_LINK_BYTES, "Display/link snapshot");
                if (display != null) {
                    zip.putNextEntry(new ZipEntry("display.txt"));
                    zip.write(DisplayDiagnostics.encode(display)); zip.closeEntry();
                }
                if (surfaceFlinger != null) {
                    zip.putNextEntry(new ZipEntry("surfaceflinger.txt"));
                    zip.write(DisplayDiagnostics.encode(surfaceFlinger)); zip.closeEntry();
                }
            }
            if (temporary.length() > MAX_EXPORT_BYTES || !temporary.renameTo(file))
                throw new IOException("Could not finish the debug file within 15 MB.");
            return file;
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static void writeSnapshots(ZipOutputStream zip, String name, File[] snapshots, int limit, String label) throws IOException {
        if (snapshots.length == 0) return;
        zip.putNextEntry(new ZipEntry(name));
        byte[] buffer = new byte[8192];
        for (File snapshot : snapshots) {
            zip.write(("\n--- " + label + " ---\n").getBytes(StandardCharsets.UTF_8));
            try (InputStream input = new FileInputStream(snapshot)) {
                int remaining = limit, count;
                while (remaining > 0 && (count = input.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                    zip.write(buffer, 0, count); remaining -= count;
                }
            }
        }
        zip.closeEntry();
    }

    static boolean validName(String name) { return name.matches("unbound-debug-[0-9]{8}-[0-9]{6}-[a-f0-9]{8}\\.zip"); }

    static final class LimitedOutput extends FilterOutputStream {
        private final long limit;
        private long written;
        LimitedOutput(OutputStream stream, long limit) { super(stream); this.limit = limit; }
        @Override public void write(int value) throws IOException {
            check(1); out.write(value); written++;
        }
        @Override public void write(byte[] bytes, int at, int count) throws IOException {
            check(count); out.write(bytes, at, count); written += count;
        }
        private void check(int count) throws IOException {
            if (count > limit - written) throw new IOException("Debug file exceeds the 15 MB limit.");
        }
    }
}
