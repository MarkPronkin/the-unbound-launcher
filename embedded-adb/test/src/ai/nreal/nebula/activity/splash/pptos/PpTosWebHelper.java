package ai.nreal.nebula.activity.splash.pptos;

/** Records legal-link routing in the isolated test APK without opening a browser. */
public final class PpTosWebHelper {
    public static int privacyOpened, termsOpened;
    private final boolean privacy;
    public PpTosWebHelper(boolean privacy) { this.privacy = privacy; }
    public void showThis(android.content.Context context) {
        if (privacy) privacyOpened++; else termsOpened++;
    }
}
