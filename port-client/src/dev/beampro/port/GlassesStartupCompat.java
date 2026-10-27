package dev.beampro.port;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;
import java.lang.reflect.Field;

/** Compatibility for the unsupported startup query on USB type 11 glasses. */
public final class GlassesStartupCompat {
    private static final String TAG = "UnboundStartup";
    private GlassesStartupCompat() {}

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    /** Called only at the SDK's startup-state query, after nativeGlassesInit succeeds. */
    public static int getStartupState(Object control, Object sdk) {
        final int state;
        try {
            state = ((Number) control.getClass().getMethod("nativeGetGlassesStartupState").invoke(control)).intValue();
        } catch (Exception error) {
            Log.w(TAG, "Cannot query glasses startup state", error);
            return -1;
        }
        if (state != -1) return state;
        try {
            // Capture the native error before model/display checks. The SDK's
            // original checkError call still follows this hook and reports it.
            int error = ((Number) Class.forName("com.xreal.glasses.api.Startup")
                    .getMethod("nativeGetApiErrorCode").invoke(null)).intValue();
            if (error != 103 || !Boolean.FALSE.equals(field(sdk, "isEthernetSeries"))) return state;
            Class<?> init = Class.forName("com.xreal.glassesdisplayplugevent.GlassesInitSetting");
            Object settings = init.getMethod("getInstance").invoke(null);
            int type = ((Number) init.getMethod("checkGlassesType").invoke(settings)).intValue();
            if (type != 11) return state;
            Context context = (Context) field(sdk, "mContext");
            DisplayManager displays = context.getSystemService(DisplayManager.class);
            if (displays == null) return state;
            for (Display display : displays.getDisplays()) {
                if (!DisplayStartupCompat.xreal(display) || display.getState() != Display.STATE_ON) continue;
                Display.Mode mode = display.getMode();
                if (mode != null && mode.getPhysicalHeight() == 1080
                        && (mode.getPhysicalWidth() == 1920 || mode.getPhysicalWidth() == 3840)) {
                    Log.i(TAG, "Type 11 startup query unsupported (103); native init succeeded and XREAL display "
                            + display.getDisplayId() + " is active at " + mode.getPhysicalWidth() + "x1080");
                    return 1;
                }
            }
            Log.w(TAG, "Type 11 startup query unsupported (103); waiting for an active XREAL display");
        } catch (Exception error) {
            Log.w(TAG, "Cannot verify type 11 display readiness", error);
        }
        return state;
    }
}
