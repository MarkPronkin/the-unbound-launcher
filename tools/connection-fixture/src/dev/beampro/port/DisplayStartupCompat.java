package dev.beampro.port;
import android.view.Display;
public final class DisplayStartupCompat {
    static boolean xreal(Display display) { return display != null && display.getName().equals("XREAL connection handoff fixture"); }
}
