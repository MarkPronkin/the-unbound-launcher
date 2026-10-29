package dev.beampro.settings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/** Fixed, read-only network probes shared by the app and its shell helper. */
public final class NetworkDiagnostics {
    public static final int TRANSACTION = 0x00FFFFF5;
    public static final String TOKEN = "dev.beampro.bridge.NETWORK_DIAGNOSTICS";
    private static final int OUTPUT_BYTES = 8192;
    interface Runner { String run(long timeout, String... command) throws Exception; }

    public static String capture(boolean shell) { return capture(shell, NetworkDiagnostics::run); }

    static String capture(boolean shell, Runner runner) {
        StringBuilder out = new StringBuilder("Captured: " + new Date() + "\nIdentity: " + (shell ? "ADB shell helper" : "app UID") + "\n");
        if (shell) {
            probe(out, runner, 2000, "/system/bin/ip", "-br", "address");
            probe(out, runner, 2000, "/system/bin/ip", "route", "show", "table", "all");
            probe(out, runner, 3000, "/system/bin/dumpsys", "-t", "2", "ethernet");
        }
        // Preserve the SDK's interface and destination, with a usable timeout and stderr.
        probe(out, runner, 4000, "/system/bin/ping", "-I", "eth0", "-c", "3", "-W", "1", "169.254.2.1");
        return out.toString();
    }

    private static void probe(StringBuilder out, Runner runner, long timeout, String... command) {
        out.append('\n').append(String.join(" ", command)).append('\n');
        try { out.append(runner.run(timeout, command)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); out.append("Interrupted\n"); }
        catch (Exception error) { out.append("Unavailable: ").append(error).append('\n'); }
    }

    static String run(long timeout, String... command) throws Exception {
        return runLimited(OUTPUT_BYTES, timeout, command);
    }

    static String runLimited(int outputBytes, long timeout, String... command) throws Exception {
        return runLimited(outputBytes, timeout, false, command);
    }

    static String runTailLimited(int outputBytes, long timeout, String... command) throws Exception {
        return runLimited(outputBytes, timeout, true, command);
    }

    private static String runLimited(int outputBytes, long timeout, boolean tail, String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        java.util.concurrent.atomic.AtomicBoolean truncated = new java.util.concurrent.atomic.AtomicBoolean();
        Thread drain = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) synchronized (bytes) {
                    if (tail) {
                        byte[] previous = bytes.toByteArray();
                        int oldKeep = Math.min(previous.length, Math.max(0, outputBytes - count));
                        int keep = Math.min(count, outputBytes);
                        bytes.reset(); bytes.write(previous, previous.length - oldKeep, oldKeep);
                        bytes.write(buffer, count - keep, keep);
                        if (oldKeep < previous.length || keep < count) truncated.set(true);
                    } else {
                        int keep = Math.min(count, outputBytes - bytes.size());
                        if (keep > 0) bytes.write(buffer, 0, keep);
                        if (keep < count) truncated.set(true);
                    }
                }
            } catch (java.io.IOException expectedOnTimeout) { }
        }, "unbound-network-output");
        drain.setDaemon(true); drain.start();
        try {
            boolean done = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!done) { process.destroyForcibly(); process.waitFor(500, TimeUnit.MILLISECONDS); }
            drain.join(500);
            synchronized (bytes) {
                byte[] captured = bytes.toByteArray();
                int first = 0;
                if (tail && truncated.get()) while (first < captured.length && (captured[first] & 0xC0) == 0x80) first++;
                return "exit=" + (done ? process.exitValue() : -1) + "; timedOut=" + !done + "\n"
                        + new String(captured, first, captured.length - first, java.nio.charset.StandardCharsets.UTF_8)
                        + (truncated.get() ? "\n[output limited to " + (tail ? "last " : "") + outputBytes + " bytes]\n" : "\n")
                        + (drain.isAlive() ? "[output reader did not finish before the deadline]\n" : "");
            }
        } finally {
            process.destroyForcibly();
            process.getInputStream().close(); process.getErrorStream().close(); process.getOutputStream().close();
        }
    }

    private NetworkDiagnostics() { }
}
