import dev.beampro.port.HeadsetIconCompat;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;

public final class HeadsetIconCompatTest {
    static int count;
    static void check(boolean b,String why){count++;if(!b)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        ApplicationInfo app=new ApplicationInfo();PackageManager packages=new PackageManager();
        for(int dpi:new int[]{100,160,360,476,640}) {
            int size=108*dpi/160;
            Drawable bg=new Drawable(size,size),fg=new Drawable(size,size);
            AdaptiveIconDrawable icon=new AdaptiveIconDrawable(size,bg,fg);
            Rect before=new Rect(fg.getBounds());
            Bitmap front=HeadsetIconCompat.foreground(icon,new PackageInfo()),back=HeadsetIconCompat.background(icon,new PackageInfo());
            check(front.w==432&&front.h==432&&back.w==432&&back.h==432,"Both adaptive layers stay 432 at density "+dpi);
            check(fg.drawnBounds.equals(new Rect(0,0,432,432))&&bg.drawnBounds.equals(fg.drawnBounds),"Rasterize both layers directly at the same bounds");
            check(front.density==0&&front.fill==0&&fg.getBounds().equals(before),"Preserve transparent pixels and original drawable bounds");
        }
        packages.original=new AdaptiveIconDrawable(243,new Drawable(243,243),new Drawable(243,243));
        packages.resources.high=new AdaptiveIconDrawable(432,new Drawable(432,432),new Drawable(432,432));
        Drawable selected=HeadsetIconCompat.load(packages,app);
        check(selected==packages.resources.high&&packages.resources.density==640&&packages.resources.id==42,"Load higher-density source resources before rasterizing");
        check(selected.mutated,"Isolate cached drawable state");
        packages.resources.fail=true;check(HeadsetIconCompat.load(packages,app)==packages.original,"Keep default icon when high-density resource cannot load");packages.resources.fail=false;
        packages.original=new BitmapDrawable(320);
        check(HeadsetIconCompat.load(packages,app)==packages.original,"Do not turn an OEM flat icon into adaptive layers");
        packages.resources.high=new BitmapDrawable(192);
        check(HeadsetIconCompat.load(packages,app)==packages.original,"Do not replace a better OEM bitmap with a smaller source");
        packages.original=new BitmapDrawable(72);
        check(HeadsetIconCompat.load(packages,app)==packages.resources.high,"Use a larger source bitmap when it improves detail");
        packages.resources.density=0;app.icon=0;
        check(HeadsetIconCompat.load(packages,app)==packages.original&&packages.resources.density==0,"No-resource apps keep PackageManager's default icon");
        Drawable legacy=new Drawable(72,36);Rect bounds=new Rect(legacy.getBounds());Bitmap bitmap=HeadsetIconCompat.legacy(legacy);
        check(bitmap.w==432&&bitmap.h==216,"Preserve legacy icon aspect ratio");
        check(bitmap.fill==-1&&legacy.drawnBounds.equals(new Rect(-2,-2,434,218)),"Preserve legacy white backing and edge treatment");
        check(legacy.filter&&legacy.getBounds().equals(bounds),"Filter bitmap scaling and restore bounds");
        bitmap=HeadsetIconCompat.legacy(new Drawable(-1,0));check(bitmap.w==432&&bitmap.h==432,"Drawables without intrinsic dimensions are safe");
        bitmap=HeadsetIconCompat.legacy(new Drawable(Integer.MAX_VALUE,1));check(bitmap.w==432&&bitmap.h==1,"Bound extreme aspect ratios and avoid zero-sized textures");
        bitmap=HeadsetIconCompat.foreground(new AdaptiveIconDrawable(243,null,null),new PackageInfo());check(bitmap.w==432&&bitmap.fill==0,"Missing adaptive layer remains transparent");
        Drawable full=new AdaptiveIconDrawable(243,null,null);bitmap=HeadsetIconCompat.adaptive(full);
        check(bitmap.w==432&&bitmap.h==432&&bitmap.fill==0,"Flattened adaptive path also gets a high-resolution transparent canvas");
        Drawable broken=new Drawable(48,48);broken.fail=true;bounds=new Rect(broken.getBounds());boolean failed=false;
        try{HeadsetIconCompat.legacy(broken);}catch(IllegalStateException expected){failed=true;}
        check(failed&&broken.getBounds().equals(bounds)&&Bitmap.last.recycled,"Restore shared state and release allocation when rendering fails");
        packages.missing=true;failed=false;try{HeadsetIconCompat.load(packages,app);}catch(PackageManager.NameNotFoundException expected){failed=true;}
        check(failed,"Preserve the original removed-package failure contract");
        System.out.println("PASS headset icon assertions: "+count);
    }
}
