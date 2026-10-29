package dev.beampro.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** Keeps an offline choice until the glasses SDK accepts it. All access is on the UI thread. */
final class SleepTimer {
    static final String PREFERENCES = "unbound_sleep_timer";
    // The original SleepTimeBean / SDK ordering, including its "never" sentinel.
    private static final int[] SECONDS = {30, 300, 600, 1200, 3600, 9000};
    private static final String[] LABELS = {"30 seconds", "5 minutes", "10 minutes", "20 minutes", "1 hour", "Never"};
    private static SleepTimer instance;
    private final SharedPreferences preferences;
    private final List<Runnable> observers = new ArrayList<>();
    private boolean connected, unsupported, unavailable;

    static SleepTimer get(Context context) {
        if (instance == null) instance = new SleepTimer(context.getApplicationContext());
        return instance;
    }

    SleepTimer(Context context) { preferences = context.getSharedPreferences(PREFERENCES, 0); }
    static int count() { return SECONDS.length; }
    static String label(int index) { return LABELS[index]; }
    int selected() {
        int index = preferences.getInt("choice", 2);
        return index >= 0 && index < count() ? index : 2;
    }
    boolean pending() { return preferences.getBoolean("pending", false); }
    String summary() { return label(selected()); }

    String status() {
        if (!connected) return pending()
                ? "Saved on this phone. Applies when your glasses connect."
                : "You can change this without your glasses connected.";
        if (unsupported) return "These glasses do not support automatic sleep. Your choice is saved for compatible glasses.";
        if (pending()) return "Saved on this phone. Could not apply yet; reconnect your glasses to try again.";
        if (unavailable) return "Could not read the glasses setting. You can still save a sleep time.";
        return "Changes apply to your connected glasses.";
    }

    void observe(Runnable observer) { if (!observers.contains(observer)) observers.add(observer); }
    void remove(Runnable observer) { observers.remove(observer); }
    private void changed() { for (Runnable observer : new ArrayList<>(observers)) observer.run(); }

    void select(int index) {
        if (index < 0 || index >= count()) throw new IllegalArgumentException("Invalid sleep time");
        // Persist before touching hardware so a disconnect cannot lose the choice.
        if (!preferences.edit().putInt("choice", index).putBoolean("pending", true).commit()) {
            throw new IllegalStateException("Could not save the sleep time. Try again.");
        }
        refresh(connected, true);
        changed();
    }

    void refresh(boolean connected, boolean retryPending) {
        int before = selected();
        String previousStatus = status();
        boolean justConnected = connected && !this.connected;
        this.connected = connected;
        unsupported = false;
        unavailable = false;
        if (connected) {
            try {
                Class<?> models = Class.forName("com.xreal.glassesdisplayplugevent.GlassesInitSetting");
                Object model = models.getMethod("getInstance").invoke(null);
                unsupported = (Boolean) models.getMethod("isHSeriesGlasses10_11").invoke(model);
                if (!unsupported) {
                    boolean levels = (Boolean) models.getMethod("isGSeriesGlasses").invoke(model);
                    Class<?> sdk = Class.forName("ai.nreal.nebula.manager.XREALManager");
                    Object manager = sdk.getMethod("getInstance").invoke(null);
                    if (pending()) {
                        if (justConnected || retryPending) {
                            boolean applied = (Boolean) sdk.getMethod(levels ? "setSleepLevel" : "setSleepTime", int.class)
                                    .invoke(manager, levels ? selected() : SECONDS[selected()]);
                            if (applied) preferences.edit().putBoolean("pending", false).apply();
                        }
                    } else {
                        int value = (Integer) sdk.getMethod(levels ? "getSleepLevel" : "getSleepTime").invoke(manager);
                        int index = levels ? (value >= 0 && value < count() ? value : -1) : indexForSeconds(value);
                        unavailable = index < 0;
                        if (index >= 0 && index != selected()) preferences.edit().putInt("choice", index).apply();
                    }
                }
            } catch (Exception failure) {
                unavailable = true;
                Log.w("UnboundSleep", "Sleep setting is saved locally; glasses SDK unavailable", failure);
            }
        }
        if (before != selected() || !previousStatus.equals(status())) changed();
    }

    private static int indexForSeconds(int seconds) {
        if (seconds >= 9000) return 5;
        for (int i = 0; i < count(); i++) if (SECONDS[i] == seconds) return i;
        return -1;
    }
}
