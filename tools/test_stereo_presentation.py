"""Exercise the actual Presentation adapter with separate phone and display contexts."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
import hashlib
import struct
import zlib

from test_display_startup import STUBS, JAVA, ROOT
from build_embedded_adb import BASE
from display_startup import patch_display_startup


VIEW_STUBS = {
 'android/app/ActivityOptions.java': '''package android.app; import android.os.Bundle;
  public class ActivityOptions {int id;public static ActivityOptions makeBasic(){return new ActivityOptions();}
  public ActivityOptions setLaunchDisplayId(int value){id=value;return this;}public Bundle toBundle(){Bundle b=new Bundle();b.putInt("android.activity.launchDisplayId",id);return b;}}''',
 'android/content/Intent.java': '''package android.content; import android.os.Bundle;public class Intent {
  public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000,FLAG_ACTIVITY_MULTIPLE_TASK=0x08000000,
  FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS=0x00800000,FLAG_ACTIVITY_NO_ANIMATION=0x00010000;
  ComponentName c;int flags;Bundle extras=new Bundle();public Intent(){}public Intent(Intent i){c=i.c;flags=i.flags;extras=new Bundle(i.extras);}
  public Intent addFlags(int f){flags|=f;return this;}public int getFlags(){return flags;}
  public Intent setComponent(ComponentName c){this.c=c;return this;}public ComponentName getComponent(){return c;}
  public Intent putExtra(String k,int v){extras.putInt(k,v);return this;}public int getIntExtra(String k,int d){return extras.getInt(k,d);}}''',
 'android/app/Activity.java': '''package android.app; import android.os.Bundle;import android.content.Intent;import android.view.*;
  public class Activity extends android.content.Context {public boolean finishing,destroyed;public Display display;
  public Bundle lastOptions;public Intent lastIntent,intent=new Intent();public Window window=new Window();
  public boolean isFinishing(){return finishing;}public boolean isDestroyed(){return destroyed;}
  public Display getDisplay(){return display;}public Window getWindow(){return window;}
  public void startActivity(Intent i,Bundle b){lastIntent=i;lastOptions=b;}public Intent getIntent(){return intent;}
  protected void onCreate(Bundle b){}protected void onResume(){}protected void onDestroy(){}public void onAttachedToWindow(){}
  public void setTheme(int t){}public void setContentView(View v){}public void finishAndRemoveTask(){finishing=true;}}''',
 'android/view/Window.java': '''package android.view;public class Window {
  WindowManager.LayoutParams attrs=new WindowManager.LayoutParams();View decor=new View(null);public int format,background;public boolean fits=true;
  public WindowManager.LayoutParams getAttributes(){return attrs;}public void setAttributes(WindowManager.LayoutParams a){attrs=a;}
  public void setBackgroundDrawableResource(int r){background=r;}public void setFormat(int f){format=f;}
  public void addFlags(int f){attrs.flags|=f;}public void setLayout(int w,int h){attrs.width=w;attrs.height=h;}
  public void setDecorFitsSystemWindows(boolean b){fits=b;}public View getDecorView(){return decor;}}''',
 'android/view/inspector/WindowInspector.java': '''package android.view.inspector; import android.view.View;
  public class WindowInspector {public static int scans;public static java.util.List<View> windows=new java.util.ArrayList<>();
  public static java.util.List<View> getGlobalWindowViews(){scans++;return new java.util.ArrayList<>(windows);}}''',
 'android/view/ViewGroup.java': '''package android.view; public class ViewGroup extends View {
  public ViewGroup(android.content.Context c){super(c);} public static class LayoutParams {
  public static final int MATCH_PARENT=-1;public int width,height;public LayoutParams(int w,int h){width=w;height=h;}}}''',
 'android/view/Gravity.java': 'package android.view; public class Gravity {public static final int TOP=48,LEFT=3;}',
 'android/view/WindowManager.java': '''package android.view; public class WindowManager {
  public int updates;public View lastView;public void updateViewLayout(View v,ViewGroup.LayoutParams p){updates++;lastView=v;v.params=p;v.observer.layout();}
  public static class LayoutParams extends ViewGroup.LayoutParams {public static final int TYPE_PRIVATE_PRESENTATION=2030;
  public static final int FLAG_FULLSCREEN=1024,FLAG_LAYOUT_IN_SCREEN=256,FLAG_FORCE_NOT_FULLSCREEN=2048,FLAG_NOT_FOCUSABLE=8,FLAG_NOT_TOUCHABLE=16,FLAG_ALT_FOCUSABLE_IM=131072;
  public int preferredDisplayModeId,type,flags,x,y,gravity,fit=7;public LayoutParams(){super(850,480);}
  public int getFitInsetsTypes(){return fit;}public void setFitInsetsTypes(int f){fit=f;}}}''',
 'android/view/WindowInsets.java': 'package android.view; public class WindowInsets {public static class Type {public static int systemBars(){return 7;}}}',
 'android/view/WindowInsetsController.java': '''package android.view; public class WindowInsetsController {
  public static final int BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE=2;public int hidden,behavior;
  public void setSystemBarsBehavior(int b){behavior=b;}public void hide(int types){hidden=types;}}''',
 'android/view/ViewTreeObserver.java': '''package android.view; public class ViewTreeObserver {
  public java.util.List<OnGlobalLayoutListener> listeners=new java.util.ArrayList<>();
  public interface OnGlobalLayoutListener{void onGlobalLayout();}public boolean isAlive(){return true;}
  public void addOnGlobalLayoutListener(OnGlobalLayoutListener l){listeners.add(l);}
  public void removeOnGlobalLayoutListener(OnGlobalLayoutListener l){listeners.remove(l);}
  public void layout(){for(OnGlobalLayoutListener l:new java.util.ArrayList<>(listeners))l.onGlobalLayout();}}''',
 'android/view/View.java': '''package android.view; import android.os.*; import android.content.Context;
  public class View {public static final int SYSTEM_UI_FLAG_LAYOUT_STABLE=256,SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION=512,
  SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN=1024,SYSTEM_UI_FLAG_HIDE_NAVIGATION=2,SYSTEM_UI_FLAG_FULLSCREEN=4,SYSTEM_UI_FLAG_IMMERSIVE_STICKY=4096;
  public Context context;public View root=this;public Display display;public boolean attached;public int ui;
  public ViewGroup.LayoutParams params=new ViewGroup.LayoutParams(850,480);public ViewTreeObserver observer=new ViewTreeObserver();
  public WindowInsetsController insets=new WindowInsetsController();public java.util.List<OnAttachStateChangeListener> listeners=new java.util.ArrayList<>();
  public View(Context c){context=c;}public interface OnAttachStateChangeListener{void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}
  public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){listeners.add(l);}public Context getContext(){return context;}
  public boolean isAttachedToWindow(){return attached;}public View getRootView(){return root;}public Display getDisplay(){return display;}
  public ViewGroup.LayoutParams getLayoutParams(){return params;}public void setLayoutParams(ViewGroup.LayoutParams p){params=p;root.observer.layout();}
  public ViewTreeObserver getViewTreeObserver(){return observer;}public void post(Runnable r){new Handler(Looper.getMainLooper()).postDelayed(r,0);}
  public void removeCallbacks(Runnable r){new Handler(Looper.getMainLooper()).removeCallbacks(r);}public int getSystemUiVisibility(){return ui;}
  public void setSystemUiVisibility(int i){ui=i;}public void setBackgroundColor(int c){}public WindowInsetsController getWindowInsetsController(){return insets;}
  public int getWidth(){return 850;}public int getHeight(){return 480;}
  public void attach(View r){root=r;attached=true;for(OnAttachStateChangeListener l:new java.util.ArrayList<>(listeners))l.onViewAttachedToWindow(this);}
  public void detach(){attached=false;for(OnAttachStateChangeListener l:new java.util.ArrayList<>(listeners))l.onViewDetachedFromWindow(this);}}''',
}


class StereoPresentationTests(unittest.TestCase):
    def test_actual_presentation_window_and_lifecycle(self):
        with tempfile.TemporaryDirectory() as work:
            work = Path(work)
            sources = []
            for name, code in dict(STUBS, **VIEW_STUBS).items():
                if name=='dev/beampro/port/StereoPresentationCompat.java':continue
                p = work / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(code)
                sources.append(str(p))
            sdk=Path.home()/'Library/Android/sdk/platforms/android-34/android.jar'
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-cp',str(sdk),'-d', str(work), *sources,
                            str(ROOT / 'port-client/src/dev/beampro/port/DisplayStartupCompat.java'),
                            str(ROOT / 'port-client/src/dev/beampro/port/StereoPresentationCompat.java'),
                            str(ROOT / 'port-client/src/dev/beampro/port/ArBackdropActivity.java'),
                            str(ROOT / 'tools/StereoPresentationCompatTest.java')], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', str(work)+':'+str(sdk), 'StereoPresentationCompatTest'],
                                    text=True, capture_output=True, check=True)
            self.assertIn('PASS 46 presentation assertions', result.stdout)

    def test_constructor_hook_exact_scope_and_reject_double_patch(self):
        with zipfile.ZipFile(BASE) as z:
            original = z.read('classes3.dex')
        with tempfile.TemporaryDirectory() as work:
            work = Path(work)
            patched = patch_display_startup(original, work / 'patch', JAVA)
            self.assertEqual(patch_display_startup(patched, work / 'restore', JAVA,
                                                  restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_display_startup(patched, work / 'double', JAVA, baseline=original)
            self.assertIn(b'GlassesDisplay.java', patched)
            damaged = bytearray(patched.replace(b'GlassesDisplay.java', b'GlassesDisplay.javx', 1))
            damaged[12:32] = hashlib.sha1(damaged[32:]).digest()
            struct.pack_into('<I', damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_display_startup(damaged, work / 'unrelated', JAVA, restore=True, baseline=original)


if __name__ == '__main__':
    unittest.main()
