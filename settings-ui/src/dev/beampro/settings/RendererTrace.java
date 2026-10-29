package dev.beampro.settings;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Cross-process, opt-in renderer evidence. File locks also serialize collection resets and export. */
public final class RendererTrace {
    public static final int SAMPLE_BYTES = 16_000, RECENT_BYTES = 64_000, SESSIONS = 4;
    public static final int MAX_BYTES = 330_000;
    private static final Object FILES_LOCK = new Object();
    private final File directory, gate, lock;
    public RendererTrace(File debug) {
        directory = new File(debug, "renderer"); gate = new File(directory, "collecting"); lock = new File(directory, "lock");
    }
    private interface Work<T> { T run() throws IOException; }
    private <T> T locked(Work<T> work) throws IOException { synchronized (FILES_LOCK) {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Renderer storage unavailable");
        try (RandomAccessFile file = new RandomAccessFile(lock, "rw"); FileLock ignored = file.getChannel().lock()) { return work.run(); }
    } }
    public void reset() throws IOException {
        locked(() -> { remove(gate); clear(); return null; });
    }
    public void start(boolean reset) throws IOException {
        locked(() -> {
            if (reset) clear();
            else {
                File[] leftovers = directory.listFiles((dir, name) -> name.matches("session-[0-9]{20}-[0-9]+\\.txt(?:\\.first)?(?:\\.tmp)?"));
                if (leftovers != null) for (File file : leftovers)
                    if (file.getName().endsWith(".tmp") || (file.getName().endsWith(".first")
                            && !new File(file.getPath().substring(0, file.getPath().length() - 6)).isFile())) remove(file);
            }
            if (reset || !gate.isFile()) write(gate, UUID.randomUUID().toString());
            return null;
        });
    }
    public void stop() throws IOException { locked(() -> { remove(gate); return null; }); }
    private void clear() throws IOException {
        File[] files = directory.listFiles((dir, name) -> name.matches("session-[0-9]{20}-[0-9]+\\.txt(?:\\.first)?(?:\\.tmp)?"));
        if (files != null) for (File file : files) remove(file);
    }
    public String token() throws IOException { return read(gate, 64).trim(); }
    private File[] sessions() {
        File[] files = directory.listFiles((dir, name) -> name.matches("session-[0-9]{20}-[0-9]+\\.txt"));
        if (files == null) return new File[0]; Arrays.sort(files); return files;
    }
    public void append(String token, String session, String sample) throws IOException {
        if (!session.matches("[0-9]{20}-[0-9]+")) throw new IOException("Invalid renderer session");
        locked(() -> {
            if (token.isEmpty() || !token.equals(token())) return null; // Reject late jobs after stop/reset.
            File file = new File(directory, "session-" + session + ".txt"), first = new File(file + ".first");
            String bounded = bound(sample, SAMPLE_BYTES);
            if (!first.isFile()) write(first, bounded);
            String recent = read(file, RECENT_BYTES) + bounded;
            byte[] bytes = recent.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > RECENT_BYTES) {
                int at = bytes.length - RECENT_BYTES;
                // Retain complete snapshots, rather than cutting a projection matrix in half.
                String tail = new String(bytes, at, bytes.length - at, StandardCharsets.UTF_8);
                int next = tail.indexOf("\n--- Renderer sample ");
                recent = next < 0 ? bounded : tail.substring(next + 1);
            }
            write(file, recent);
            File[] files = sessions();
            for (int i = 0; i < files.length - SESSIONS; i++) { remove(files[i]); remove(new File(files[i] + ".first")); }
            return null;
        });
    }
    public String report() throws IOException {
        return locked(() -> {
            StringBuilder out = new StringBuilder("Renderer geometry diagnostics\nDirect Unity-thread readback, independent of logcat.\n"
                    + "Snapshots describe sampled state, not optical correctness. No screenshots, app content or head poses.\n"
                    + "Native framebuffer enum is not exposed; render pass/parameter counts describe the actual Unity eye layout.\n");
            File[] files = sessions();
            if (files.length == 0) out.append("No renderer sample captured. Enable collection before entering AR Space; unavailable/stalled Unity is reported when observed.\n");
            for (File file : files) {
                out.append("\n=== ").append(file.getName()).append("; first sample ===\n").append(read(new File(file + ".first"), SAMPLE_BYTES));
                out.append("\n=== Recent samples ===\n").append(read(file, RECENT_BYTES));
            }
            return bound(out.toString(), MAX_BYTES);
        });
    }
    private static String bound(String text, int limit) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) return text;
        int end = limit - 32; while (end > 0 && (bytes[end] & 0xc0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8) + "\n[renderer data truncated]\n";
    }
    private static String read(File file, int limit) throws IOException {
        if (!file.isFile()) return "";
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int count, remaining = limit;
            while (remaining > 0 && (count = input.read(bytes, 0, Math.min(bytes.length, remaining))) > 0) { out.write(bytes, 0, count); remaining -= count; }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private static void write(File file, String value) throws IOException {
        File tmp = new File(file + ".tmp");
        try {
            try (OutputStream out = new FileOutputStream(tmp)) { out.write(value.getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(file)) throw new IOException("Cannot save renderer diagnostics");
        } finally { tmp.delete(); }
    }
    private static void remove(File file) throws IOException { if (file.exists() && !file.delete()) throw new IOException("Cannot reset renderer diagnostics"); }
}
