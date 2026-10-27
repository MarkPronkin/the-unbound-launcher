package dev.beampro.bridge;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A leased, panel-only power session. Never puts Android or the glasses to sleep. */
final class PhoneScreenPower {
    static final String TOKEN = "dev.beampro.bridge.PHONE_SCREEN";
    static final int TRANSACTION = 0x00FFFFF8;
    private final DisplayManager displays;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Process watchdog;
    private OutputStreamWriter commands;
    private IBinder owner;
    private IBinder.DeathRecipient death;
    private int glassesId;
    private long renewed;
    private final Runnable leaseCheck = this::check;

    PhoneScreenPower(Context context) {
        displays = context.getSystemService(DisplayManager.class);
        displays.registerDisplayListener(new DisplayManager.DisplayListener() {
            public void onDisplayAdded(int id) {}
            public void onDisplayChanged(int id) { check(); }
            public void onDisplayRemoved(int id) { check(); }
        }, handler);
    }

    synchronized void request(int command, IBinder token, int externalDisplay) throws Exception {
        if (command < 1 || command > 3 || token == null) throw new IllegalArgumentException("Invalid phone-screen request");
        if (command == 1) {
            if (owner != null) throw new IllegalStateException("Phone screen already off");
            requireGlasses(externalDisplay);
            // Resolve logical display zero's physical address. Display ordering is NOT stable,
            // and selecting physicalIds[0] could power down the glasses instead of the phone.
            Display phone = displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (phone == null || ((Integer) Display.class.getMethod("getType").invoke(phone)) != 1)
                throw new IllegalStateException("No built-in phone display");
            Object address = Display.class.getMethod("getAddress").invoke(phone);
            long physical = (Long) address.getClass().getMethod("getPhysicalDisplayId").invoke(address);
            String classpath = System.getenv("CLASSPATH");
            if (classpath == null || classpath.isEmpty()) throw new IllegalStateException("Helper classpath unavailable");
            ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/",
                    Watchdog.class.getName(), Long.toString(physical));
            builder.environment().put("CLASSPATH", classpath);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            watchdog = builder.start();
            commands = new OutputStreamWriter(watchdog.getOutputStream(), "UTF-8");
            owner = token;
            death = () -> { synchronized (PhoneScreenPower.this) { if (owner == token) restore(); } };
            try {
                token.linkToDeath(death, 0);
                await("READY");
                send("OFF");
                await("OFF");
                glassesId = externalDisplay;
                renewed = SystemClock.elapsedRealtime();
                handler.postDelayed(leaseCheck, 2000);
                System.out.println("BEAM_PHONE_SCREEN_OFF physical=" + physical + " glasses=" + glassesId);
            } catch (Exception error) { restore(); throw error; }
        } else if (owner != null) {
            if (owner != token) throw new SecurityException("Different phone-screen session owner");
            if (command == 3) {
                if (!restore()) throw new IllegalStateException("Phone display restoration failed; press the power button");
            } else {
                requireGlasses(glassesId);
                if (watchdog == null || !watchdog.isAlive()) {
                    restore();
                    throw new IllegalStateException("Phone-screen watchdog stopped");
                }
                send("PING");
                renewed = SystemClock.elapsedRealtime();
            }
        } else if (command == 2) throw new IllegalStateException("Phone-screen session expired");
    }

    private void requireGlasses(int id) throws Exception {
        Display display = displays.getDisplay(id);
        if (id == Display.DEFAULT_DISPLAY || display == null || display.getState() != Display.STATE_ON
                || ((Integer) Display.class.getMethod("getType").invoke(display)) != 2)
            throw new IllegalStateException("AR Space needs a connected external display");
    }

    private synchronized void check() {
        if (owner == null) return;
        try {
            requireGlasses(glassesId);
            if (!owner.isBinderAlive() || SystemClock.elapsedRealtime() - renewed > 8000
                    || watchdog == null || !watchdog.isAlive()) { restore(); return; }
        } catch (Exception error) { restore(); return; }
        handler.removeCallbacks(leaseCheck);
        handler.postDelayed(leaseCheck, 2000);
    }

    private void send(String value) throws java.io.IOException {
        commands.write(value + "\n");
        commands.flush();
    }

    private void await(String expected) throws Exception {
        // app_process may emit vendor diagnostics before our protocol response.
        java.io.InputStream input = watchdog.getInputStream();
        long deadline = SystemClock.elapsedRealtime() + 3000;
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
        throw new IllegalStateException("No " + expected + " acknowledgement from phone-screen watchdog");
    }

    private boolean restore() {
        boolean restored = true;
        if (owner != null && death != null) {
            try { owner.unlinkToDeath(death, 0); } catch (java.util.NoSuchElementException ignored) {}
        }
        if (watchdog != null) {
            try {
                send("ON");
                await("ON");
            } catch (Exception error) {
                // EOF also restores the panel. Do not kill the process before its finally block.
                try { watchdog.getOutputStream().close(); } catch (Exception ignored) {}
                try { restored = watchdog.waitFor(3500, TimeUnit.MILLISECONDS) && watchdog.exitValue() == 0; }
                catch (Exception ignored) { restored = false; }
                if (!restored) System.err.println("BEAM_PHONE_SCREEN_RESTORE_FAILED " + error);
            }
        }
        owner = null; death = null; watchdog = null; commands = null;
        handler.removeCallbacks(leaseCheck);
        System.out.println("BEAM_PHONE_SCREEN_RESTORED success=" + restored);
        return restored;
    }

    /** Separate process: restores on helper death (stdin EOF) or a stalled heartbeat. */
    public static final class Watchdog {
        public static void main(String[] args) throws Exception {
            int uid = android.os.Process.myUid();
            if ((uid != 2000 && uid != 0) || args.length != 1) throw new SecurityException("Shell helper only");
            Class<?> surface = Class.forName("android.view.SurfaceControl");
            Class<?> control = surface;
            try { control.getMethod("getPhysicalDisplayToken", long.class); }
            catch (NoSuchMethodException moved) {
                Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
                String path = System.getenv("SYSTEMSERVERCLASSPATH");
                if (path == null || path.isEmpty()) path = "/system/framework/services.jar";
                ClassLoader loader = (ClassLoader) factory.getDeclaredMethod("createClassLoader",
                        String.class, String.class, String.class, ClassLoader.class, int.class, boolean.class, String.class)
                        .invoke(null, path, null, null, ClassLoader.getSystemClassLoader(), 0, true, null);
                control = loader.loadClass("com.android.server.display.DisplayControl");
                Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
                load.setAccessible(true);
                load.invoke(Runtime.getRuntime(), control, "android_servers");
            }
            IBinder panel = (IBinder) control.getMethod("getPhysicalDisplayToken", long.class)
                    .invoke(null, Long.parseLong(args[0]));
            if (panel == null) throw new IllegalStateException("Phone panel unavailable");
            Method power = surface.getMethod("setDisplayPowerMode", IBinder.class, int.class);
            LinkedBlockingQueue<String> input = new LinkedBlockingQueue<>(16);
            Thread reader = new Thread(() -> {
                try {
                    BufferedReader stream = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
                    String line;
                    while ((line = stream.readLine()) != null) input.put(line);
                } catch (Exception ignored) {}
                finally { try { input.put("ON"); } catch (InterruptedException ignored) {} }
            }, "phone-screen-commands");
            reader.setDaemon(true); reader.start();
            boolean attemptedOff = false;
            System.out.println("READY"); System.out.flush();
            try {
                while (true) {
                    String command = input.poll(8, TimeUnit.SECONDS);
                    if (command == null || command.equals("ON")) break;
                    if (command.equals("OFF") && !attemptedOff) {
                        attemptedOff = true;
                        power.invoke(null, panel, 0);
                        System.out.println("OFF"); System.out.flush();
                    } else if (!command.equals("PING")) throw new IllegalArgumentException("Invalid watchdog command");
                }
            } finally {
                if (attemptedOff) power.invoke(null, panel, 2);
                System.out.println("ON"); System.out.flush();
            }
            // Native framework threads need not keep this narrowly scoped watchdog alive.
            System.exit(0);
        }
    }
}
