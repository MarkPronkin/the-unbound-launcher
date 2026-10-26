package dev.beampro.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Bounded command execution. Drain while waiting so a full pipe cannot stall am. */
final class ShellCommand {
    private static final int OUTPUT_LIMIT = 65536;

    static final class Result {
        final int exitCode;
        final boolean timedOut;
        final String output;

        Result(int exitCode, boolean timedOut, String output) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.output = output;
        }

        boolean succeeded() {
            return !timedOut && exitCode == 0 && !output.contains("Error:")
                    && !output.contains("Error type ") && !output.contains("Exception:");
        }
    }

    static Result run(long timeoutMillis, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    synchronized (output) {
                        int keep = Math.min(count, OUTPUT_LIMIT - output.size());
                        if (keep > 0) output.write(buffer, 0, keep);
                    }
                }
            } catch (IOException ignored) { /* Expected when a timed-out command is killed. */ }
        }, "beam-launch-output");
        drain.setDaemon(true);
        drain.start();
        try {
            boolean completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(1000, TimeUnit.MILLISECONDS);
            }
            drain.join(1000);
            synchronized (output) {
                return new Result(completed ? process.exitValue() : -1, !completed, output.toString("UTF-8"));
            }
        } finally {
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
            process.getOutputStream().close();
            process.getErrorStream().close();
        }
    }
}
