package dev.beampro.settings.preview;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import dev.beampro.settings.AppMenuActivity;
import dev.beampro.settings.LauncherAppIcon;
import java.io.InputStream;

/** Pixel checks exercise legacy/adaptive shapes and the packaged original artwork. */
public final class LauncherIconChecks extends Instrumentation {
    int passed;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        AppMenuActivity activity = null;
        try {
            activity = (AppMenuActivity)startActivitySync(new android.content.Intent(getTargetContext(), AppMenuActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            java.lang.reflect.Field loading = AppMenuActivity.class.getDeclaredField("loading"); loading.setAccessible(true);
            final AppMenuActivity screen = activity;
            for (int i = 0; i < 100; i++) {
                waitForIdleSync(); boolean[] busy = {true};
                runOnMainSync(() -> { try { busy[0] = loading.getBoolean(screen); } catch (Exception e) { throw new RuntimeException(e); } });
                if (!busy[0]) break;
                Thread.sleep(50);
            }
            require(!loading.getBoolean(screen), "catalog loaded");
            final Throwable[] failure = {null};
            runOnMainSync(() -> {
                try {
                    ColorDrawable legacy = new ColorDrawable(Color.BLUE); legacy.setBounds(7, 9, 27, 29);
                    Bitmap square = LauncherAppIcon.rasterize(legacy);
                    require(legacy.getBounds().equals(new Rect(7, 9, 27, 29)), "legacy drawable bounds retained");
                    for (int size : new int[]{34, 40, 102, 120}) circle(new LauncherAppIcon(screen, square), size, size, Color.BLUE);
                    circle(new LauncherAppIcon(screen, square), 80, 40, Color.BLUE);
                    Drawable mark = new Drawable() {
                        final Paint paint = new Paint();
                        public void draw(Canvas canvas) { paint.setColor(Color.RED); Rect b = getBounds();
                            float half = b.width() / 6f; canvas.drawRect(b.exactCenterX() - half, b.exactCenterY() - half,
                                    b.exactCenterX() + half, b.exactCenterY() + half, paint); }
                        public void setAlpha(int alpha) {} public void setColorFilter(ColorFilter filter) {}
                        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
                    };
                    Bitmap adaptive = LauncherAppIcon.rasterize(new AdaptiveIconDrawable(new ColorDrawable(Color.GREEN), mark));
                    Bitmap rendered = circle(new LauncherAppIcon(screen, adaptive), 120, 120, Color.RED);
                    require(rendered.getPixel(60, 5) == Color.GREEN, "adaptive background fills circle without phone mask");
                    require(rendered.getPixel(95, 60) == Color.GREEN && rendered.getPixel(85, 60) == Color.RED,
                            "adaptive foreground keeps Android safe-zone scale");
                    Bitmap transparent = LauncherAppIcon.rasterize(new ColorDrawable(Color.TRANSPARENT));
                    circle(new LauncherAppIcon(screen, transparent), 40, 40, Color.WHITE);
                    int[] fingerprints = new int[3]; int index = 0;
                    for (String id : new String[]{"TutorialWidgetContent", "LiveWindowWidgetContent", "PhotoWidgetContent"}) {
                        Bitmap original;
                        try (InputStream stream = screen.getAssets().open("launcher-icons/" + id + ".png")) { original = BitmapFactory.decodeStream(stream); }
                        require(original != null && original.getWidth() == 432 && original.getHeight() == 432, id + " full original artwork packaged");
                        View icon = (View)AppMenuChecks.invoke(screen, "appIcon", new Class[]{String.class}, id);
                        require(icon instanceof LauncherAppIcon, id + " uses circular editor icon");
                        Bitmap actual = circle(icon, 432, 432, original.getPixel(216, 216));
                        require(actual.getPixel(100, 216) == original.getPixel(100, 216)
                                && actual.getPixel(332, 216) == original.getPixel(332, 216), id + " original pixels retained");
                        fingerprints[index++] = original.getPixel(216, 216);
                    }
                    require(fingerprints[0] != fingerprints[1] && fingerprints[1] != fingerprints[2], "built-in artwork is distinct");
                } catch (Throwable error) { failure[0] = error; }
            });
            if (failure[0] != null) throw failure[0];
            result.putString("stream", "Launcher icon pixel checks passed: " + passed + "\n"); finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "Launcher icon check failed after " + passed + ": " + error + "\n"); finish(0, result);
        } finally { if (activity != null) { final AppMenuActivity screen = activity; runOnMainSync(screen::finish); } }
    }
    Bitmap circle(View icon, int width, int height, int center) {
        icon.layout(0, 0, width, height);
        Bitmap pixels = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); icon.draw(new Canvas(pixels));
        int cx = width / 2, cy = height / 2, radius = Math.min(width, height) / 2;
        require(pixels.getPixel(cx, cy) == center, "artwork center at " + width + "×" + height);
        require(Color.alpha(pixels.getPixel(cx - radius, cy - radius)) == 0
                && Color.alpha(pixels.getPixel(cx + radius - 1, cy + radius - 1)) == 0, "corners outside circular mask");
        require(Color.alpha(pixels.getPixel(cx, cy - radius + 1)) >= 250
                && Color.alpha(pixels.getPixel(cx - radius + 1, cy)) >= 250, "circle fills equal horizontal and vertical diameters");
        return pixels;
    }
    void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
}
