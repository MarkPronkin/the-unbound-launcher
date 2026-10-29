package dev.beampro.bridge;

import android.os.IBinder;
import android.os.SystemClock;
import java.io.*;
import java.nio.channels.FileLock;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** App-owned lease; a separate shell process restores settings on helper death. */
final class TrackpadGestures {
    static final String TOKEN = "dev.beampro.bridge.TRACKPAD_GESTURES";
    static final int TRANSACTION = 0x00FFFFF1;
    private final File journal;
    private final int user;
    private Process watchdog;
    private OutputStreamWriter commands;
    private IBinder owner;
    private IBinder.DeathRecipient death;

    TrackpadGestures(File directory, int uid) {
        journal = new File(directory, "trackpad-gestures-" + uid + ".properties");
        user = uid / 100000;
        if (supported()) new Thread(() -> {
            synchronized (TrackpadGestures.this) {
                if (owner != null) return;
                try { ensureWatchdog(); release(); }
                catch (Exception error) { release(); System.err.println("BEAM_TRACKPAD_RECOVERY_FAILED " + error); }
            }
        }, "trackpad-gesture-recovery").start();
    }

    private static boolean supported() {
        try { Class.forName("android.os.OplusGestureMonitorManager"); return true; }
        catch (ClassNotFoundException absent) { return false; }
    }

    synchronized void request(int command, IBinder token) throws Exception {
        if (command < 1 || command > 3 || token == null) throw new IllegalArgumentException("Invalid trackpad gesture lease");
        if (!supported()) return;
        if (owner != null && !owner.equals(token)) throw new SecurityException("Different trackpad gesture owner");
        if (command == 3) { release(); return; }
        try {
            if (owner == null || watchdog == null || !watchdog.isAlive()) {
                release();
                ensureWatchdog();
                owner = token;
                death = () -> { synchronized (TrackpadGestures.this) { if (token.equals(owner)) release(); } };
                token.linkToDeath(death, 0);
                send("START"); await("ACTIVE");
            } else send("PING");
        } catch (Exception error) { release(); throw error; }
    }

    private void ensureWatchdog() throws Exception {
        String path = System.getenv("CLASSPATH");
        if (path == null || path.isEmpty()) throw new IllegalStateException("Helper classpath unavailable");
        ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/", Watchdog.class.getName(),
                journal.getPath(), Integer.toString(user));
        builder.environment().put("CLASSPATH", path);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        watchdog = builder.start();
        commands = new OutputStreamWriter(watchdog.getOutputStream(), "UTF-8");
        await("READY");
    }

    private void send(String line) throws IOException { commands.write(line + "\n"); commands.flush(); }

    private void await(String expected) throws Exception {
        InputStream input = watchdog.getInputStream();
        long deadline = SystemClock.elapsedRealtime() + 12000;
        StringBuilder line = new StringBuilder();
        while (SystemClock.elapsedRealtime() < deadline) {
            while (input.available() > 0) {
                int ch = input.read();
                if (ch == '\n') {
                    if (line.toString().equals(expected)) return;
                    if (line.toString().startsWith("ERROR")) throw new IllegalStateException(line.toString());
                    line.setLength(0);
                } else if (line.length() < 1024) line.append((char) ch);
            }
            if (!watchdog.isAlive()) break;
            Thread.sleep(10);
        }
        throw new IllegalStateException("No " + expected + " from trackpad gesture watchdog");
    }

    private void release() {
        if (owner != null && death != null) {
            try { owner.unlinkToDeath(death, 0); } catch (java.util.NoSuchElementException ignored) {}
        }
        owner = null; death = null;
        if (commands != null) {
            try { send("STOP"); await("RESTORED"); }
            catch (Exception error) { System.err.println("BEAM_TRACKPAD_RESTORE_PENDING " + error); }
            finally { try { commands.close(); } catch (Exception ignored) {} }
        }
        commands = null; watchdog = null;
    }

    public static final class Watchdog {
        public static void main(String[] args) {
            int result = 0;
            try {
                if (android.os.Process.myUid() != 2000 || args.length != 2) throw new SecurityException("Shell helper only");
                File file = new File(args[0]);
                int user = Integer.parseInt(args[1]);
                File directory = file.getParentFile();
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Recovery directory unavailable");
                LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(16);
                Thread reader = new Thread(() -> {
                    try {
                        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
                        String line;
                        while ((line = input.readLine()) != null) queue.put(line);
                    } catch (Exception ignored) {}
                    finally { queue.clear(); queue.offer("STOP"); }
                }, "trackpad-gesture-pipe");
                reader.setDaemon(true); reader.start();
                try (RandomAccessFile lockFile = new RandomAccessFile(file.getPath() + ".lock", "rw")) {
                    FileLock lock = null;
                    long deadline = SystemClock.elapsedRealtime() + 10000;
                    while (lock == null && SystemClock.elapsedRealtime() < deadline) {
                        lock = lockFile.getChannel().tryLock();
                        if (lock == null) Thread.sleep(50);
                    }
                    if (lock == null) throw new IOException("Previous gesture session is still restoring");
                    try (TrackpadGesturePolicy policy = new TrackpadGesturePolicy(new ShellSettings(user), new FileJournal(file))) {
                        response("READY");
                        while (true) {
                            String command = queue.poll(8, TimeUnit.SECONDS);
                            if (command == null || command.equals("STOP")) break;
                            if (command.equals("START")) { policy.acquire(); response("ACTIVE"); }
                            else if (!command.equals("PING")) throw new IllegalArgumentException("Invalid gesture command");
                        }
                    } finally { lock.release(); }
                }
                response("RESTORED");
            } catch (Throwable error) {
                response("ERROR " + error);
                error.printStackTrace(System.err); result = 1;
            }
            System.exit(result);
        }

        private static void response(String value) { System.out.println(value); System.out.flush(); }
    }

    private static final class ShellSettings implements TrackpadGesturePolicy.Settings {
        final String user;
        ShellSettings(int user) { this.user = Integer.toString(user); }
        private String[] parts(String key) {
            for (String allowed : TrackpadGesturePolicy.KEYS) if (allowed.equals(key)) return key.split(":", 2);
            throw new SecurityException("Unknown gesture setting");
        }
        private String run(String... args) throws Exception {
            ShellCommand.Result result = ShellCommand.run(2000, args);
            if (!result.succeeded()) throw new IOException("Gesture settings: " + result.output);
            return result.output.trim();
        }
        public String get(String key) throws Exception {
            String[] parts = parts(key);
            String value = run("settings", "--user", user, "get", parts[0], parts[1]);
            return value.equals("null") ? null : value;
        }
        public void set(String key, String value) throws Exception {
            String[] parts = parts(key);
            if (value == null) run("settings", "--user", user, "delete", parts[0], parts[1]);
            else run("settings", "--user", user, "put", parts[0], parts[1], value);
        }
    }

    private static final class FileJournal implements TrackpadGesturePolicy.Journal {
        final File file;
        FileJournal(File file) { this.file = file; }
        public Properties load() throws Exception {
            Properties state = new Properties();
            if (file.isFile()) try (FileInputStream input = new FileInputStream(file)) { state.load(input); }
            return state;
        }
        public void save(Properties state) throws Exception {
            if (state.isEmpty()) {
                if (file.exists() && !file.delete()) throw new IOException("Cannot clear gesture recovery journal");
                return;
            }
            File temporary = new File(file.getPath() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                state.store(output, "Unbound temporary trackpad gesture settings"); output.getFD().sync();
            }
            if (!temporary.renameTo(file)) throw new IOException("Cannot commit gesture recovery journal");
        }
    }
}
