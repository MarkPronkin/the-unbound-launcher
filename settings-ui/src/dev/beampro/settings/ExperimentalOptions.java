package dev.beampro.settings;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;

/** Atomic option files are read afresh by the independently running :space client. */
public final class ExperimentalOptions {
    public static final String FILE = "unbound-experimental-options";
    public static final String QUIET_PHOTOS = "disable_eye_shutter_sound";
    public static final String SKIP_SCREEN_OFF_CONFIRMATION = "skip_screen_off_confirmation";

    private ExperimentalOptions() {}

    public static boolean enabled(Context context, String key) {
        try {
            byte[] value = file(context, key).readFully();
            return value.length == 1 && value[0] == 1;
        } catch (Exception absent) { return false; }
    }

    public static void save(Context context, String key, boolean enabled) throws Exception {
        AtomicFile file = file(context, key);
        FileOutputStream stream = file.startWrite();
        try { stream.write(enabled ? 1 : 0); file.finishWrite(stream); }
        catch (Exception failure) { file.failWrite(stream); throw failure; }
    }

    private static AtomicFile file(Context context, String key) {
        if (!QUIET_PHOTOS.equals(key) && !SKIP_SCREEN_OFF_CONFIRMATION.equals(key))
            throw new IllegalArgumentException("Unknown experimental option");
        return new AtomicFile(new File(context.getFilesDir(), FILE + "-" + key));
    }
}
