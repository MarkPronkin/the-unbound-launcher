package dev.beampro.port;

import android.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Pass only the Beam Pro's screen-lock signal from the stock brightness observer. Observer thread only. */
public final class BrightnessLockCompat {
    private static Object owner;
    private static boolean locked;

    private BrightnessLockCompat() {}

    // Replaces the stock observer's callback. NebulaOS treats brightness 0 as a
    // screen lock and any other value as an unlock, and every unlock recenters
    // Spatial Mouse. Adaptive brightness rewrites the same setting, so forward
    // only changes between zero and non-zero.
    public static void onBrightnessChanged(Object callback, int brightness) {
        if (callback != owner) {
            // A new controller session starts unlocked, as the stock receiver does.
            owner = callback;
            locked = false;
        }
        if ((brightness == 0) == locked) {
            Log.i("PortBrightnessLock", "Ignored brightness " + brightness + "; lock state unchanged");
            return;
        }
        locked = brightness == 0;
        try {
            Method method = callback.getClass().getMethod("onBrightnessChanged", int.class);
            method.setAccessible(true);
            method.invoke(callback, brightness);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Brightness callback failed", cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Brightness callback unavailable", error);
        }
    }
}
