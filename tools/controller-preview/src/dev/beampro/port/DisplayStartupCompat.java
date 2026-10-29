package dev.beampro.port;

import android.view.Display;

/** Emulator fixture identity; production screen power still requires physical glasses. */
public final class DisplayStartupCompat {
    public static boolean xreal(Display display) {
        return display != null && "Controller fixture glasses".equals(display.getName());
    }
}
