package dev.beampro.port;

import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/** Distinguish the phone IME from navigation bars and display cutouts. */
public final class KeyboardCompat {
    private KeyboardCompat() {}

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
