package dev.beampro.port;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.Log;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Rasterize headset app artwork independently of the phone/display density. */
public final class HeadsetIconCompat {
    private static final int SIZE=432; // Matches the original headset's built-in artwork.
    private static final Set<String> logged=Collections.synchronizedSet(new HashSet<String>());
    private HeadsetIconCompat() {}

    public static Drawable load(PackageManager packages,ApplicationInfo app) throws PackageManager.NameNotFoundException {
        Drawable original=packages.getApplicationIcon(app),selected=original;
        boolean highDensity=false;
        if(app.icon!=0)try {
            Drawable candidate=packages.getResourcesForApplication(app)
                    .getDrawableForDensity(app.icon,DisplayMetrics.DENSITY_XXXHIGH,null);
            // Preserve OEM-rendered/legacy vs adaptive semantics, including the
            // number of layers Unity receives. Do not replace a themed flat icon
            // with an unrelated adaptive representation.
            boolean smallerBitmap=candidate instanceof BitmapDrawable&&original instanceof BitmapDrawable
                    &&(((BitmapDrawable)candidate).getBitmap().getWidth()<((BitmapDrawable)original).getBitmap().getWidth()
                       ||((BitmapDrawable)candidate).getBitmap().getHeight()<((BitmapDrawable)original).getBitmap().getHeight());
            if(candidate!=null&&!smallerBitmap&&(candidate instanceof AdaptiveIconDrawable)==(original instanceof AdaptiveIconDrawable)) {
                selected=candidate;highDensity=true;
            }
        } catch(RuntimeException|PackageManager.NameNotFoundException unavailable) {
            // The normal package-manager drawable is still a valid fallback.
        }
        if(selected==null)throw new IllegalArgumentException("Application icon unavailable");
        selected=selected.mutate();
        if(logged.add(app.packageName))Log.i("UnboundIcons","package="+app.packageName
                +" source="+selected.getClass().getSimpleName()+" intrinsic="+selected.getIntrinsicWidth()+"x"+selected.getIntrinsicHeight()
                +" highDensity="+highDensity+" maxRaster="+SIZE);
        return selected;
    }

    public static Bitmap adaptive(Drawable drawable) {return render(drawable,SIZE,SIZE,false,0);}

    public static Bitmap legacy(Drawable drawable) {
        int width=drawable.getIntrinsicWidth(),height=drawable.getIntrinsicHeight();
        int w=SIZE,h=SIZE;
        if(width>0&&height>0){double scale=(double)SIZE/Math.max(width,height);
            w=Math.max(1,(int)Math.round(width*scale));h=Math.max(1,(int)Math.round(height*scale));}
        return render(drawable,w,h,true,2);
    }

    public static Bitmap foreground(Drawable drawable,PackageInfo ignored) {
        return render(((AdaptiveIconDrawable)drawable).getForeground(),SIZE,SIZE,false,0);
    }

    public static Bitmap background(Drawable drawable,PackageInfo ignored) {
        return render(((AdaptiveIconDrawable)drawable).getBackground(),SIZE,SIZE,false,0);
    }

    private static Bitmap render(Drawable drawable,int width,int height,boolean white,int overscan) {
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        bitmap.setDensity(Bitmap.DENSITY_NONE);
        if(white)bitmap.eraseColor(Color.WHITE);
        if(drawable==null)return bitmap;
        Rect before=new Rect(drawable.getBounds());
        try {
            drawable.setFilterBitmap(true);
            drawable.setBounds(-overscan,-overscan,width+overscan,height+overscan);
            drawable.draw(new Canvas(bitmap));
            return bitmap;
        } catch(RuntimeException failure) {
            bitmap.recycle();throw failure;
        } finally {drawable.setBounds(before);}
    }
}
