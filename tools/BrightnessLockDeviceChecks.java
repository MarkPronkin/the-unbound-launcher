package dev.beampro.brightnesschecks;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Drive the selected APK's stock brightness observer with real Settings values; no Unity. */
public final class BrightnessLockDeviceChecks {
    private static final String MONITOR = "com.xreal.evapro.ScreenBrightnessMonitor";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final boolean expectBug;
    private int checks;
    private static volatile String savedMode, savedBrightness;

    private BrightnessLockDeviceChecks(Context context, boolean expectBug) {
        this.context = context;
        this.expectBug = expectBug;
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        BrightnessLockDeviceChecks checks = new BrightnessLockDeviceChecks(context, Boolean.parseBoolean(args[0]));
        // A failure inside stock observer code must still restore the device settings.
        Thread.setDefaultUncaughtExceptionHandler((thread1, error) -> {
            System.out.println("FAIL uncaught " + error);
            error.printStackTrace(System.out);
            restore();
            System.exit(1);
        });
        new Thread(checks::run, "BrightnessLockChecks").start();
        Looper.loop();
    }

    private final class Session {
        final List<Integer> values = new ArrayList<>();
        final Object callback;
        final Object monitor;

        Session() throws Exception {
            Class<?> type = Class.forName(MONITOR + "$Callback");
            // Unity's AndroidJavaProxy is also a java.lang.reflect.Proxy for this interface.
            callback = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.getName().equals("onBrightnessChanged")) {
                    synchronized (values) { values.add((Integer) args[0]); values.notifyAll(); }
                    return null;
                }
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                return "BrightnessCallback";
            });
            Object[] created = new Object[1];
            onMain(() -> {
                try {
                    // The stock constructor binds the observer Handler to the current Looper.
                    created[0] = Class.forName(MONITOR).getConstructor().newInstance();
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            monitor = created[0];
            // startMonitor's state without its registration, which ContentService
            // accepts only from app processes; this shell process dispatches instead.
            setField(monitor, "resolver", context.getContentResolver());
            setField(monitor, "callback", callback);
        }

        List<Integer> snapshot() { synchronized (values) { return new ArrayList<>(values); } }

        // The stock observer runs on its own Handler and reads the real setting
        // before the patched callback.
        void change(int value) throws Exception {
            put(value);
            Object observer = field(monitor, "observer");
            observer.getClass().getMethod("dispatchChange", boolean.class, android.net.Uri.class)
                    .invoke(observer, false, android.provider.Settings.System.getUriFor("screen_brightness"));
            onMain(() -> {});
        }
    }

    private void run() {
        try {
            useShellSettingsProvider();
            savedMode = settings("get", "screen_brightness_mode");
            savedBrightness = settings("get", "screen_brightness");
            settings("put", "screen_brightness_mode", "0");
            put(100);
            Session session = new Session();
            // Auto-brightness style changes, real lock (0) and unlock values, then a repeat lock.
            int[] sequence = {120, 0, 80, 60, 30, 0, 40};
            List<Integer> expected = expectBug ? asList(sequence) : Arrays.asList(0, 80, 0, 40);
            List<Integer> forwarded = new ArrayList<>();
            for (int value : sequence) {
                session.change(value);
                if (expectBug || filterForwards(forwarded, value)) forwarded.add(value);
                check(session.snapshot().equals(forwarded), "after " + value + ": " + session.snapshot() + ", expected " + forwarded);
            }
            check(session.snapshot().equals(expected), "forwarded " + session.snapshot() + ", expected " + expected);

            // A new controller callback starts unlocked, like a fresh stock receiver.
            Session next = new Session();
            next.change(70);
            check(next.snapshot().equals(expectBug ? Arrays.asList(70) : new ArrayList<Integer>()),
                    "new session non-zero " + next.snapshot());
            next.change(0);
            check(next.snapshot().equals(expectBug ? Arrays.asList(70, 0) : Arrays.asList(0)),
                    "new session lock " + next.snapshot());

            if (!expectBug) callbackFailures();
            System.out.println("PASS Brightness lock checks: " + checks + "; expect_bug: " + expectBug + ";");
        } catch (Throwable error) {
            System.out.println("FAIL " + error);
            error.printStackTrace(System.out);
        } finally {
            restore();
            System.exit(0);
        }
    }

    private static synchronized void restore() {
        String mode = savedMode, brightness = savedBrightness;
        savedMode = savedBrightness = null;
        if (mode == null) return;
        try {
            settings("put", "screen_brightness_mode", mode);
            if (brightness != null && !brightness.equals("null")) settings("put", "screen_brightness", brightness);
            System.out.println("Restored screen_brightness_mode=" + mode + " screen_brightness=" + brightness);
        } catch (Exception error) { System.out.println("FAIL restore " + error); }
    }

    // Shell processes are not app processes, so ActivityThread cannot acquire the
    // settings provider. Use the external connection the settings command uses.
    private static void useShellSettingsProvider() throws Exception {
        Object manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Object holder = manager.getClass().getMethod("getContentProviderExternal", String.class, int.class,
                android.os.IBinder.class, String.class).invoke(manager, "settings", 0, new android.os.Binder(), "BrightnessLockChecks");
        Object provider = holder.getClass().getField("provider").get(holder);
        java.lang.reflect.Field system = android.provider.Settings.System.class.getDeclaredField("sProviderHolder");
        system.setAccessible(true);
        setField(system.get(null), "mContentProvider", provider);
    }

    // The filter must surface callback exceptions as the stock interface call does.
    private void callbackFailures() throws Exception {
        Class<?> type = Class.forName(MONITOR + "$Callback");
        Object failing = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("onBrightnessChanged")) throw new IllegalArgumentException("callback failed");
            return method.getName().equals("hashCode") ? System.identityHashCode(proxy) : null;
        });
        Method filter = Class.forName("dev.beampro.port.BrightnessLockCompat").getMethod("onBrightnessChanged", Object.class, int.class);
        try {
            filter.invoke(null, failing, 0);
            check(false, "callback exception was swallowed");
        } catch (InvocationTargetException error) {
            check(error.getCause() instanceof IllegalArgumentException
                    && "callback failed".equals(error.getCause().getMessage()), "callback exception type " + error.getCause());
        }
    }

    // Lock (0) and unlock (non-zero) pass only when they change the previous lock state.
    private static boolean filterForwards(List<Integer> forwarded, int value) {
        boolean locked = !forwarded.isEmpty() && forwarded.get(forwarded.size() - 1) == 0;
        return (value == 0) != locked;
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private void put(int value) throws Exception {
        settings("put", "screen_brightness", Integer.toString(value));
        check(settings("get", "screen_brightness").equals(Integer.toString(value)), "setting readback " + value);
    }

    private static String settings(String... args) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList("settings", args[0], "system"));
        command.addAll(Arrays.asList(args).subList(1, args.length));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[256];
            for (int read; (read = input.read(buffer)) > 0; ) output.write(buffer, 0, read);
        }
        if (process.waitFor() != 0) throw new IllegalStateException("settings " + command + ": " + output);
        return output.toString("UTF-8").trim();
    }

    private void onMain(Runnable action) {
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        main.post(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } finally { done.countDown(); } });
        try { done.await(); }
        catch (InterruptedException error) { throw new AssertionError(error); }
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static List<Integer> asList(int[] values) {
        List<Integer> list = new ArrayList<>();
        for (int value : values) list.add(value);
        return list;
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
