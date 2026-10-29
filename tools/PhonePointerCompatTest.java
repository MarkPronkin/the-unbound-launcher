package dev.beampro.port;

import java.util.concurrent.*;

public final class PhonePointerCompatTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static final class Player {
        final ExecutorService unity = Executors.newSingleThreadExecutor(r -> new Thread(r, "UnityMain"));
        final BlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
        boolean delayed;
        void queueGLThreadEvent(Runnable job) { if (delayed) pending.add(job); else unity.execute(job); }
        void drain() throws Exception { Runnable job; while ((job = pending.poll()) != null) unity.submit(job).get(1, TimeUnit.SECONDS); }
    }
    static class Activity { private final Player mUnityPlayer; Activity(Player player) { mUnityPlayer = player; } }
    static final class ChildActivity extends Activity { ChildActivity(Player player) { super(player); } }
    static final class Controls implements PhonePointerCompat.Controls {
        volatile int flags = 23, calls;
        public int setSuspended(boolean suspended) {
            check(Thread.currentThread().getName().equals("UnityMain"), "Pointer API must execute on Unity's thread");
            calls++; flags = suspended ? 0 : 23; return flags;
        }
    }
    public static void main(String[] args) throws Exception {
        Player player = new Player(); Controls controls = new Controls();
        try {
            PhonePointerCompat first = new PhonePointerCompat(new ChildActivity(player), controls, 1000);
            PhonePointerCompat second = new PhonePointerCompat(new Activity(player), controls, 1000);
            first.suspend(); check(controls.flags == 0, "Pause acknowledged before returning");
            boolean denied = false;
            try { second.suspend(); } catch (IllegalStateException expected) { denied = true; }
            check(denied, "A different session cannot replace the snapshot");
            second.restore(); check(controls.flags == 0, "A different session cannot restore the pointer");
            first.suspend(); first.restore(); check(controls.flags == 23, "Restore prior pointer state");
            second.suspend(); first.restore(); check(controls.flags == 0, "Old session cannot resume a newer screen-off session");
            second.restore();
            PhonePointerCompat delayed = new PhonePointerCompat(new Activity(player), controls, 20);
            int calls = controls.calls; player.delayed = true;
            boolean timedOut = false;
            try { delayed.suspend(); } catch (IllegalStateException expected) { timedOut = true; }
            check(timedOut, "Missing Unity acknowledgement fails closed");
            player.drain(); check(controls.calls == calls && controls.flags == 23, "Late pause cannot disable a restored phone");
            player.delayed = false; delayed.suspend(); player.delayed = true;
            try { delayed.restore(); } catch (IllegalStateException expected) {}
            player.drain(); check(controls.flags == 23, "Late restoration still runs when Unity recovers");
            System.out.println("PASS Unity-thread pointer acknowledgement, ownership, timeouts and late restoration");
        } finally { player.unity.shutdownNow(); }
    }
}
