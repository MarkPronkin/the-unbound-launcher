package ai.nreal.nebula.space;

import android.app.Activity;
import android.app.Presentation;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import dev.beampro.port.ConnectionAnimation;
import dev.beampro.port.FixturePointer;

/** Real Android controller hierarchy and glasses surface, in a separate renderer process. */
public final class LaunchSpaceAcrivity extends Activity {
    private static final Uri STATE = Uri.parse("content://dev.beampro.connectiontest.state");
    public Player mUnityPlayer;
    final Handler main = new Handler();
    FrameLayout controls;
    ImageReader sink;
    VirtualDisplay display;
    Presentation presentation;
    boolean drawControls, drawOutput;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); mUnityPlayer = new Player();
        if (getIntent().getBooleanExtra("software", false)) getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        controls = new FrameLayout(this); controls.setId(0x7f09009e);
        TextView menu = new TextView(this); menu.setId(0x7f0900c7); menu.setText("MENU"); controls.addView(menu);
        TextView mode = new TextView(this); mode.setId(0x7f0900a0); mode.setText("Spatial Mouse");
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2); p.topMargin = 100; controls.addView(mode, p);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1)); setContentView(root);
        getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(() -> drawControls);
        sink = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 3);
        sink.setOnImageAvailableListener(reader -> { try (Image image = reader.acquireLatestImage()) {} }, main);
        display = getSystemService(DisplayManager.class).createVirtualDisplay("XREAL connection handoff fixture", 1920, 1080, 240,
                sink.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        main.post(tick);
    }
    final Runnable tick = new Runnable() {
        public void run() {
            Bundle state = getContentResolver().call(STATE, "get", null, null);
            if (state.getBoolean("finish")) { finish(); return; }
            controls.setVisibility(state.getBoolean("controls") ? View.VISIBLE : View.INVISIBLE);
            drawControls = state.getBoolean("drawControls"); drawOutput = state.getBoolean("drawOutput");
            if (drawControls) controls.invalidate();
            FixturePointer.configure(state.getBoolean("pointer") ? 31 : 0, state.getBoolean("anchor"), state.getBoolean("advancing"));
            if (state.getBoolean("surface") && presentation == null) {
                presentation = new Presentation(LaunchSpaceAcrivity.this, display.getDisplay());
                if (getIntent().getBooleanExtra("nativeHost", false)) {
                    TextView nativeHost = new TextView(presentation.getContext()); nativeHost.setText("Native renderer host");
                    presentation.setContentView(nativeHost);
                } else presentation.setContentView(new SurfaceView(presentation.getContext()));
                presentation.show();
                presentation.getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(() -> drawOutput);
            } else if (!state.getBoolean("surface") && presentation != null) { presentation.dismiss(); presentation = null; }
            if (drawOutput && presentation != null) presentation.getWindow().getDecorView().invalidate();
            Bundle report = new Bundle(); report.putInt("rendererPid", android.os.Process.myPid());
            report.putBoolean("coverShown", covered(getWindow().getDecorView()));
            report.putBoolean("controlsShown", controls.isShown()); report.putBoolean("surfaceShown", presentation != null && presentation.isShowing());
            report.putBoolean("hardwareControls", controls.isHardwareAccelerated());
            getContentResolver().call(STATE, "report", null, report);
            main.postDelayed(this, 50);
        }
    };
    private boolean covered(View view) {
        if (view instanceof ConnectionAnimation) return view.isShown();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++)
            if (covered(((ViewGroup)view).getChildAt(i))) return true;
        return false;
    }
    @Override public void onDestroy() {
        main.removeCallbacks(tick);
        if (presentation != null) presentation.dismiss();
        display.release(); sink.close(); mUnityPlayer.thread.quitSafely();
        Bundle report = new Bundle(); report.putBoolean("destroyed", true); getContentResolver().call(STATE, "report", null, report);
        super.onDestroy();
    }
    public static final class Player {
        final HandlerThread thread = new HandlerThread("UnityMain");
        final Handler jobs;
        Player() { thread.start(); jobs = new Handler(thread.getLooper()); }
        private void queueGLThreadEvent(Runnable job) { jobs.post(job); }
    }
}
