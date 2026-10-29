import dev.beampro.bridge.TrackpadGesturePolicy;
import java.util.*;

public final class TrackpadGesturePolicyTest {
    static final class Fake implements TrackpadGesturePolicy.Settings, TrackpadGesturePolicy.Journal {
        final Map<String,String> values = new HashMap<>();
        Properties disk = new Properties();
        String fail;
        int writes;
        public String get(String key) { return values.get(key); }
        public void set(String key, String value) {
            if (key.equals(fail)) { fail = null; throw new IllegalStateException("refused"); }
            if (value == null) values.remove(key); else values.put(key, value);
            writes++;
        }
        public Properties load() { Properties result = new Properties(); result.putAll(disk); return result; }
        public void save(Properties state) { disk = new Properties(); disk.putAll(state); }
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Fake f = new Fake();
        f.values.put(TrackpadGesturePolicy.KEYS[0], "1");
        f.values.put(TrackpadGesturePolicy.KEYS[2], "0");
        Map<String,String> before = new HashMap<>(f.values);
        TrackpadGesturePolicy policy = new TrackpadGesturePolicy(f,f);
        policy.acquire();
        for (String key : TrackpadGesturePolicy.KEYS) check("0".equals(f.get(key)), "Every recognizer disabled");
        int writes = f.writes; policy.acquire(); check(writes == f.writes, "Renew does not replace snapshot");
        policy.close(); check(f.values.equals(before), "Exact preferences and absent defaults restored");
        check(f.disk.isEmpty(), "Completed restoration clears journal");
        policy.close(); check(f.values.equals(before), "Repeated close is harmless");

        policy.acquire();
        new TrackpadGesturePolicy(f,f); // Simulate loss of the in-memory owner.
        check(f.values.equals(before) && f.disk.isEmpty(), "Crash/reboot recovery restores durable snapshot");

        policy = new TrackpadGesturePolicy(f,f); policy.acquire();
        f.values.put(TrackpadGesturePolicy.KEYS[0], "2");
        policy.close(); check("2".equals(f.get(TrackpadGesturePolicy.KEYS[0])), "Later external choice survives");

        f = new Fake(); f.fail = TrackpadGesturePolicy.KEYS[2];
        policy = new TrackpadGesturePolicy(f,f);
        try { policy.acquire(); throw new AssertionError("Failed write was hidden"); }
        catch (IllegalStateException expected) { check(expected.getMessage().equals("refused"), "Original write failure"); }
        check(f.values.isEmpty() && f.disk.isEmpty(), "Partial acquisition rolls back every successful change");

        f = new Fake(); policy = new TrackpadGesturePolicy(f,f); policy.acquire();
        f.fail = TrackpadGesturePolicy.KEYS[1];
        try { policy.close(); throw new AssertionError("Failed restore was hidden"); }
        catch (IllegalStateException expected) {}
        check(f.disk.size() == 1, "Failed restoration stays journaled while other settings restore");
        new TrackpadGesturePolicy(f,f);
        check(f.values.isEmpty() && f.disk.isEmpty(), "Retry completes a failed restoration");
        System.out.println("PASS: five recognizer gates, absent/default settings, renew, restore, crash recovery, external changes, partial failure and recovery retry");
    }
}
