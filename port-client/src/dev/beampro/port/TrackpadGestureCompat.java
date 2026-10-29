package dev.beampro.port;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.app.KeyguardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.PowerManager;
import android.view.Display;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lease the OEM three-finger recognizer only while either phone trackpad is visible. */
public final class TrackpadGestureCompat implements Application.ActivityLifecycleCallbacks {
    private static final String TOKEN = "dev.beampro.bridge.TRACKPAD_GESTURES";
    private static final int TRANSACTION = 0x00FFFFF1;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService REQUESTS = Executors.newSingleThreadExecutor();
    private static boolean installed;
    private final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();

    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        try { Class.forName("android.os.OplusGestureMonitorManager"); }
        catch (ClassNotFoundException otherPhone) { return; }
        ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(new TrackpadGestureCompat());
    }

    private static boolean trackpad(List<Fragment> fragments) {
        for (Fragment fragment : fragments) {
            if (fragment == null || !fragment.isAdded() || fragment.isHidden()) continue;
            String name = fragment.getClass().getName();
            if ((name.equals("ai.nreal.extension.virtualcontroller.TouchPadPortraitFragment")
                    || name.equals("ai.nreal.extension.virtualcontroller.TouchPadLandscapeFragment"))
                    && fragment.getView() != null && fragment.getView().isShown()) return true;
            if (trackpad(fragment.getChildFragmentManager().getFragments())) return true;
        }
        return false;
    }

    private static final class Session {
        final Activity activity;
        final IBinder owner = new Binder();
        final Runnable pulse = this::tick;
        boolean visible, wanted, busy;
        IBinder remote; // Worker thread only.
        long lastError;

        Session(Activity activity) { this.activity = activity; }
        void start() { visible = true; MAIN.removeCallbacks(pulse); tick(); }
        void stop() { visible = false; wanted = false; MAIN.removeCallbacks(pulse); submit(); }
        void tick() {
            PowerManager power = activity.getSystemService(PowerManager.class);
            KeyguardManager lock = activity.getSystemService(KeyguardManager.class);
            wanted = visible && !activity.isFinishing() && !activity.isDestroyed()
                    && power.isInteractive() && !lock.isKeyguardLocked()
                    && trackpad(activity.getFragmentManager().getFragments());
            submit();
            if (visible) MAIN.postDelayed(pulse, 1000);
        }
        void submit() {
            if (busy) return;
            busy = true;
            final boolean acquire = wanted;
            REQUESTS.execute(() -> {
                try {
                    if (acquire && (remote == null || !remote.isBinderAlive())) {
                        Bundle result = activity.getContentResolver().call(Uri.parse("content://dev.beampro.portprobe.bridge"), "get", null, null);
                        remote = result == null ? null : result.getBinder("binder");
                        if (remote == null) throw new IllegalStateException("ADB helper unavailable");
                    }
                    if (remote != null) {
                        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                        try {
                            data.writeInterfaceToken(TOKEN); data.writeInt(acquire ? 1 : 3); data.writeStrongBinder(owner);
                            if (!remote.transact(TRANSACTION, data, reply, 0)) throw new IllegalStateException("Restart the updated ADB helper");
                            reply.readException();
                            String error = reply.readString();
                            if (error != null) throw new IllegalStateException(error);
                        } finally { data.recycle(); reply.recycle(); }
                        if (!acquire) remote = null;
                    }
                } catch (Exception error) {
                    remote = null;
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastError > 30000 || lastError == 0) {
                        lastError = now;
                        android.util.Log.w("PortTrackpadGestures", "Three-finger protection unavailable", error);
                    }
                } finally {
                    MAIN.post(() -> { busy = false; if (wanted != acquire) submit(); });
                }
            });
        }
    }

    @Override public void onActivityStarted(Activity activity) {
        String name = activity.getClass().getName();
        if ((!name.equals("ai.nreal.nebula.space.LaunchSpaceAcrivity") && !name.equals("ai.nreal.activitylife.NRXRActivity"))
                || activity.getDisplay() == null || activity.getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) return;
        Session session = sessions.get(activity);
        if (session == null) { session = new Session(activity); sessions.put(activity, session); }
        session.start();
    }
    @Override public void onActivityStopped(Activity activity) { Session session = sessions.get(activity); if (session != null) session.stop(); }
    @Override public void onActivityDestroyed(Activity activity) { Session session = sessions.remove(activity); if (session != null) session.stop(); }
    @Override public void onActivityCreated(Activity activity, Bundle saved) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle saved) {}
}
