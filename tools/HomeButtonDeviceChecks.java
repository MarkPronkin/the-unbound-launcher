package dev.beampro.homebuttonchecks;

import android.content.Context;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Exercise the selected APK's actual listeners and Handler timers without Unity or haptics. */
public final class HomeButtonDeviceChecks {
    private final List<Controller> controllers = new ArrayList<>();
    private boolean expectBug;
    private int checks;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    private HomeButtonDeviceChecks(Context context, boolean expectBug) {
        this.context = context;
        this.expectBug = expectBug;
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        // Android 17's View constructor expects app-bound shared memory. This
        // isolated shell process needs its own instance; no device state is changed.
        try {
            Class<?> shared = Class.forName("com.android.internal.os.ApplicationSharedMemory");
            try { shared.getMethod("getInstance").invoke(null); }
            catch (InvocationTargetException absent) {
                shared.getMethod("setInstance", shared).invoke(null, shared.getMethod("create").invoke(null));
            }
        } catch (ClassNotFoundException olderAndroid) {}
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        Context context = system.createPackageContext("dev.markpronkin.unbound", Context.CONTEXT_IGNORE_SECURITY);
        HomeButtonDeviceChecks checks = new HomeButtonDeviceChecks(context, Boolean.parseBoolean(args[0]));
        new Thread(checks::run, "HomeButtonChecks").start();
        Looper.loop();
    }

    private Context getTargetContext() { return context; }

    private void runOnMainSync(Runnable action) {
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> { try { action.run(); } finally { done.countDown(); } });
        try { done.await(); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }

    private void waitForIdleSync() { runOnMainSync(() -> {}); }

    private final class Controller {
        final Object fragment;
        final View.OnTouchListener listener;
        final FrameLayout home;
        final Handler handler;
        final Runnable callback;
        int resets, inputAction = -1;

        Controller(String name) throws Exception {
            Class<?> type = Class.forName("ai.nreal.extension.virtualcontroller." + name);
            fragment = type.getConstructor().newInstance();
            home = new FrameLayout(getTargetContext());
            Class<?> ids = Class.forName("ai.nreal.extension.virtualcontroller.R$id");
            home.setId(ids.getField("home_button").getInt(null));
            home.layout(0, 0, 200, 100);
            set("homeButton", home);
            set("ivHome", new ImageView(getTargetContext()));
            Class<?> receiver = Class.forName("ai.nreal.extension.virtualcontroller.ISystemButtonDataReceiver");
            Object proxy = Proxy.newProxyInstance(receiver.getClassLoader(), new Class<?>[]{receiver}, (p, m, args) -> {
                if (m.getName().equals("OnHomeLongPressed")) resets++;
                if (m.getName().equals("FillHomeButtonInput")) inputAction = (Integer) args[2];
                if (m.getReturnType() == boolean.class) return false;
                if (m.getReturnType() == int.class) return 0;
                return null;
            });
            set("systemButtonDataReceiver", proxy);
            listener = (View.OnTouchListener) get("onButtonTouchListener");
            handler = (Handler) get("mHandler");
            callback = (Runnable) get("mHomeLongPressed");
        }

        Object get(String name) throws Exception {
            Field field = fragment.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(fragment);
        }

        void set(String name, Object value) throws Exception {
            Field field = fragment.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(fragment, value);
        }

        void touch(int action, float x) {
            long now = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(now, now, action, x, 50, 0);
            try { check(listener.onTouch(home, event), "Home event consumed"); }
            finally { event.recycle(); }
        }

        void secondFinger(int action) {
            long now = SystemClock.uptimeMillis();
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
            for (int i = 0; i < 2; i++) {
                properties[i] = new MotionEvent.PointerProperties();
                properties[i].id = i;
                properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coords[i] = new MotionEvent.PointerCoords();
                coords[i].x = 100 + i * 10;
                coords[i].y = 50;
            }
            MotionEvent event = MotionEvent.obtain(now, now, action, 2, properties, coords,
                    0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try { check(listener.onTouch(home, event), "Multi-contact Home event consumed"); }
            finally { event.recycle(); }
        }

        void lifecycle(String name) throws Exception { fragment.getClass().getMethod(name).invoke(fragment); }
    }

    private interface Action { void run(Controller controller) throws Exception; }
    private void each(Action action) {
        final Throwable[] error = new Throwable[1];
        runOnMainSync(() -> {
            try { for (Controller controller : controllers) action.run(controller); }
            catch (Throwable failure) { error[0] = failure; }
        });
        if (error[0] != null) throw new AssertionError(error[0]);
    }

    private void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
        checks++;
    }

    private void waitForTimers() { SystemClock.sleep(2250); waitForIdleSync(); }

    private void run() {
        int status = 0;
        try {
            final Throwable[] setup = new Throwable[1];
            runOnMainSync(() -> {
                try {
                    for (String name : new String[]{"AirMouseFragment", "TouchPadPortraitFragment", "TouchPadLandscapeFragment"})
                        controllers.add(new Controller(name));
                } catch (Throwable error) { setup[0] = error; }
            });
            if (setup[0] != null) throw new AssertionError(setup[0]);

            each(c -> {
                c.touch(MotionEvent.ACTION_DOWN, 100);
                check(c.handler.hasCallbacks(c.callback), "Home hold timer armed");
                c.touch(MotionEvent.ACTION_CANCEL, 100);
                check(c.inputAction == MotionEvent.ACTION_CANCEL, "Cancellation still reaches Unity input boundary");
                check(c.handler.hasCallbacks(c.callback) == expectBug, "Canceled Home timer state");
            });
            waitForTimers();
            each(c -> check(c.resets == (expectBug ? 1 : 0), "Canceled Home reset count"));
            if (!expectBug) {
                each(c -> { c.touch(MotionEvent.ACTION_DOWN, 100); c.touch(MotionEvent.ACTION_UP, 100); });
                waitForTimers();
                each(c -> check(c.resets == 0 && c.inputAction == MotionEvent.ACTION_UP, "Short Home tap preserved"));

                each(c -> { c.touch(MotionEvent.ACTION_DOWN, 100); c.touch(MotionEvent.ACTION_MOVE, 1000); c.touch(MotionEvent.ACTION_MOVE, 100); });
                waitForTimers();
                each(c -> check(c.resets == 0, "Leaving Home cancels hold until a fresh down"));

                each(c -> {
                    c.touch(MotionEvent.ACTION_DOWN, 100);
                    c.secondFinger(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
                    c.secondFinger(MotionEvent.ACTION_POINTER_UP);
                });
                waitForTimers();
                each(c -> check(c.resets == 0, "Additional contact cancels hold"));

                for (String lifecycle : new String[]{"onPause", "onDestroyView"}) {
                    each(c -> { c.touch(MotionEvent.ACTION_DOWN, 100); c.lifecycle(lifecycle); });
                    waitForTimers();
                    each(c -> check(c.resets == 0, "Controller lifecycle cancels hold"));
                }

                each(c -> { c.touch(MotionEvent.ACTION_DOWN, 100); c.touch(MotionEvent.ACTION_MOVE, 101); });
                waitForTimers();
                each(c -> check(c.resets == 1, "Deliberate Home hold still resets exactly once"));
                waitForTimers();
                each(c -> { check(c.resets == 1, "Home hold does not repeat"); c.touch(MotionEvent.ACTION_UP, 100); });
            }
            System.out.println("PASS Home button checks: " + checks + "; controllers: 3; expect_bug=" + expectBug);
        } catch (Throwable error) {
            System.out.println("FAIL " + android.util.Log.getStackTraceString(error));
            status = 1;
        } finally { each(c -> c.handler.removeCallbacks(c.callback)); }
        System.exit(status);
    }
}
