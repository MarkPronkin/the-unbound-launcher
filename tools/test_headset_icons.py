"""Check density-independent icon rendering and the exact JNI-loader patch boundary."""
from pathlib import Path
import hashlib
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib
from test_display_startup import ROOT, JAVA
from build_embedded_adb import BASE
from icon_resolution import patch_icon_resolution

STUBS={
 'android/graphics/Rect.java':'''package android.graphics;public class Rect {
  public int left,top,right,bottom;public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
  public Rect(Rect r){this(r.left,r.top,r.right,r.bottom);}public boolean equals(Object x){if(!(x instanceof Rect))return false;Rect r=(Rect)x;return left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}}''',
 'android/graphics/Color.java':'package android.graphics;public class Color {public static final int WHITE=-1;}',
 'android/graphics/Bitmap.java':'''package android.graphics;public class Bitmap {
  public enum Config {ARGB_8888}public static final int DENSITY_NONE=0;public int w,h,density=360,fill;public boolean recycled;public static Bitmap last;
  public Bitmap(int w,int h){this.w=w;this.h=h;}public static Bitmap createBitmap(int w,int h,Config c){return last=new Bitmap(w,h);}
  public int getWidth(){return w;}public int getHeight(){return h;}public void setDensity(int d){density=d;}
  public void eraseColor(int c){fill=c;}public void recycle(){recycled=true;}}''',
 'android/graphics/Canvas.java':'''package android.graphics;public class Canvas {public final Bitmap bitmap;public Canvas(Bitmap b){bitmap=b;}}''',
 'android/graphics/drawable/Drawable.java':'''package android.graphics.drawable;import android.graphics.*;
  public class Drawable {public int w,h;public boolean mutated,filter,fail;public Canvas drawn;public Rect drawnBounds;
  Rect bounds=new Rect(1,2,3,4);public Drawable(int w,int h){this.w=w;this.h=h;}
  public int getIntrinsicWidth(){return w;}public int getIntrinsicHeight(){return h;}public Drawable mutate(){mutated=true;return this;}
  public Rect getBounds(){return bounds;}public void setBounds(Rect r){bounds=new Rect(r);}public void setBounds(int l,int t,int r,int b){bounds=new Rect(l,t,r,b);}
  public void setFilterBitmap(boolean v){filter=v;}public void draw(Canvas c){drawn=c;drawnBounds=new Rect(bounds);if(fail)throw new IllegalStateException("draw failed");}}''',
 'android/graphics/drawable/AdaptiveIconDrawable.java':'''package android.graphics.drawable;public class AdaptiveIconDrawable extends Drawable {
  Drawable bg,fg;public AdaptiveIconDrawable(int size,Drawable b,Drawable f){super(size,size);bg=b;fg=f;}
  public Drawable getBackground(){return bg;}public Drawable getForeground(){return fg;}}''',
 'android/graphics/drawable/BitmapDrawable.java':'''package android.graphics.drawable;import android.graphics.Bitmap;
  public class BitmapDrawable extends Drawable {Bitmap bitmap;public BitmapDrawable(int size){super(size,size);bitmap=new Bitmap(size,size);}public Bitmap getBitmap(){return bitmap;}}''',
 'android/content/pm/ApplicationInfo.java':'package android.content.pm;public class ApplicationInfo {public int icon=42;public String packageName="test.app";}',
 'android/content/pm/PackageInfo.java':'package android.content.pm;public class PackageInfo {}',
 'android/content/res/Resources.java':'''package android.content.res;import android.graphics.drawable.Drawable;
  public class Resources {public Drawable high;public int density,id;public boolean fail;
  public Drawable getDrawableForDensity(int id,int d,Object theme){density=d;this.id=id;if(fail)throw new IllegalArgumentException("missing");return high;}
  public static Resources getSystem(){throw new AssertionError("Do not size headset icons from screen density");}}''',
 'android/content/pm/PackageManager.java':'''package android.content.pm;import android.graphics.drawable.Drawable;import android.content.res.Resources;
  public class PackageManager {public Drawable original;public Resources resources=new Resources();public boolean missing;
  public static class NameNotFoundException extends Exception {}
  public Drawable getApplicationIcon(ApplicationInfo a)throws NameNotFoundException{if(missing)throw new NameNotFoundException();return original;}
  public Resources getResourcesForApplication(ApplicationInfo a)throws NameNotFoundException{return resources;}}''',
 'android/util/DisplayMetrics.java':'package android.util;public class DisplayMetrics {public static final int DENSITY_XXXHIGH=640;}',
 'android/util/Log.java':'package android.util;public class Log {public static int i(String t,String m){return 0;}}',
}


class HeadsetIconTests(unittest.TestCase):
    def test_loader_and_rasterizer(self):
        with tempfile.TemporaryDirectory() as tmp:
            work=Path(tmp);files=[]
            for name,code in STUBS.items():
                p=work/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(code);files.append(str(p))
            subprocess.run([str(JAVA/'bin/javac'),'--release','8','-d',str(work),*files,
                            str(ROOT/'port-client/src/dev/beampro/port/HeadsetIconCompat.java'),str(ROOT/'tools/HeadsetIconCompatTest.java')],check=True)
            result=subprocess.run([str(JAVA/'bin/java'),'-cp',str(work),'HeadsetIconCompatTest'],text=True,capture_output=True,check=True)
            self.assertIn('PASS headset icon assertions:',result.stdout)

    def test_scoped_icon_patch_round_trip_and_tamper_rejection(self):
        with zipfile.ZipFile(BASE) as z:original=z.read('classes2.dex')
        with tempfile.TemporaryDirectory() as tmp:
            work=Path(tmp);patched=patch_icon_resolution(original,work/'patch',JAVA)
            self.assertEqual(patch_icon_resolution(patched,work/'restore',JAVA,restore=True,baseline=original),original)
            with self.assertRaises(subprocess.CalledProcessError):patch_icon_resolution(patched,work/'double',JAVA,baseline=original)
            self.assertIn(b'IconLoadingHelper.java',patched)
            bad=bytearray(patched.replace(b'IconLoadingHelper.java',b'IconLoadingHelper.javx',1))
            bad[12:32]=hashlib.sha1(bad[32:]).digest();struct.pack_into('<I',bad,8,zlib.adler32(bad[12:])&0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):patch_icon_resolution(bad,work/'tamper',JAVA,restore=True,baseline=original)


if __name__=='__main__':unittest.main()
