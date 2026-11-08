package dev.beampro.port;

import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;

/** Distinguish the phone IME from navigation bars and display cutouts. */
public final class KeyboardCompat {
    private KeyboardCompat() {}

    /** Keep the stock IME focus target without letting its invisible window eat phone touches. */
    public static void addImeMask(WindowManager windows, View view, ViewGroup.LayoutParams layout) {
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) layout;
        // A zero-sized focusable overlay is still touch-modal unless explicitly
        // excluded from touch dispatch. Leave focus and IME flags intact.
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        windows.addView(view, params);
    }

    public static boolean isVisible(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (Build.VERSION.SDK_INT >= 30) {
            // A detached or not-yet-laid-out controller has no IME evidence.
            return insets != null && insets.isVisible(WindowInsets.Type.ime());
        }
        // Android 10 has no public IME visibility type. Ignore stable system
        // bars and require a keyboard-sized gap, including in landscape.
        Rect visible = new Rect();
        view.getWindowVisibleDisplayFrame(visible);
        int height = view.getRootView().getHeight();
        int bars = insets == null ? 0 : insets.getStableInsetBottom();
        float density = view.getResources().getDisplayMetrics().density;
        return height > 0 && height - visible.bottom - bars > Math.max(100 * density, height * 0.15f);
    }
}
