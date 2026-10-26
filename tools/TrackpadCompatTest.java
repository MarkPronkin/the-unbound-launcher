import dev.beampro.port.TrackpadCompat;
import java.util.*;

/** Exercise the actual Java callback adapter, including noisy contacts and gesture transitions. */
public class TrackpadCompatTest {
    public static final class Receiver {
        final List<Integer> actions = new ArrayList<>();
        int[] ids; float[] x, y;
        public void FillTouchpadInput(int action, int id, int[] ids, float[] x, float[] y) {
            actions.add(action); this.ids = ids; this.x = x; this.y = y;
        }
    }
    public static final class FailingReceiver {
        public void FillTouchpadInput(int action, int id, int[] ids, float[] x, float[] y) {
            throw new IllegalArgumentException("original failure");
        }
    }
    static void event(Object receiver, int action, float x, float y) {
        TrackpadCompat.forward(receiver, action, 7, new int[]{7}, new float[]{x}, new float[]{y});
    }
    static void check(Receiver receiver, Integer... expected) {
        if (!receiver.actions.equals(Arrays.asList(expected))) throw new AssertionError(receiver.actions);
    }
    public static void main(String[] args) {
        Receiver tap = new Receiver();
        event(tap, 0, 0, 0);
        for (float jitter : new float[]{0, .001f, -.012f, .024f}) event(tap, 2, jitter, jitter);
        event(tap, 1, .005f, .008f); check(tap, 0, 1);

        Receiver hold = new Receiver(); event(hold, 0, .2f, .3f);
        for (int i = 0; i < 200; i++) event(hold, 2, .201f, .298f);
        event(hold, 1, .2f, .3f); check(hold, 0, 1);

        Receiver drag = new Receiver(); event(drag, 0, 0, 0); event(drag, 2, .02f, 0);
        event(drag, 2, .04f, 0); event(drag, 2, 0, 0); event(drag, 1, 0, 0); check(drag, 0, 2, 2, 1);

        for (float edge : new float[]{-.92f, .92f}) {
            Receiver strip = new Receiver(); event(strip, 0, edge, 0); event(strip, 2, edge, .001f);
            event(strip, 1, edge, .001f); check(strip, 0, 2, 1);
        }

        Receiver multi = new Receiver(); event(multi, 0, 0, 0);
        int[] ids = {7, 9}; float[] x = {0, .2f}, y = {0, .2f};
        for (int action : new int[]{5, 2, 6}) TrackpadCompat.forward(multi, action, 9, ids, x, y);
        if (multi.ids != ids || multi.x != x || multi.y != y) throw new AssertionError("Arrays were changed");
        event(multi, 2, 0, 0); event(multi, 1, 0, 0); check(multi, 0, 5, 2, 6, 2, 1);

        Receiver canceled = new Receiver(); event(canceled, 0, 0, 0); event(canceled, 3, 0, 0);
        event(canceled, 2, 0, 0); event(canceled, 0, 0, 0); event(canceled, 2, 0, 0);
        event(canceled, 1, 0, 0); check(canceled, 0, 3, 2, 0, 1);

        Receiver previous = new Receiver(), next = new Receiver(); event(previous, 0, 0, 0);
        event(next, 2, 0, 0); check(next, 2);

        Receiver invalid = new Receiver(); event(invalid, 0, Float.NaN, 0);
        event(invalid, 2, 0, 0); check(invalid, 0, 2);
        TrackpadCompat.forward(invalid, 3, 7, null, null, null); check(invalid, 0, 2, 3);

        try {
            event(new FailingReceiver(), 0, 0, 0); throw new AssertionError("Original failure was swallowed");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().equals("original failure")) throw new AssertionError(expected);
        }
        System.out.println("PASS: noisy tap, noisy hold, drag, both scroll edges, multitouch, cancel, receiver change, invalid contact, original exception");
    }
}
