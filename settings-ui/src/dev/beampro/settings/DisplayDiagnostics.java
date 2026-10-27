package dev.beampro.settings;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;

/** Fixed, read-only display dumps. Large results travel over a pipe, never a Binder string. */
public final class DisplayDiagnostics {
    public static final int TRANSACTION = 0x00FFFFF3;
    public static final String TOKEN = "dev.beampro.bridge.DISPLAY_DIAGNOSTICS";
    public static final int DISPLAY = 0, SURFACE_FLINGER = 1;
    public static final int MAX_BYTES = 1_010_000;
    private static final int OUTPUT_BYTES = 1_000_000;
    interface Runner { String run(long timeout, String... command) throws Exception; }

    public static String service(int kind) {
        switch (kind) {
            case DISPLAY: return "display";
            case SURFACE_FLINGER: return "SurfaceFlinger";
            default: throw new IllegalArgumentException("Unknown display diagnostic");
        }
    }

    public static String capture(int kind) {
        return capture(kind, (timeout, command) -> NetworkDiagnostics.runLimited(OUTPUT_BYTES, timeout, command));
    }

    static String capture(int kind, Runner runner) {
        String service = service(kind);
        StringBuilder out = new StringBuilder("Captured: " + new Date() + "\nIdentity: ADB shell helper\n");
        out.append("/system/bin/dumpsys -t 8 ").append(service).append('\n');
        try { out.append(runner.run(10_000, "/system/bin/dumpsys", "-t", "8", service)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); out.append("Interrupted\n"); }
        catch (Exception failure) { out.append("Unavailable: ").append(failure).append('\n'); }
        return out.toString();
    }

    /** Enforce a byte budget even for malformed UTF-8 and diagnostic error messages. */
    public static byte[] encode(String report) {
        byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) return bytes;
        byte[] marker = "\n[diagnostic file truncated at its byte limit]\n".getBytes(StandardCharsets.UTF_8);
        int keep = MAX_BYTES - marker.length;
        while (keep > 0 && (bytes[keep] & 0xC0) == 0x80) keep--;
        byte[] result = Arrays.copyOf(bytes, keep + marker.length);
        System.arraycopy(marker, 0, result, keep, marker.length);
        return result;
    }

    private DisplayDiagnostics() { }
}
