package dev.beampro.bridge;

import java.util.Properties;

/** Reversible OxygenOS/ColorOS three-finger recognizer settings. */
public final class TrackpadGesturePolicy implements AutoCloseable {
    // All five can keep OplusGestureObserver's three-finger monitor registered.
    // Partial screenshots and touchpad screenshots default to ON even without a row.
    public static final String[] KEYS = {
        "system:oplus_customize_smart_apperceive_screen_capture",
        "system:oplus_customize_screenshot_enable_area_screenshot",
        "secure:double_finger_split_screen_enable",
        "secure:three_finger_flash_memory_enable",
        "global:touchpad_gesture_threefinger_fling_to_screenshot"
    };
    public interface Settings {
        String get(String key) throws Exception;
        void set(String key, String value) throws Exception;
    }
    public interface Journal {
        Properties load() throws Exception;
        void save(Properties state) throws Exception;
    }
    private final Settings settings;
    private final Journal journal;
    private final Properties previous;

    public TrackpadGesturePolicy(Settings settings, Journal journal) throws Exception {
        this.settings = settings; this.journal = journal;
        previous = journal.load();
        // Recover an interrupted session before taking another snapshot.
        close();
    }

    public void acquire() throws Exception {
        if (!previous.isEmpty()) return;
        try {
            for (String key : KEYS) {
                String value = settings.get(key);
                if ("0".equals(value)) continue;
                previous.setProperty(key, value == null ? "absent" : value);
                journal.save(previous); // Durable before each potentially successful write.
                settings.set(key, "0");
                if (!"0".equals(settings.get(key))) throw new IllegalStateException("Gesture setting refused: " + key);
            }
        } catch (Exception failure) {
            try { close(); } catch (Exception recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
    }

    @Override public void close() throws Exception {
        Exception failure = null;
        for (String key : KEYS) {
            String value = previous.getProperty(key);
            if (value == null) continue;
            try {
                // Keep a later external preference change. Only restore values we still own.
                if ("0".equals(settings.get(key))) {
                    String restored = value.equals("absent") ? null : value;
                    settings.set(key, restored);
                    if (!java.util.Objects.equals(restored, settings.get(key)))
                        throw new IllegalStateException("Gesture setting did not restore: " + key);
                }
                previous.remove(key);
                journal.save(previous);
            } catch (Exception error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
