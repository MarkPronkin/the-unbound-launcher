package dev.beampro.port;

import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.lang.reflect.Field;

/** Cancel the stock reset timer when a Home hold is interrupted. Main thread only. */
public final class HomeButtonCompat {
    private HomeButtonCompat() {}

    // Called only from the Home branch of each stock controller's touch listener.
    // The stock listener still owns normal taps, visuals and the two-second hold.
    public static void onTouch(Object listener, View button, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) return;
        if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1) {
            int slop = ViewConfiguration.get(button.getContext()).getScaledTouchSlop();
            float x = event.getX(), y = event.getY();
            if (x >= -slop && y >= -slop && x < button.getWidth() + slop
                    && y < button.getHeight() + slop) return;
        }
        try {
            cancel(field(listener, "this$0"), "touch action=" + action);
        } catch (ReflectiveOperationException error) {
            Log.e("PortHomeButton", "Could not cancel interrupted Home hold", error);
        }
    }

    public static void cancel(Object fragment) {
        cancel(fragment, "controller lifecycle ended");
    }

    private static void cancel(Object fragment, String reason) {
        try {
            Handler handler = (Handler) field(fragment, "mHandler");
            Runnable callback = (Runnable) field(fragment, "mHomeLongPressed");
            boolean pending = handler.hasCallbacks(callback);
            handler.removeCallbacks(callback);
            if (pending) Log.i("PortHomeButton", "Canceled Home reset: " + reason);
        } catch (ReflectiveOperationException error) {
            Log.e("PortHomeButton", "Could not cancel Home reset", error);
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
