package dev.beampro.standalone;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/** Owns capture buffers and verifies pixels, rather than treating allocation as rendering. */
public final class FrameSink implements AutoCloseable {
    public interface Listener { void frame(Bitmap bitmap); }
    public final ImageReader reader;
    public int frames, nonblackFrames, changedFrames;
    public long lastHash;
    private Bitmap last;
    private boolean closed;
    private long sampledAt;

    public FrameSink(int width, int height, Handler handler, Listener listener) {
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(source -> {
            if (closed) return;
            try (Image image = source.acquireLatestImage()) {
                if (image == null) return;
                long now=android.os.SystemClock.elapsedRealtime();
                if (now-sampledAt<150) return;
                sampledAt=now;
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();
                // Do not assume packed rows; vendor capture buffers often contain row padding.
                int[] pixels = new int[image.getWidth() * image.getHeight()];
                for (int y=0; y<image.getHeight(); y++) for (int x=0; x<image.getWidth(); x++) {
                    int offset=y*plane.getRowStride()+x*plane.getPixelStride();
                    int r=buffer.get(offset)&255, g=buffer.get(offset+1)&255, b=buffer.get(offset+2)&255;
                    pixels[y*image.getWidth()+x]=0xff000000|(r<<16)|(g<<8)|b;
                }
                long hash=hash(pixels); boolean nonblack=false;
                for (int pixel : pixels) if ((pixel & 0x00ffffff) > 0x080808) { nonblack=true; break; }
                if (frames > 0 && hash != lastHash) changedFrames++;
                frames++; if (nonblack) nonblackFrames++; lastHash=hash;
                if (last != null) last.recycle();
                last=Bitmap.createBitmap(pixels,image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);
                if (listener != null) listener.frame(last);
            } catch (IllegalStateException error) {
                if (!closed) android.util.Log.e("UnboundStandalone", "Capture buffer failure", error);
            }
        }, handler);
    }
    public static long hash(int[] pixels) {
        long result=0xcbf29ce484222325L;
        for (int pixel:pixels) { result ^= pixel; result *= 0x100000001b3L; }
        return result;
    }
    public static long cropHash(Bitmap source, Rect bounds) {
        Rect area=new Rect(bounds);
        if (!area.intersect(0,0,source.getWidth(),source.getHeight()) || area.isEmpty()) return 0;
        int[] pixels=new int[area.width()*area.height()];
        source.getPixels(pixels,0,area.width(),area.left,area.top,area.width(),area.height());
        return hash(pixels);
    }
    public void save(File destination) {
        if (last == null) return;
        try (FileOutputStream output=new FileOutputStream(destination)) { last.compress(Bitmap.CompressFormat.PNG,100,output); }
        catch (java.io.IOException error) { android.util.Log.e("UnboundStandalone", "Cannot save frame", error); }
    }
    public JSONObject stats() {
        JSONObject data=new JSONObject();
        Results.put(data,"frames",frames); Results.put(data,"nonblack_frames",nonblackFrames);
        Results.put(data,"changed_frames",changedFrames); Results.put(data,"last_hash",Long.toHexString(lastHash));
        return data;
    }
    @Override public void close() {
        if (closed) return;
        closed=true; reader.setOnImageAvailableListener(null,null); reader.close();
        if (last != null) { last.recycle(); last=null; }
    }
}
