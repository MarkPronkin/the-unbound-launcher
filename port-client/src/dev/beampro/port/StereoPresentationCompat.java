package dev.beampro.port;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.WeakHashMap;

/** Apply stereo mode and edge-to-edge layout to the SDK's actual Presentation window. */
public final class StereoPresentationCompat {
    private static final String TAG = "UnboundStereo";
    private static final int TYPE_PRESENTATION = 2037; // Framework Presentation's window type.
    private static final WeakHashMap<View, Boolean> watched = new WeakHashMap<>();
    private static final WeakHashMap<View, WeakReference<Session>> roots = new WeakHashMap<>();
    private static Tracker tracker;

    /** Native SDK Presentations can create their own views instead of attaching the Java SDK views. */
    public static synchronized void track(Activity owner) {
        if(owner==null)return;
        try { Class.forName("dev.beampro.settings.RendererDiagnostics").getMethod("track", Activity.class).invoke(null, owner); }
        catch (ReflectiveOperationException unavailable) { Log.w(TAG, "Optional renderer diagnostics unavailable", unavailable); }
        if(tracker!=null&&tracker.owner.get()==owner)return;
        if(tracker!=null){tracker.handler.removeCallbacks(tracker);
            ArBackdropActivity.releaseMissing(tracker.owner.get(),java.util.Collections.emptySet());}
        tracker=new Tracker(owner);
        tracker.handler.postDelayed(tracker,0);
    }

    private static final class Tracker implements Runnable {
        final WeakReference<Activity> owner;
        final Handler handler=new Handler(Looper.getMainLooper());
        Tracker(Activity activity){owner=new WeakReference<>(activity);}
        public void run(){
            Activity activity=owner.get();
            if(activity==null||activity.isFinishing()||activity.isDestroyed()) {
                ArBackdropActivity.releaseMissing(activity,java.util.Collections.emptySet());
                if(tracker==this)tracker=null;
                return;
            }
            if(Build.VERSION.SDK_INT>=37) PresentationWindowDiagnostics.scan(activity);
            HashSet<Integer> live=new HashSet<>();
            for(View root:WindowInspector.getGlobalWindowViews()) {
                if(!root.isAttachedToWindow()||!DisplayStartupCompat.xreal(root.getDisplay())
                        ||(!DisplayStartupCompat.stereoEdid(root.getDisplay())&&!PresentationOnlyCompat.directDisplay(root.getDisplay()))
                        ||!(root.getLayoutParams() instanceof WindowManager.LayoutParams))continue;
                int type=((WindowManager.LayoutParams)root.getLayoutParams()).type;
                if(type==TYPE_PRESENTATION||type==WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION){
                    watchView(root);
                    if(Build.VERSION.SDK_INT<37&&!PresentationOnlyCompat.isActive(activity)){live.add(root.getDisplay().getDisplayId());ArBackdropActivity.ensure(activity,root);}}
            }
            ArBackdropActivity.releaseMissing(activity,live);
            handler.postDelayed(this,500);
        }
    }

    /** Called after construction of the SDK's two own views, before native code attaches them. */
    public static synchronized void watchView(View view) {
        if (view == null) return;
        if (watched.containsKey(view)) {
            // The native renderer can reapply its non-focusable flags after
            // attachment. Reconcile those writes without repeatedly relaying out
            // a healthy window or changing any unrelated overlay.
            if(Build.VERSION.SDK_INT>=37&&view.isAttachedToWindow()) {
                View root=view.getRootView();
                if(root.getLayoutParams() instanceof WindowManager.LayoutParams) {
                    int flags=((WindowManager.LayoutParams)root.getLayoutParams()).flags;
                    WeakReference<Session> reference=roots.get(root);
                    Session session=reference==null?null:reference.get();
                    if(session!=null&&((flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0
                            ||(flags&WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)==0))session.schedule();
                }
            }
            return;
        }
        watched.put(view, true);
        Attachment attachment = new Attachment();
        view.addOnAttachStateChangeListener(attachment);
        if (view.isAttachedToWindow()) attachment.onViewAttachedToWindow(view);
    }

    private static final class Attachment implements View.OnAttachStateChangeListener {
        Session session;
        @Override public void onViewAttachedToWindow(View view) {
            if (session != null) return;
            View root = view.getRootView();
            if (!DisplayStartupCompat.xreal(root.getDisplay())) return;
            if(Build.VERSION.SDK_INT>=37) PresentationWindowDiagnostics.capture(root,"before session");
            WeakReference<Session> reference = roots.get(root);
            session = reference == null ? null : reference.get();
            if (session == null) {
                session = new Session(root);
                roots.put(root, new WeakReference<>(session));
            }
            session.views.put(view, true);
            session.schedule();
        }
        @Override public void onViewDetachedFromWindow(View view) {
            if (session == null) return;
            session.views.remove(view);
            if (session.views.isEmpty()) session.close();
            session = null;
        }
    }

    private static final class Session implements Runnable, DisplayManager.DisplayListener,
            ViewTreeObserver.OnGlobalLayoutListener {
        final View root;
        final DisplayManager displays;
        final WindowManager manager;
        final WeakHashMap<View, Boolean> views = new WeakHashMap<>();
        final ViewTreeObserver observer;
        int ownedMode, previousMode;
        boolean posted, closed, registered;

        Session(View root) {
            this.root = root;
            // SDK children may retain the PHONE Activity context after reparenting.
            // Only the Presentation decor's context owns the external window manager.
            displays = root.getContext().getSystemService(DisplayManager.class);
            manager = root.getContext().getSystemService(WindowManager.class);
            observer = root.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(this);
        }
        void schedule() {
            if (closed || posted) return;
            posted = true;
            root.post(this);
        }
        @Override public void run() {
            posted = false;
            if (closed || !root.isAttachedToWindow()) return;
            Display display = root.getDisplay();
            if (!DisplayStartupCompat.xreal(display)) { releaseMode(); return; }
            if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams)) return;
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) root.getLayoutParams();
            if (attrs.type != TYPE_PRESENTATION
                    && attrs.type != WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION) return;
            int mode = DisplayStartupCompat.stereoEdid(display) ? DisplayStartupCompat.stereoMode(display) : 0;
            boolean direct=PresentationOnlyCompat.directDisplay(display);
            if(direct) {
                Display.Mode current=display.getMode();
                if(current.getPhysicalHeight()==1080&&(current.getPhysicalWidth()==1920||current.getPhysicalWidth()==3840))mode=current.getModeId();
                else mode=0;
            }
            if (mode == 0) { releaseMode(); return; }
            if (!registered) {
                displays.registerDisplayListener(this, new Handler(Looper.getMainLooper()));
                registered = true;
            }
            boolean changed = false;
            if (ownedMode != 0 && attrs.preferredDisplayModeId != ownedMode) ownedMode = 0;
            if (attrs.preferredDisplayModeId == 0 || attrs.preferredDisplayModeId == ownedMode) {
                if (ownedMode == 0) previousMode = attrs.preferredDisplayModeId;
                ownedMode = mode;
                if (attrs.preferredDisplayModeId != mode) { attrs.preferredDisplayModeId = mode; changed = true; }
            }
            int flags = (attrs.flags | WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                    & ~WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN;
            if(Build.VERSION.SDK_INT>=37) {
                // Android 17 PresentationController occludes every Activity on
                // the display, so an immersive backdrop cannot run. The public
                // Presentation must own bar control itself. Touch stays with the
                // phone controller; ALT_FOCUSABLE_IM excludes this renderer from
                // the IME target used by apps on their managed virtual displays.
                flags=(flags|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                        &~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            }
            if (attrs.flags != flags) { attrs.flags = flags; changed = true; }
            if (attrs.width != ViewGroup.LayoutParams.MATCH_PARENT || attrs.height != ViewGroup.LayoutParams.MATCH_PARENT
                    || attrs.x != 0 || attrs.y != 0 || attrs.gravity != (Gravity.TOP | Gravity.LEFT)) {
                attrs.width = attrs.height = ViewGroup.LayoutParams.MATCH_PARENT;
                attrs.x = attrs.y = 0;
                attrs.gravity = Gravity.TOP | Gravity.LEFT;
                changed = true;
            }
            if (Build.VERSION.SDK_INT >= 30 && attrs.getFitInsetsTypes() != 0) {
                attrs.setFitInsetsTypes(0);
                changed = true;
            }
            try {
                if(Build.VERSION.SDK_INT>=37) PresentationWindowDiagnostics.capture(root,"before layout request");
                if (changed) manager.updateViewLayout(root, attrs);
                int ui = root.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                if (root.getSystemUiVisibility() != ui) root.setSystemUiVisibility(ui);
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController controller = root.getWindowInsetsController();
                    if (controller != null) {
                        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                        controller.hide(WindowInsets.Type.systemBars());
                    }
                }
                for (View view : new ArrayList<>(views.keySet())) {
                    ViewGroup.LayoutParams child = view.getLayoutParams();
                    if (view != root && child != null
                            && (child.width != ViewGroup.LayoutParams.MATCH_PARENT || child.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
                        child.width = child.height = ViewGroup.LayoutParams.MATCH_PARENT;
                        view.setLayoutParams(child);
                    }
                }
                if(Build.VERSION.SDK_INT>=37) PresentationWindowDiagnostics.capture(root,"after layout request");
                if (changed) Log.i(TAG, "SDK Presentation display=" + display.getDisplayId()
                        + " requests mode=" + attrs.preferredDisplayModeId + (direct?" (direct Presentation)":" (3840x1080@60)")+", full display without insets"
                        + "; root=" + root.getWidth() + "x" + root.getHeight());
            } catch (IllegalArgumentException failure) {
                if(Build.VERSION.SDK_INT>=37) PresentationWindowDiagnostics.capture(root,"layout request failed");
                Log.w(TAG, "Presentation was removed during layout update", failure);
            }
        }
        void releaseMode() {
            if (ownedMode == 0 || !(root.getLayoutParams() instanceof WindowManager.LayoutParams)) return;
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) root.getLayoutParams();
            if (attrs.preferredDisplayModeId == ownedMode) {
                attrs.preferredDisplayModeId = previousMode;
                try { manager.updateViewLayout(root, attrs); } catch (IllegalArgumentException ignored) {}
            }
            ownedMode = 0;
        }
        void close() {
            if (closed) return;
            closed = true;
            root.removeCallbacks(this);
            if (registered) displays.unregisterDisplayListener(this);
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            releaseMode();
            roots.remove(root);
        }
        @Override public void onGlobalLayout() { schedule(); }
        @Override public void onDisplayAdded(int id) { schedule(); }
        @Override public void onDisplayChanged(int id) { schedule(); }
        @Override public void onDisplayRemoved(int id) { schedule(); }
    }
}
