package dev.beampro.port;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Ignore contact jitter before the original trackpad recognizer sees a MOVE. */
public final class TrackpadCompat {
    // Coordinates span [-1, 1]. This is 1.25% of the pad in each direction.
    private static final float TAP_SLOP = 0.025f;
    private static WeakReference<Object> owner = new WeakReference<>(null);
    private static boolean pendingTap;
    private static int pointerId;
    private static float downX, downY;
    private static Method callback;

    private static synchronized boolean accept(Object receiver, int action, int actionId,
            int[] ids, float[] x, float[] y) {
        if (owner.get() != receiver) {
            owner = new WeakReference<>(receiver);
            pendingTap = false;
        }
        boolean single = ids != null && x != null && y != null
                && ids.length == 1 && x.length == 1 && y.length == 1;
        if (action == 0) {
            pendingTap = single && Float.isFinite(x[0]) && Float.isFinite(y[0])
                    && Math.abs(x[0]) < 0.8f; // Edge scroll strip keeps all movement.
            if (pendingTap) { pointerId = ids[0]; downX = x[0]; downY = y[0]; }
        } else if (action == 2 && pendingTap && single && ids[0] == pointerId) {
            if (Math.abs(x[0] - downX) <= TAP_SLOP && Math.abs(y[0] - downY) <= TAP_SLOP) {
                return false;
            }
            pendingTap = false; // Once a drag starts, returning to the origin cannot create a tap.
        } else {
            pendingTap = false; // UP, CANCEL and every multi-finger transition remain intact.
        }
        return true;
    }

    public static void forward(Object receiver, int action, int actionId,
            int[] ids, float[] x, float[] y) {
        if (!accept(receiver, action, actionId, ids, x, y)) return;
        try {
            Method method = callback;
            if (method == null || !method.getDeclaringClass().isInstance(receiver)) {
                method = receiver.getClass().getMethod("FillTouchpadInput", int.class, int.class,
                        int[].class, float[].class, float[].class);
                callback = method;
            }
            method.invoke(receiver, action, actionId, ids, x, y);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Trackpad callback failed", cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Trackpad callback unavailable", error);
        }
    }
}
