package dev.beampro.port;

import android.content.Context;
import android.util.Log;
import android.util.AtomicFile;
import java.io.File;

/** Reads the settings UI's preference contract without depending on its DEX. */
public final class ExperimentalOptionsCompat {
    private static final String FILE = "unbound-experimental-options";
    private static final String QUIET_PHOTOS = "disable_eye_shutter_sound";
    private static final String SKIP_SCREEN_OFF_CONFIRMATION = "skip_screen_off_confirmation";

    private ExperimentalOptionsCompat() {}

    private static boolean enabled(Context context, String key) {
        if (context == null) return false;
        File path = new File(context.getFilesDir(), FILE + "-" + key);
        try {
            byte[] value = new AtomicFile(path).readFully();
            return value.length == 1 && value[0] == 1;
        }
        catch (java.io.FileNotFoundException absent) { return false; }
        catch (Exception error) {
            Log.w("UnboundExperimental", "Keeping the default behavior for " + key, error);
            return false;
        }
    }

    public static boolean skipScreenOffConfirmation(Context context) {
        return enabled(context, SKIP_SCREEN_OFF_CONFIRMATION);
    }

    /** Only the capture coroutine's raw/kaca cue is muted; all other sounds pass through. */
    public static boolean suppressPhotoSound(Context context, int resource) {
        if (!enabled(context, QUIET_PHOTOS)) return false;
        try {
            return "raw".equals(context.getResources().getResourceTypeName(resource))
                    && "kaca".equals(context.getResources().getResourceEntryName(resource))
                    && context.getPackageName().equals(context.getResources().getResourcePackageName(resource));
        } catch (Exception unknownResource) { return false; }
    }
}
