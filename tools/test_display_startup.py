"""Run the real Android adapter against a deterministic, device-free Android test boundary."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from build_embedded_adb import BASE
from display_startup import patch_display_startup

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
STUBS = {
    'dev/beampro/port/StereoPresentationCompat.java': '''package dev.beampro.port;
      public class StereoPresentationCompat {public static void track(android.app.Activity owner){}}''',
    'android/os/Bundle.java': '''package android.os; public class Bundle extends java.util.HashMap<String,Integer> {
      public Bundle() {} public Bundle(Bundle b) { super(b); }
      public int getInt(String k,int d) { return containsKey(k)?get(k):d; }
      public void putInt(String k,int v) { put(k,v); }}''',
    'android/os/Build.java': 'package android.os; public class Build { public static class VERSION { public static int SDK_INT=37; }}',
    'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){ return new Looper(); }}',
    'android/os/SystemClock.java': 'package android.os; public class SystemClock { public static long now; public static long uptimeMillis(){return now;} }',
    'android/os/Handler.java': '''package android.os; import java.util.*; public class Handler {
      static class Task { Runnable r; long time; Task(Runnable r,long t){this.r=r;time=t;} }
      static List<Task> tasks=new ArrayList<>(); public Handler(Looper l){}
      public boolean postDelayed(Runnable r,long ms){tasks.add(new Task(r,SystemClock.now+ms));return true;}
      public void removeCallbacks(Runnable r){tasks.removeIf(t->t.r==r);}
      public static void advance(long ms){long until=SystemClock.now+ms;
        while(true){Task next=null; for(Task t:tasks)if(t.time<=until&&(next==null||t.time<next.time))next=t;
          if(next==null)break;tasks.remove(next);SystemClock.now=next.time;next.r.run();} SystemClock.now=until;}
    }''',
    'android/util/Log.java': '''package android.util; public class Log {
      public static int i(String t,String s){return 0;} public static int w(String t,String s){return 0;}
      public static int w(String t,String s,Throwable x){return 0;} public static int e(String t,String s,Throwable x){return 0;}}''',
    'android/content/ComponentName.java': '''package android.content; public class ComponentName {
      String p,c; public ComponentName(String p,String c){this.p=p;this.c=c;}
      public String getClassName(){return c;} public String getPackageName(){return p;}}''',
    'android/content/Intent.java': '''package android.content; public class Intent {
      ComponentName c;int flags; public Intent(){} public Intent(Intent i){c=i.c;flags=i.flags;}
      public Intent addFlags(int f){flags|=f;return this;}public int getFlags(){return flags;}
      public Intent setComponent(ComponentName c){this.c=c;return this;} public ComponentName getComponent(){return c;}}''',
    'android/content/Context.java': '''package android.content; import android.app.*; import android.hardware.display.*;
      public class Context { public static Application app; public static DisplayManager displays; public static ActivityManager manager;
      public Context getApplicationContext(){return app;} public String getPackageName(){return "com.xreal.evapro.nebula";}
      public <T>T getSystemService(Class<T> c){return c.cast(c==DisplayManager.class?displays:manager);}}''',
    'android/app/Application.java': '''package android.app; import android.os.Bundle; public class Application extends android.content.Context {
      public java.util.List<ActivityLifecycleCallbacks> callbacks=new java.util.ArrayList<>();
      public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){callbacks.add(c);}
      public interface ActivityLifecycleCallbacks {void onActivityCreated(Activity a,Bundle b);void onActivityStarted(Activity a);
      void onActivityResumed(Activity a);void onActivityPaused(Activity a);void onActivityStopped(Activity a);
      void onActivityDestroyed(Activity a);void onActivitySaveInstanceState(Activity a,Bundle b);}}''',
    'android/app/Activity.java': '''package android.app; import android.os.Bundle; import android.content.Intent; import android.view.*;
      public class Activity extends android.content.Context {public boolean finishing,destroyed;public Display display;
      public Bundle lastOptions;public Intent lastIntent; public Window window=new Window();
      public boolean isFinishing(){return finishing;}public boolean isDestroyed(){return destroyed;}
      public Display getDisplay(){return display;}public Window getWindow(){return window;}
      public void startActivity(Intent i,Bundle b){lastIntent=i;lastOptions=b;}}''',
    'android/app/ActivityManager.java': '''package android.app; public class ActivityManager { public boolean allowed=true;
      public boolean isActivityStartAllowedOnDisplay(android.content.Context c,int id,android.content.Intent i){return allowed;}}''',
    'android/view/WindowManager.java': 'package android.view; public class WindowManager {public static class LayoutParams {public int preferredDisplayModeId;}}',
    'android/view/Window.java': '''package android.view; public class Window {WindowManager.LayoutParams attrs=new WindowManager.LayoutParams();
      public WindowManager.LayoutParams getAttributes(){return attrs;}public void setAttributes(WindowManager.LayoutParams a){attrs=a;}}''',
    'android/view/Display.java': '''package android.view; public class Display {
      public static final int STATE_ON=2;public int id,state=2;public boolean valid=true,xreal;public Mode current;public Mode[] modes;
      public Display(int id,boolean xr,Mode current,Mode... modes){this.id=id;xreal=xr;this.current=current;this.modes=modes;}
      public boolean isValid(){return valid;}public int getDisplayId(){return id;}public int getState(){return state;}
      public Mode getMode(){return current;}public Mode[] getSupportedModes(){return modes;}
      public static class Mode {int id,w,h;float hz;public Mode(int id,int w,int h,float hz){this.id=id;this.w=w;this.h=h;this.hz=hz;}
      public int getModeId(){return id;}public int getPhysicalWidth(){return w;}public int getPhysicalHeight(){return h;}
      public float getRefreshRate(){return hz;}}}''',
    'android/hardware/display/DisplayManager.java': '''package android.hardware.display; import android.view.Display; import android.os.Handler;
      public class DisplayManager {public Display[] displays=new Display[0];public java.util.List<DisplayListener> listeners=new java.util.ArrayList<>();
      public Display[] getDisplays(){return displays;}public Display getDisplay(int id){for(Display d:displays)if(d.id==id)return d;return null;}
      public void registerDisplayListener(DisplayListener l,Handler h){listeners.add(l);}public void unregisterDisplayListener(DisplayListener l){listeners.remove(l);}
      public void changed(int id){for(DisplayListener l:new java.util.ArrayList<>(listeners))l.onDisplayChanged(id);}
      public interface DisplayListener {void onDisplayAdded(int id);void onDisplayRemoved(int id);void onDisplayChanged(int id);}}''',
    'ai/nreal/activitylife/NRFakeActivity.java': 'package ai.nreal.activitylife; public class NRFakeActivity extends android.app.Activity {}',
    'ai/nreal/activitylife/NRXRApp.java': '''package ai.nreal.activitylife; import android.app.Activity; import android.content.Intent;
      import android.view.Display; import android.os.Bundle; import dev.beampro.port.DisplayStartupCompat;
      public class NRXRApp {public Activity mContext=new Activity(),mUnityActivity=new Activity();
      public boolean mIsQuiting,mIsActivityStartedOnDPAdded,launchResult=true;public int launches,finishes;
      public static boolean isNrealDisplay(Display d,boolean log){return d.xreal;}
      private boolean startActivityOnDisplay(Display d,Intent i){launches++;if(!launchResult)return false;
        Bundle b=new Bundle();b.putInt("android.activity.launchDisplayId",d.id);DisplayStartupCompat.startActivity(mContext,i,b);return true;}
      private void tryFinishUnityActivity(){finishes++;mUnityActivity.finishing=true;}}'''
}


class DisplayStartupTests(unittest.TestCase):
    def test_adapter_with_delayed_consent_mode_changes_and_lifecycle(self):
        with tempfile.TemporaryDirectory() as work:
            work = Path(work)
            sources = []
            for name, code in STUBS.items():
                target = work / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(code)
                sources.append(target)
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', str(work),
                            *map(str, sources), str(ROOT / 'port-client/src/dev/beampro/port/DisplayStartupCompat.java'),
                            str(ROOT / 'tools/DisplayStartupCompatTest.java')], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', str(work), 'DisplayStartupCompatTest'],
                                    check=True, text=True, capture_output=True)
            self.assertIn('PASS 52 display startup assertions', result.stdout)

    def test_dex_scope_round_trip_and_double_patch_rejection(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes.dex')
        with tempfile.TemporaryDirectory() as work:
            work = Path(work)
            patched = patch_display_startup(original, work / 'patch', JAVA)
            restored = patch_display_startup(patched, work / 'restore', JAVA, restore=True, baseline=original)
            self.assertEqual(restored, original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_display_startup(patched, work / 'double', JAVA, baseline=original)


if __name__ == '__main__':
    unittest.main()
