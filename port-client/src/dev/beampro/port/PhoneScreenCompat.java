package dev.beampro.port;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Phone-only trackpad control; its window stays resumed while the physical panel is off. */
public final class PhoneScreenCompat {
    private static final String TOKEN = "dev.beampro.bridge.PHONE_SCREEN";
    private static final int TRANSACTION = 0x00FFFFF8;
    private static final int BUTTON_ID = 0x00bea010;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService REQUESTS = Executors.newSingleThreadExecutor();
    private static boolean installed;

    private PhoneScreenCompat() {}

    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        Application app = (Application) context.getApplicationContext();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
            public void onActivityCreated(Activity activity, Bundle saved) {}
            public void onActivityStarted(Activity activity) {}
            public void onActivityResumed(Activity activity) {
                String name = activity.getClass().getName();
                if (!name.equals("ai.nreal.nebula.space.LaunchSpaceAcrivity")
                        && !name.equals("ai.nreal.activitylife.NRXRActivity")) return;
                if (activity.getDisplay() == null || activity.getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) return;
                MAIN.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    Session session = sessions.get(activity);
                    if (session == null) { session = new Session(activity); sessions.put(activity, session); }
                    session.resumed = true;
                    try { session.attach(); }
                    catch (Exception error) { android.util.Log.w("PortPhoneScreen", "Screen-off button unavailable", error); }
                });
            }
            public void onActivityPaused(Activity activity) {
                Session session = sessions.get(activity);
                if (session != null) session.pause();
            }
            public void onActivityStopped(Activity activity) {}
            public void onActivitySaveInstanceState(Activity activity, Bundle saved) {}
            public void onActivityDestroyed(Activity activity) {
                Session session = sessions.remove(activity);
                if (session != null) session.close();
            }
        });
    }

    private static final class Session {
        final Activity activity;
        final Window window;
        final IBinder owner = new Binder();
        final WindowManager windows;
        final Shade shade;
        final View button;
        final DisplayManager displays;
        final DisplayManager.DisplayListener displayListener;
        final Runnable heartbeat = this::renew;
        AlertDialog explanation;
        IBinder remote;
        IBinder.DeathRecipient helperDeath;
        boolean blocked, starting, restoring, closed, resumed;
        int glasses = -1, blockedTouches;
        boolean hadKeepScreenOn;

        Session(Activity activity) {
            this.activity = activity;
            window = activity.getWindow();
            windows = activity.getWindowManager();
            displays = activity.getSystemService(DisplayManager.class);
            shade = new Shade();
            shade.setBackgroundColor(Color.BLACK);
            shade.setContentDescription("Phone screen off. Press either volume button to restore.");
            shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            button = new ScreenButton(activity);
            button.setId(BUTTON_ID);
            button.setContentDescription("Turn off phone screen");
            button.setTooltipText("Turn off phone screen");
            button.setFocusable(true);
            button.setOnClickListener(v -> explain());
            displayListener = new DisplayManager.DisplayListener() {
                public void onDisplayAdded(int id) { updateButton(); }
                public void onDisplayChanged(int id) { if (blocked && !glassesReady()) restore(); updateButton(); }
                public void onDisplayRemoved(int id) { if (id == glasses) restore(); updateButton(); }
            };
            displays.registerDisplayListener(displayListener, MAIN);
        }

        void attach() {
            if (closed) return;
            if (button.getParent() == null) {
                WindowManager.LayoutParams position = overlay(dp(48), dp(48),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
                position.gravity = Gravity.TOP | Gravity.RIGHT;
                position.x = dp(12); position.y = dp(12);
                position.setTitle("Unbound phone screen button");
                // A legacy zero-sized, touch-modal IME overlay sits above the Activity.
                // This small overlay must be above it to receive button taps.
                windows.addView(button, position);
            }
            updateButton();
        }

        WindowManager.LayoutParams overlay(int width, int height, int flags) {
            return new WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    flags, PixelFormat.TRANSLUCENT);
        }

        int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

        int externalDisplay() {
            for (Display display : displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
                if (display.getDisplayId() != Display.DEFAULT_DISPLAY && display.getState() == Display.STATE_ON)
                    return display.getDisplayId();
            }
            return -1;
        }

        boolean glassesReady() {
            Display display = displays.getDisplay(glasses);
            return display != null && display.getState() == Display.STATE_ON;
        }

        void updateButton() { button.setVisibility(!resumed || externalDisplay() < 0 ? View.GONE : View.VISIBLE); }

        void explain() {
            if (blocked || starting || closed) return;
            explanation = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert).setTitle("Turn off phone screen?")
                    .setMessage("AR Space will keep running in your glasses. Phone touches will be blocked.\n\n"
                            + "Press either volume button to turn the screen back on. Your phone remains unlocked.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Turn off screen", (dialog, which) -> MAIN.postDelayed(this::start, 200)).create();
            explanation.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            explanation.show();
            signature(explanation);
        }

        /** Mirrors dev.beampro.settings.Signature dialogs; this client is compiled without the settings UI. */
        void signature(AlertDialog dialog) {
            GradientDrawable sheet = new GradientDrawable();
            sheet.setColor(0xff1c1c1e); sheet.setCornerRadius(dp(20)); sheet.setStroke(dp(1), 0xff2c2c2e);
            dialog.getWindow().setBackgroundDrawable(new InsetDrawable(sheet, dp(16)));
            for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
                android.widget.Button button = dialog.getButton(which);
                if (button == null) continue;
                button.setAllCaps(false); button.setTextSize(15);
                button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                button.setTextColor(which == AlertDialog.BUTTON_POSITIVE ? 0xfff5f5f7 : 0xffa1a1a6);
            }
        }

        void start() {
            if (blocked || starting || closed || !resumed) return;
            glasses = externalDisplay();
            if (glasses < 0 || activity.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                toast("Enter AR Space before turning off the phone screen."); return;
            }
            try { block(); }
            catch (Exception error) { toast("Could not block phone touches. Check Display over other apps."); return; }
            starting = true;
            REQUESTS.execute(() -> {
                Exception failure = null;
                try {
                    Bundle result = activity.getContentResolver().call(Uri.parse("content://dev.beampro.portprobe.bridge"), "get", null, null);
                    remote = result == null ? null : result.getBinder("binder");
                    if (remote == null || !remote.isBinderAlive()) throw new IllegalStateException("Start the ADB helper first.");
                    helperDeath = () -> MAIN.post(() -> { unblock(); toast("The helper stopped. The phone screen is being restored."); });
                    remote.linkToDeath(helperDeath, 0);
                    request(1);
                } catch (Exception error) { failure = error; }
                final Exception error = failure;
                MAIN.post(() -> {
                    starting = false;
                    if (error != null) {
                        restore();
                        toast("Could not turn off the phone screen. " + error.getMessage());
                    } else if (closed || restoring || !resumed || !glassesReady()) restore();
                    else { MAIN.postDelayed(heartbeat, 2000); android.util.Log.i("PortPhoneScreen", "Phone panel off; touches blocked"); }
                });
            });
        }

        void block() {
            blockedTouches = 0;
            hadKeepScreenOn = (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
            shade.focusedOnce = false;
            shade.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            shade.setFocusableInTouchMode(true);
            WindowManager.LayoutParams position = overlay(-1, -1, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            position.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            position.setTitle("Unbound phone touch guard");
            windows.addView(shade, position);
            blocked = true;
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            shade.requestFocus();
        }

        void renew() {
            if (!blocked || restoring || closed) return;
            if (!resumed || !glassesReady() || activity.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                restore(); return;
            }
            REQUESTS.execute(() -> {
                Exception failure = null;
                try { request(2); } catch (Exception error) { failure = error; }
                final Exception error = failure;
                MAIN.post(() -> {
                    if (error != null) { restore(); toast("The phone screen was restored because the helper stopped responding."); }
                    else if (blocked && !restoring && !closed) MAIN.postDelayed(heartbeat, 2000);
                });
            });
        }

        void restore() {
            MAIN.removeCallbacks(heartbeat);
            if (!blocked || restoring) return;
            restoring = true;
            REQUESTS.execute(() -> {
                try { if (remote != null && remote.isBinderAlive()) request(3); }
                catch (Exception error) { android.util.Log.w("PortPhoneScreen", "Restoring via watchdog", error); }
                finally {
                    if (remote != null && helperDeath != null) remote.unlinkToDeath(helperDeath, 0);
                    remote = null; helperDeath = null;
                    MAIN.post(() -> { unblock(); restoring = false; });
                }
            });
        }

        void unblock() {
            MAIN.removeCallbacks(heartbeat);
            if (!blocked) return;
            blocked = false;
            if (shade.getParent() != null) windows.removeView(shade);
            if (!hadKeepScreenOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            android.util.Log.i("PortPhoneScreen", "Phone screen and touch input restored; blockedTouchEvents=" + blockedTouches);
        }

        void request(int command) throws Exception {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(TOKEN); data.writeInt(command); data.writeStrongBinder(owner); data.writeInt(glasses);
                if (!remote.transact(TRANSACTION, data, reply, 0)) throw new IllegalStateException("Restart the updated ADB helper.");
                reply.readException();
                String error = reply.readString();
                if (error != null) throw new IllegalStateException(error);
            } finally { data.recycle(); reply.recycle(); }
        }

        void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }

        void pause() {
            resumed = false; restore();
            if (explanation != null) explanation.dismiss();
            if (button.getParent() != null) windows.removeView(button);
        }

        void close() {
            closed = true; pause(); displays.unregisterDisplayListener(displayListener);
        }

        final class Shade extends View {
            boolean focusedOnce;
            Shade() { super(activity); }
            @Override public boolean dispatchTouchEvent(MotionEvent event) { blockedTouches++; return true; }
            @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { blockedTouches++; return true; }
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) restore();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
            @Override public void onWindowFocusChanged(boolean focused) {
                super.onWindowFocusChanged(focused);
                if (focused) focusedOnce = true;
                else if (focusedOnce && blocked) restore();
            }
        }
    }

    private static final class ScreenButton extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ScreenButton(Context context) { super(context); setClickable(true); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save(); canvas.scale(getWidth() / 48f, getHeight() / 48f);
            paint.setStyle(Paint.Style.FILL); paint.setColor(isPressed() ? 0xff343434 : 0xff202020);
            canvas.drawCircle(24, 24, 23, paint);
            paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawRoundRect(17, 12, 31, 36, 2, 2, paint);
            canvas.drawLine(22, 32, 26, 32, paint);
            paint.setColor(0xff202020); paint.setStrokeWidth(6); canvas.drawLine(12, 12, 36, 36, paint);
            paint.setColor(Color.WHITE); paint.setStrokeWidth(1.8f); canvas.drawLine(12, 12, 36, 36, paint);
            canvas.restore();
        }
    }
}
