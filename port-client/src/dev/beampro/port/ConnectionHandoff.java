package dev.beampro.port;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import java.util.UUID;
import java.util.WeakHashMap;

/** A single phone cover across the main/Unity processes, released by actual output readiness. */
public final class ConnectionHandoff {
    private static final String OWNER = "ai.nreal.nebula.space.LaunchSpaceAcrivity";
    private static final Uri BRIDGE = Uri.parse("content://dev.beampro.portprobe.bridge");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int ATTACHED = 1, READY = 2, FAILED = 3, CANCEL = 4, RELEASE = 5, VISIBILITY = 6, PREPARED = 7, STAGE = 8;
    private static boolean installed;
    private final Activity activity;
    private final ConnectionAnimation animation;
    private final String token = UUID.randomUUID().toString();
    private ResultReceiver controller;
    private boolean closed, ready, renderingReady;
    private boolean overlay;

    ConnectionHandoff(Activity activity, ConnectionAnimation animation) {
        this.activity = activity; this.animation = animation;
    }

    void start(Runnable onReady, Runnable onFailure) {
        if (animation != null) animation.starting();
        overlay = animation != null && animation.coverLaunch(activity);
        Bundle info = new Bundle();
        info.putString("token", token);
        info.putLong("deadline", SystemClock.uptimeMillis() + 45000);
        info.putBoolean("overlay", overlay);
        info.putParcelable("callback", remote(new ResultReceiver(MAIN) {
            @Override protected void onReceiveResult(int event, Bundle data) {
                if (event == ATTACHED) {
                    ResultReceiver peer = data == null ? null : data.getParcelable("controller");
                    if (closed) { if (peer != null) peer.send(CANCEL, null); return; }
                    controller = peer;
                }
                if (closed) return;
                if (event == PREPARED) renderingReady = true;
                if (event == READY && !ready) {
                    ready = true; renderingReady = true;
                    Log.i("UnboundConnect", "Controller and native glasses window have committed their first frames");
                    if (overlay && animation != null) animation.connected(() -> animation.reveal(onReady));
                    else onReady.run();
                } else if (event == FAILED) onFailure.run();
                else if (event == STAGE && animation != null && data != null) animation.stage(data.getString("stage"));
                else if (event == VISIBILITY && animation != null && data != null)
                    animation.setVisibility(data.getBoolean("visible") ? View.VISIBLE : View.INVISIBLE);
            }
        }));
        activity.getContentResolver().call(BRIDGE, "connection-handoff-put", null, info);
    }

    // Parcel the framework type, rather than our anonymous subclass, across process class loaders.
    private static ResultReceiver remote(ResultReceiver receiver) {
        Parcel parcel = Parcel.obtain();
        try {
            receiver.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }

    void close(boolean cancel) {
        if (closed) return;
        closed = true;
        if (controller != null) controller.send(cancel ? CANCEL : RELEASE, null);
        try { activity.getContentResolver().call(BRIDGE, "connection-handoff-clear", token, null); }
        catch (RuntimeException failure) { Log.w("UnboundConnect", "Cannot clear connection handoff", failure); }
    }

    boolean isReady() { return renderingReady; }

    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        ((Application)context.getApplicationContext()).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            final WeakHashMap<Activity, Controller> controllers = new WeakHashMap<>();
            public void onActivityCreated(Activity a, Bundle state) {}
            public void onActivityPostCreated(Activity a, Bundle state) {
                if (!a.getClass().getName().equals(OWNER)) return;
                try {
                    Bundle info = a.getContentResolver().call(BRIDGE, "connection-handoff-get", null, null);
                    if (info == null || info.getParcelable("callback") == null
                            || SystemClock.uptimeMillis() >= info.getLong("deadline")) return;
                    Controller controller = new Controller(a, info);
                    controllers.put(a, controller);
                    MAIN.post(controller);
                } catch (RuntimeException failure) { Log.w("UnboundConnect", "Cannot join connection handoff", failure); }
            }
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) { visibility(a, true); }
            public void onActivityPaused(Activity a) { visibility(a, false); }
            private void visibility(Activity a, boolean visible) {
                Controller c = controllers.get(a);
                if (c != null && !c.closed) {
                    Bundle data = new Bundle(); data.putBoolean("visible", visible); c.callback.send(VISIBILITY, data);
                }
            }
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle state) {}
            public void onActivityDestroyed(Activity a) {
                Controller c = controllers.remove(a);
                if (c != null) { if (!c.closed) c.callback.send(FAILED, null); c.close(); }
            }
        });
    }

    private static final class Controller implements Runnable {
        final Activity activity;
        final ResultReceiver callback;
        final long deadline;
        ConnectionAnimation cover;
        boolean closed, ready;
        DrawnView controls, output;
        String stage;

        Controller(Activity activity, Bundle info) {
            this.activity = activity; deadline = info.getLong("deadline"); callback = info.getParcelable("callback");
            activity.getWindow().setWindowAnimations(0);
            if (!info.getBoolean("overlay")) {
                cover = new ConnectionAnimation(activity, () -> {}, () -> {
                    callback.send(FAILED, null); activity.finish();
                });
                cover.starting();
                cover.coverController(activity);
            }
            Bundle data = new Bundle();
            data.putParcelable("controller", remote(new ResultReceiver(MAIN) {
                @Override protected void onReceiveResult(int event, Bundle ignored) {
                    if (event == CANCEL) { close(); activity.finish(); }
                    else if (event == RELEASE) close();
                }
            }));
            callback.send(ATTACHED, data);
        }

        void close() {
            if (closed) return;
            closed = true; MAIN.removeCallbacks(this);
            if (controls != null) controls.close();
            if (output != null) output.close();
            if (cover != null) {
                cover.dispose();
                if (cover.getParent() instanceof ViewGroup) ((ViewGroup)cover.getParent()).removeView(cover);
                cover = null;
            }
        }

        public void run() {
            if (closed || ready) return;
            MAIN.removeCallbacks(this);
            if (activity.isFinishing() || activity.isDestroyed() || SystemClock.uptimeMillis() >= deadline) {
                callback.send(FAILED, null); close(); activity.finish(); return;
            }
            View phone = controllerView(activity), glasses = glassesView();
            controls = observe(controls, phone);
            output = observe(output, glasses);
            if (controls != null && controls.ready() && output != null && output.ready()) {
                ready = true;
                callback.send(PREPARED, null);
                if (cover != null) cover.connected(() -> cover.reveal(() -> callback.send(READY, null)));
                else callback.send(READY, null);
                return;
            }
            String next = phone == null ? "Waiting for phone controls" : glasses == null ? "Waiting for glasses output"
                    : controls == null || !controls.ready() ? "Drawing your controller" : "Drawing AR Space";
            if (!next.equals(stage)) {
                stage = next; Log.i("UnboundConnect", next);
                Bundle data = new Bundle(); data.putString("stage", next); callback.send(STAGE, data);
            }
            MAIN.postDelayed(this, 100);
        }
        private DrawnView observe(DrawnView previous, View current) {
            if (previous != null && previous.owner.get() == current && (!previous.committedFrame || previous.drawn)) return previous;
            if (previous != null) previous.close();
            return current == null ? null : new DrawnView(current);
        }
    }

    static View controllerView(Activity activity) {
        // Shared IDs in Spatial Mouse and both trackpad orientations. Unity's empty host is insufficient.
        View content = activity.findViewById(0x7f09009e);
        return visible(content) && content.isLaidOut() && visible(content.findViewById(0x7f0900c7))
                && visible(content.findViewById(0x7f0900a0)) ? content : null;
    }

    static View glassesView() {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            Display display = root.getDisplay();
            if (!visible(root) || display == null || display.getState() != Display.STATE_ON
                    || !DisplayStartupCompat.xreal(display) || !(root.getLayoutParams() instanceof WindowManager.LayoutParams)) continue;
            int type = ((WindowManager.LayoutParams)root.getLayoutParams()).type;
            Display.Mode mode = display.getMode();
            if ((type == 2037 || type == WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION)
                    && PresentationReadiness.nativeOutput(mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate())) return root;
        }
        return null;
    }

    private static boolean visible(View view) {
        return view != null && view.isAttachedToWindow() && view.isShown() && view.getWindowVisibility() == View.VISIBLE
                && view.getWidth() > 0 && view.getHeight() > 0 && view.getAlpha() > 0;
    }

    /** Observe a completed Android draw, without depending on optional Unity pointer visuals.
     * Native SDK output may have no Java SurfaceView child at all. */
    private static final class DrawnView implements ViewTreeObserver.OnDrawListener {
        final java.lang.ref.WeakReference<View> owner;
        final ViewTreeObserver tree;
        final Runnable committed = () -> MAIN.post(this::commit);
        final boolean hardware;
        boolean drawn, closed, posted, committedFrame;
        DrawnView(View view) {
            owner = new java.lang.ref.WeakReference<>(view);
            tree = view.getViewTreeObserver(); hardware = view.isHardwareAccelerated();
            if (hardware) tree.registerFrameCommitCallback(committed);
            else tree.addOnDrawListener(this);
            view.invalidate();
        }
        public void onDraw() {
            View view = owner.get();
            if (!closed && !posted && view != null) { posted = true; view.postOnAnimation(this::commit); }
        }
        void commit() {
            if (closed) return;
            committedFrame = true;
            drawn = visible(owner.get());
        }
        boolean ready() { return drawn && visible(owner.get()); }
        void close() {
            if (closed) return;
            closed = true;
            if (tree.isAlive()) {
                if (hardware) tree.unregisterFrameCommitCallback(committed);
                else tree.removeOnDrawListener(this);
            }
        }
    }
}
