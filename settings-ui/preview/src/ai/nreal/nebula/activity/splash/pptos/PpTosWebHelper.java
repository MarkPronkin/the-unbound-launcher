package ai.nreal.nebula.activity.splash.pptos;

import android.content.Context;

/** The original legal helper boundary, kept local during Android UI checks. */
public final class PpTosWebHelper {
    public static int privacyClicks, termsClicks;
    public static Context owner;
    private final boolean privacy;
    public PpTosWebHelper(boolean privacy) { this.privacy = privacy; }
    public void showThis(Context context) {
        owner = context;
        if (privacy) privacyClicks++; else termsClicks++;
    }
}
