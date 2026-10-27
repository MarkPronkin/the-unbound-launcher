package dev.beampro.settings;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
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
    private File current;
    private long sequence;

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

    synchronized File export(String report) throws IOException {
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
                for (File chunk : chunks()) try (FileInputStream input = new FileInputStream(chunk)) {
                    int count; while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
                }
                zip.closeEntry();
            }
            if (temporary.length() > MAX_EXPORT_BYTES || !temporary.renameTo(file))
                throw new IOException("Could not finish the debug file within 15 MB.");
            return file;
        } finally {
            if (temporary.exists()) temporary.delete();
        }
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
