package dev.beampro.port;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Session-owned pointer suspension, executed and acknowledged on Unity's thread. */
final class PhonePointerCompat {
    interface Controls { int setSuspended(boolean suspended); }
    private static PhonePointerCompat owner; // Unity thread only.
    private final Object activity;
    private final Controls controls;
    private final long timeoutMillis;

    PhonePointerCompat(Object activity) { this(activity, Native::setSuspended, 3000); }
    PhonePointerCompat(Object activity, Controls controls, long timeoutMillis) {
        this.activity = activity; this.controls = controls; this.timeoutMillis = timeoutMillis;
    }

    void suspend() throws Exception { change(true); }
    void restore() throws Exception { change(false); }

    private void change(boolean suspend) throws Exception {
        Field field = null;
        for (Class<?> type = activity.getClass(); type != null && field == null; type = type.getSuperclass()) {
            try { field = type.getDeclaredField("mUnityPlayer"); } catch (NoSuchFieldException ignored) {}
        }
        if (field == null) throw new IllegalStateException("Motion pointer player unavailable");
        field.setAccessible(true);
        Object player = field.get(activity);
        if (player == null) throw new IllegalStateException("Motion pointer player not ready");
        Method queue = player.getClass().getDeclaredMethod("queueGLThreadEvent", Runnable.class);
        queue.setAccessible(true);
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        queue.invoke(player, (Runnable) () -> {
            try {
                if (!valid.get()) return;
                if (suspend) {
                    if (owner != null && owner != this) throw new IllegalStateException("Another phone pointer session is active");
                    owner = this;
                    if (controls.setSuspended(true) != 0) throw new IllegalStateException("Motion pointer did not pause");
                } else if (owner == this) {
                    controls.setSuspended(false);
                    owner = null;
                }
            } catch (Throwable error) { failure.set(error); }
            finally { done.countDown(); }
        });
        if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            // A late suspend must never disable a restored phone. A late restore
            // still needs to run if Unity recovers from a stalled frame.
            if (suspend) valid.set(false);
            throw new IllegalStateException("Motion pointer did not respond");
        }
        Throwable error = failure.get();
        if (error != null) throw new IllegalStateException("Motion pointer control failed: " + error.getMessage(), error);
    }

    private static native int nativeSetSuspended(boolean suspended);
    private static final class Native {
        static { System.loadLibrary("UnboundPhonePointer"); }
        static int setSuspended(boolean suspended) { return nativeSetSuspended(suspended); }
    }
}
