package dev.beampro.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

/** One circular mask for both editor sizes, independent of the phone's icon shape. */
public final class LauncherAppIcon extends View {
    private static final int SIZE = 192;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final BitmapShader shader;
    private final Matrix matrix = new Matrix();
    private final Bitmap bitmap;

    public LauncherAppIcon(Context context, Bitmap bitmap) {
        super(context);
        this.bitmap = bitmap;
        shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        paint.setShader(shader);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Keep adaptive layers at Android's normal scale, but apply our own circle. */
    public static Bitmap rasterize(Drawable source) {
        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        // Transparent legacy artwork still has a complete, legible circular tile.
        canvas.drawColor(Color.WHITE);
        if (source instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable)source;
            int inset = Math.round(SIZE * AdaptiveIconDrawable.getExtraInsetFraction());
            Rect bounds = new Rect(-inset, -inset, SIZE + inset, SIZE + inset);
            draw(canvas, adaptive.getBackground(), bounds);
            draw(canvas, adaptive.getForeground(), bounds);
        } else {
            int width = source.getIntrinsicWidth(), height = source.getIntrinsicHeight();
            float scale = width > 0 && height > 0 ? (float)SIZE / Math.max(width, height) : 1;
            int w = width > 0 && height > 0 ? Math.round(width * scale) : SIZE;
            int h = width > 0 && height > 0 ? Math.round(height * scale) : SIZE;
            draw(canvas, source, new Rect((SIZE - w) / 2, (SIZE - h) / 2, (SIZE + w) / 2, (SIZE + h) / 2));
        }
        return bitmap;
    }

    private static void draw(Canvas canvas, Drawable drawable, Rect bounds) {
        if (drawable == null) return;
        Rect original = new Rect(drawable.getBounds());
        drawable.setBounds(bounds); drawable.draw(canvas); drawable.setBounds(original);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float left = (getWidth() - size) / 2, top = (getHeight() - size) / 2;
        matrix.setScale(size / bitmap.getWidth(), size / bitmap.getHeight());
        matrix.postTranslate(left, top); shader.setLocalMatrix(matrix);
        canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, size / 2f, paint);
    }
}
