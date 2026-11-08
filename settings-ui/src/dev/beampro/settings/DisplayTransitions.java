package dev.beampro.settings;

/** Attempt-scoped, passive display observations, independent of logcat and slow helper dumps. */
final class DisplayTransitions {
    static final long WINDOW_MS = 90_000;
    interface Sink {
        void event(String attempt, String detail);
        void capture(String attempt, String reason);
    }
    private final Sink sink;
    private String attempt = "", previous;
    private long deadline;
    private boolean fallbackCaptured, nativeSeen;

    DisplayTransitions(Sink sink) { this.sink = sink; }

    synchronized void begin(String id, long now) {
        // SDK retries within the same attempt must not reset the capture limit.
        if (id.equals(attempt) && deadline != 0) return;
        attempt = id; previous = null; fallbackCaptured = nativeSeen = false;
        deadline = now + WINDOW_MS;
    }

    synchronized void stop() { deadline = 0; attempt = ""; previous = null; }
    synchronized boolean active(long now) { return deadline != 0 && now < deadline; }

    void sample(long wall, long now, String inventory, boolean physical, boolean nativeOutput, boolean failure) {
        String detail = "wallMs=" + wall + "; uptimeMs=" + now + "; displays=[" + inventory + "]";
        String id, capture = null; boolean changed;
        synchronized (this) {
            if (!active(now)) return;
            id = attempt;
            changed = !inventory.equals(previous);
            previous = inventory;
            if (nativeOutput) nativeSeen = true;
            if (physical && !nativeOutput && !fallbackCaptured) {
                fallbackCaptured = true;
                capture = "First non-native physical output; nativeOutputPreviouslySeen=" + nativeSeen + "; " + detail;
            } else if (failure) capture = "Connection failure; " + detail;
        }
        // The sink can write files or acquire the collector lock; never invoke it under the state lock.
        if (changed) sink.event(id, detail);
        if (capture != null) sink.capture(id, capture);
    }
}
