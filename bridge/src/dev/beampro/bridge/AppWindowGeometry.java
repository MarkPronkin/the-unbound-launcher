package dev.beampro.bridge;

import android.graphics.Point;
import android.view.Display;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Translate logical rotation into the rotation of a managed app's output buffer. */
public final class AppWindowGeometry {
    private static final Map<Display,Integer> OFFSETS = new WeakHashMap<>();
    private static final Pattern ROTATION = Pattern.compile(
            ", rotation ([0-3]), type VIRTUAL(?:,|\\s)");

    private AppWindowGeometry() {}

    // ActivityContent uses this value both to undo rotation for surface sizing
    // and to notify Unity. Adjusting only the size would leave Unity's window
    // orientation inconsistent with the buffer and its input coordinates.
    public static int rotation(Display display) {
        int logical = display.getRotation();
        try {
            if (!display.getName().startsWith("com.xreal.virtualdisplay-")) return logical;
            Point size = new Point();
            display.getRealSize(size);
            Display.Mode mode = display.getMode();
            int width = mode.getPhysicalWidth(), height = mode.getPhysicalHeight();
            if (width <= 0 || height <= 0) return logical;
            Integer known;
            synchronized (OFFSETS) { known = OFFSETS.get(display); }
            if (known != null) {
                int buffer = (logical + known) & 3;
                return known >= 0 && matches(size, width, height, buffer) ? buffer : logical;
            }
            if (width == height) return logical;
            if (matches(size, width, height, logical)) return logical;
            // A resize can expose old mode information. Do not infer rotation
            // from an arbitrary size mismatch or silently guess its direction.
            if (!matches(size, height, width, logical)) return logical;
            int offset;
            synchronized (OFFSETS) {
                known = OFFSETS.get(display);
                if (known == null) {
                    known = readOffset(display);
                    OFFSETS.put(display, known);
                }
                offset = known;
            }
            int buffer = (logical + offset) & 3;
            return offset >= 0 && matches(size, width, height, buffer) ? buffer : logical;
        } catch (Exception unavailable) {
            // Ordinary devices retain the SDK's original rotation contract.
            return logical;
        }
    }

    private static boolean matches(Point size, int width, int height, int rotation) {
        return (rotation & 1) == 0 ? size.x == width && size.y == height
                : size.x == height && size.y == width;
    }

    private static int readOffset(Display display) {
        try {
            // The public Display API has logical rotation only. Device rotation
            // is separate (LogicalDisplay adds both for the compositor). Read
            // it only for an observed axis mismatch, once per Display object.
            String unique = (String) Display.class.getMethod("getUniqueId").invoke(display);
            ShellCommand.Result result = ShellCommand.run(1000, "dumpsys", "-t", "1", "display");
            int offset = result.succeeded() ? deviceRotation(result.output, unique) : -1;
            System.out.println("BEAM_APP_WINDOW_GEOMETRY display=" + display.getDisplayId()
                    + " deviceRotation=" + offset);
            return offset;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception unavailable) {
            return -1;
        }
    }

    static int deviceRotation(String dump, String unique) {
        if (unique == null || !unique.startsWith("virtual:")) return -1;
        Integer found = null;
        for (String line : dump.split("\\n")) {
            String header = line.trim();
            if (!header.startsWith("DisplayDeviceInfo{\"com.xreal.virtualdisplay-")
                    || !header.contains("uniqueId=\"" + unique + "\"")
                    || !header.contains("FLAG_ROTATES_WITH_CONTENT")
                    || !header.contains("owner com.android.shell (uid 2000)")) continue;
            Matcher rotation = ROTATION.matcher(header);
            if (!rotation.find() || found != null) return -1;
            found = Integer.parseInt(rotation.group(1));
        }
        return found == null ? -1 : found;
    }
}
