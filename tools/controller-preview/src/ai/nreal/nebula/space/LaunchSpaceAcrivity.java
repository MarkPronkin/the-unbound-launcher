package ai.nreal.nebula.space;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import dev.beampro.port.PhoneScreenCompat;

/** Emulator-only host: real APK resources and production client, with no AR renderer or power requests. */
public final class LaunchSpaceAcrivity extends Activity {
    public Context target;
    public View controller, legacyIme;
    public VirtualDisplay glasses;
    private SurfaceTexture texture;
    private Surface surface;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try { target = createPackageContext("dev.beampro.controllerresources", CONTEXT_INCLUDE_CODE | CONTEXT_IGNORE_SECURITY); }
        catch (Exception error) { throw new IllegalStateException(error); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        PhoneScreenCompat.install(this);
        texture = new SurfaceTexture(false);
        texture.setDefaultBufferSize(640, 360);
        surface = new Surface(texture);
        glasses = getSystemService(DisplayManager.class).createVirtualDisplay("Controller fixture glasses", 640, 360, 160,
                surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | (1 << 10)); // TRUSTED, as in the production shell helper.
        legacyIme = new View(this);
        WindowManager.LayoutParams ime = new WindowManager.LayoutParams(0, 0,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, android.graphics.PixelFormat.TRANSLUCENT);
        ime.gravity = Gravity.TOP | Gravity.LEFT;
        ime.setTitle("Controller fixture legacy touch-modal IME");
        getWindowManager().addView(legacyIme, ime);
        showMode("air_mouse_fragment");
    }

    public View inflate(String mode, int widthDp, int heightDp, float fontScale) {
        return inflate(mode, widthDp, heightDp, fontScale, 320);
    }

    private View inflate(String mode, int widthDp, int heightDp, float fontScale, int densityDpi) {
        Configuration config = new Configuration(target.getResources().getConfiguration());
        config.densityDpi = densityDpi;
        config.screenWidthDp = widthDp; config.screenHeightDp = heightDp;
        config.smallestScreenWidthDp = Math.min(widthDp, heightDp);
        config.orientation = widthDp > heightDp ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        config.fontScale = fontScale;
        Context themed = new ContextThemeWrapper(target.createConfigurationContext(config), android.R.style.Theme_Material_NoActionBar);
        int layout = themed.getResources().getIdentifier(mode, "layout", themed.getResources().getResourcePackageName(0x7f09009e));
        View root = LayoutInflater.from(this).cloneInContext(themed).inflate(layout, null, false);
        // Match fragment setup: the drawer cannot be opened with an edge swipe.
        try { root.getClass().getMethod("setDrawerLockMode", int.class).invoke(root, 1); }
        catch (Exception error) { throw new IllegalStateException(error); }
        return root;
    }

    public void showMode(String mode) {
        Configuration config = getResources().getConfiguration();
        controller = inflate(mode, config.screenWidthDp, config.screenHeightDp, config.fontScale, config.densityDpi);
        setContentView(controller);
    }

    public void disconnect() { if (glasses != null) { glasses.release(); glasses = null; } }

    @Override protected void onDestroy() {
        disconnect();
        if (legacyIme.getParent() != null) getWindowManager().removeView(legacyIme);
        surface.release(); texture.release();
        super.onDestroy();
    }
}
