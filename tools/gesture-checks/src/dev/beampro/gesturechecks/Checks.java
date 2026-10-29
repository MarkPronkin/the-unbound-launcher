package dev.beampro.gesturechecks;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.view.*;
import dev.beampro.port.TrackpadGestureCompat;

/** Physical-phone fixture: native fragment lifecycle and real InputDispatcher multitouch. */
public final class Checks extends Instrumentation {
    private String phase;
    private int assertions;
    public static volatile int acquired, released;
    @Override public void onCreate(Bundle arguments) { phase = arguments.getString("phase", "protected"); start(); }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); assertions++; }
    private void waitFor(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 7000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.call()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Lifecycle request did not arrive");
    }
    @Override public void onStart() {
        ai.nreal.nebula.space.LaunchSpaceAcrivity activity = null;
        Bundle result = new Bundle();
        try {
            setInTouchMode(true);
            activity = (ai.nreal.nebula.space.LaunchSpaceAcrivity) startActivitySync(new Intent(getTargetContext(),
                    ai.nreal.nebula.space.LaunchSpaceAcrivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final ai.nreal.nebula.space.LaunchSpaceAcrivity host = activity;
            waitFor(() -> acquired > 0);
            waitForIdleSync(); SystemClock.sleep(400);
            swipe(550);
            if (phase.equals("protected")) { swipe(-550); hold(); }
            result.putInt("three_finger_moves", host.threeMoves);
            result.putInt("cancels", host.cancels);
            result.putInt("max_pointers", host.maxPointers);
            if (phase.equals("protected")) {
                check(host.maxPointers == 3, "Three pointers reach the activity");
                check(host.threeMoves >= 18, "Both swipe directions and hold reach the activity");
                check(host.cancels == 0, "System does not cancel multitouch");
            }
            int prior = released;
            final int beforeMode = prior;
            runOnMainSync(() -> host.show(new Fragment()));
            waitFor(() -> released > beforeMode);
            check(released > prior, "Leaving trackpad releases lease");
            int count = acquired;
            runOnMainSync(() -> host.show(new ai.nreal.extension.virtualcontroller.TouchPadLandscapeFragment()));
            waitFor(() -> acquired > count);
            check(acquired > count, "Landscape trackpad acquires lease");
            prior = released;
            final int stopped = prior;
            runOnMainSync(host::finish);
            waitFor(() -> released > stopped);
            check(released > stopped, "Activity stop restores gestures");
            result.putInt("assertions", assertions);
            result.putString("stream", "PASS " + phase + " trackpad gestures: " + result.toString() + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            if (activity != null) { final Activity host = activity; runOnMainSync(host::finish); }
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void swipe(float distance) {
        long down = SystemClock.uptimeMillis();
        event(down, MotionEvent.ACTION_DOWN, 1, 1300);
        event(down, MotionEvent.ACTION_POINTER_DOWN | (1 << 8), 2, 1300);
        event(down, MotionEvent.ACTION_POINTER_DOWN | (2 << 8), 3, 1300);
        for (int i = 1; i <= 14; i++) { SystemClock.sleep(18); event(down, MotionEvent.ACTION_MOVE, 3, 1300 + distance*i/14); }
        event(down, MotionEvent.ACTION_POINTER_UP | (2 << 8), 3, 1300 + distance);
        event(down, MotionEvent.ACTION_POINTER_UP | (1 << 8), 2, 1300 + distance);
        event(down, MotionEvent.ACTION_UP, 1, 1300 + distance);
        SystemClock.sleep(350);
    }
    private void hold() {
        long down = SystemClock.uptimeMillis();
        event(down, MotionEvent.ACTION_DOWN, 1, 1300);
        event(down, MotionEvent.ACTION_POINTER_DOWN | (1 << 8), 2, 1300);
        event(down, MotionEvent.ACTION_POINTER_DOWN | (2 << 8), 3, 1300);
        SystemClock.sleep(1100);
        event(down, MotionEvent.ACTION_MOVE, 3, 1300);
        event(down, MotionEvent.ACTION_POINTER_UP | (2 << 8), 3, 1300);
        event(down, MotionEvent.ACTION_POINTER_UP | (1 << 8), 2, 1300);
        event(down, MotionEvent.ACTION_UP, 1, 1300);
        SystemClock.sleep(300);
    }
    private void event(long down, int action, int count, float y) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
        for (int i=0; i<count; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords(); coordinates[i].x = 350 + i*200; coordinates[i].y = y;
            coordinates[i].pressure = 1; coordinates[i].size = .1f;
        }
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coordinates,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { check(getUiAutomation().injectInputEvent(event, true), "Touch injection accepted"); }
        finally { event.recycle(); }
    }
    public static final class App extends Application {
        @Override public void onCreate() { super.onCreate(); TrackpadGestureCompat.install(this); }
    }
    public static final class Bridge extends ContentProvider {
        final IBinder binder = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != 0x00FFFFF1) throw new AssertionError("Wrong transaction");
                data.enforceInterface("dev.beampro.bridge.TRACKPAD_GESTURES");
                int command = data.readInt();
                if (data.readStrongBinder() == null) throw new AssertionError("Missing lease owner");
                if (command == 1) acquired++; else if (command == 3) released++; else throw new AssertionError("Wrong lease command");
                reply.writeNoException(); reply.writeString(null); return true;
            }
        };
        public boolean onCreate() { return true; }
        @Override public Bundle call(String method,String arg,Bundle extras) {
            if (!"get".equals(method)) throw new AssertionError("Wrong provider operation");
            Bundle result = new Bundle(); result.putBinder("binder",binder); return result;
        }
        public Cursor query(Uri u,String[] p,String s,String[] a,String o) { throw new UnsupportedOperationException(); }
        public String getType(Uri u) { return null; }
        public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
        public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
        public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
    }
}
