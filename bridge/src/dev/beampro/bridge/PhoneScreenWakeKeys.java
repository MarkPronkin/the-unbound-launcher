package dev.beampro.bridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** Observe volume buttons without owning a window or consuming controller input. */
final class PhoneScreenWakeKeys implements AutoCloseable {
    private final List<Process> readers = new ArrayList<>();
    private volatile boolean closed;

    static PhoneScreenWakeKeys start(Runnable wake) throws Exception {
        return start(wake, "/system/bin/getevent");
    }

    static PhoneScreenWakeKeys start(Runnable wake, String executable) throws Exception {
        ShellCommand.Result devices = ShellCommand.run(2000, executable, "-lp");
        if (!devices.succeeded()) throw new IllegalStateException("Volume-button recovery is unavailable");
        List<String> paths = volumeDevices(devices.output);
        if (paths.isEmpty()) throw new IllegalStateException("No readable volume buttons for screen recovery");
        PhoneScreenWakeKeys keys = new PhoneScreenWakeKeys();
        try {
            for (String path : paths) {
                Process process = new ProcessBuilder(executable, "-ql", path)
                        .redirectErrorStream(true).start();
                keys.readers.add(process);
                Thread reader = new Thread(() -> keys.read(process, wake), "phone-screen-volume");
                reader.setDaemon(true);
                reader.start();
            }
            // An unreadable/disconnected input node exits immediately. Keep the panel
            // on if the independent recovery path could not be established.
            for (Process process : keys.readers)
                if (process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    throw new IllegalStateException("Volume-button recovery could not start");
            return keys;
        } catch (Exception error) { keys.close(); throw error; }
    }

    static List<String> volumeDevices(String listing) {
        List<String> result = new ArrayList<>();
        String path = null;
        for (String line : listing.split("\n")) {
            if (line.startsWith("add device ")) {
                int separator = line.indexOf(": ");
                path = separator < 0 ? null : line.substring(separator + 2).trim();
                if (path != null && !path.matches("/dev/input/event[0-9]+")) path = null;
            } else if (path != null && (line.contains("KEY_VOLUMEUP") || line.contains("KEY_VOLUMEDOWN"))) {
                if (!result.contains(path)) result.add(path);
            }
        }
        return result;
    }

    static boolean volumeReleased(String line) {
        String[] fields = line.trim().split("\\s+");
        int n = fields.length;
        return n >= 3 && fields[n - 3].equals("EV_KEY")
                && (fields[n - 2].equals("KEY_VOLUMEUP") || fields[n - 2].equals("KEY_VOLUMEDOWN"))
                && fields[n - 1].equals("UP");
    }

    private void read(Process process, Runnable wake) {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
            String line;
            while (!closed && (line = input.readLine()) != null) {
                if (volumeReleased(line)) { wake.run(); return; }
            }
        } catch (Exception ignored) { /* Losing recovery must restore the panel too. */ }
        if (!closed) wake.run();
    }

    @Override public void close() {
        closed = true;
        for (Process process : readers) process.destroy();
        readers.clear();
    }
}
