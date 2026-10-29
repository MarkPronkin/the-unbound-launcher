import android.app.Application;
import android.os.Build;
import dev.beampro.port.TrackpadGestureCompat;

/** Run the actual app/helper capability checks with isolated OEM framework fixtures. */
public final class TrackpadGestureCapabilityTest {
    public static void main(String[] args) throws Exception {
        String maker = args[0];
        boolean expected = Boolean.parseBoolean(args[1]);
        // Runtime fixtures supply writable fields; the production sources remain unmodified.
        Build.class.getField("MANUFACTURER").set(null, maker);
        Build.class.getField("BRAND").set(null, maker);
        Class<?> helper = Class.forName("dev.beampro.bridge.TrackpadGestures");
        java.lang.reflect.Method supported = helper.getDeclaredMethod("supported");
        supported.setAccessible(true);
        if (((Boolean) supported.invoke(null)) != expected)
            throw new AssertionError("Helper detection differs for " + maker);
        Application app = new Application();
        TrackpadGestureCompat.install(app);
        TrackpadGestureCompat.install(app);
        int registrations = app.getClass().getField("registrations").getInt(app);
        if (registrations != (expected ? 1 : 0))
            throw new AssertionError("Client detection differs for " + maker + ": " + registrations);
        System.out.println("PASS " + maker + ": app and helper supported=" + expected);
    }
}
