package dev.beampro.settings;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

/** Optional SBS startup for models whose original home configuration is mono. */
public final class ExperimentalStereo {
    private static final String FILE = "unbound-experimental-stereo";
    private ExperimentalStereo() {}

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), FILE));
    }
    public static boolean enabled(Context context) {
        try {
            byte[] value = file(context).readFully();
            // Retired 2D 60 Hz choices also retain the original model defaults.
            return value.length == 1 && value[0] == 1;
        } catch (Exception absent) { return false; }
    }
    public static void save(Context context, boolean enabled) throws Exception {
        // Unity's audited native reader uses this package-owned path. Refuse a
        // profile in which Java and the renderer would read different files.
        if (context.getPackageName().equals("dev.markpronkin.unbound")
                && !context.getFilesDir().getCanonicalFile().equals(
                        new File("/data/user/0/dev.markpronkin.unbound/files").getCanonicalFile()))
            throw new IllegalStateException("Display overrides are unavailable in this Android user profile.");
        AtomicFile file = file(context);
        FileOutputStream stream = file.startWrite();
        try { stream.write(enabled ? 1 : 0); file.finishWrite(stream); }
        catch (Exception failure) { file.failWrite(stream); throw failure; }
    }
    private static Object field(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
            catch (NoSuchFieldException absent) {}
        }
        throw new NoSuchFieldException(name);
    }
    /** The SDK has separate legacy EGlassMode and Ethernet EDID namespaces. */
    public static int selectConfig(int original, Object manager) {
        try {
            Context context = (Context) field(manager, "mContext");
            if (!enabled(context)) return original;
            boolean ethernet = Boolean.TRUE.equals(field(field(manager, "mNRServiceControl"), "isEthernetSeries"));
            boolean mono = ethernet ? original >= 1 && original <= 4
                    : original == 1 || original == 5 || original == 10 || original == 11;
            return mono ? (ethernet ? 5 : 3) : original;
        } catch (Exception failure) {
            Log.w("UnboundStereo", "Keeping original glasses configuration", failure);
            return original;
        }
    }
}
