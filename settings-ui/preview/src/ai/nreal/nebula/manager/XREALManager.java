package ai.nreal.nebula.manager;

/** Emulator-only sleep SDK contract; no USB or device writes. */
public final class XREALManager {
    private static final XREALManager INSTANCE = new XREALManager();
    public static int sleepLevel = 2, sleepSeconds = 600, levelWrites, timeWrites, reads;
    public static boolean rejectWrites, throwWrites;
    public static XREALManager getInstance() { return INSTANCE; }
    public int getSleepLevel() { reads++; return sleepLevel; }
    public int getSleepTime() { reads++; return sleepSeconds; }
    public boolean setSleepLevel(int level) {
        levelWrites++;
        if (throwWrites) throw new IllegalStateException("Fixture SDK disconnected");
        if (rejectWrites) return false;
        sleepLevel = level; return true;
    }
    public boolean setSleepTime(int seconds) {
        timeWrites++;
        if (throwWrites) throw new IllegalStateException("Fixture SDK disconnected");
        if (rejectWrites) return false;
        sleepSeconds = seconds; return true;
    }
    public static void reset() {
        sleepLevel = 2; sleepSeconds = 600; levelWrites = timeWrites = reads = 0;
        rejectWrites = throwWrites = false;
    }
}
