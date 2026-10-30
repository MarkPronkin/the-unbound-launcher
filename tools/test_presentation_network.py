"""Exercise the production renderer session's network timers and display-switch callbacks."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_display_startup import ROOT, JAVA, STUBS as DISPLAY_STUBS
from test_glasses_network import STUBS as NETWORK_STUBS
from test_usb_permission import STUBS as USB_STUBS

STUBS = {
    **USB_STUBS, **DISPLAY_STUBS, **NETWORK_STUBS,
    'android/os/Bundle.java': USB_STUBS['android/os/Bundle.java'],
    'android/content/Context.java': '''package android.content; public class Context {
      public android.hardware.usb.UsbManager usb=new android.hardware.usb.UsbManager();
      public android.net.ConnectivityManager connectivity=new android.net.ConnectivityManager();
      public android.hardware.display.DisplayManager displays=new android.hardware.display.DisplayManager();
      public android.app.KeyguardManager guard=new android.app.KeyguardManager();
      public Context getApplicationContext(){return this;}
      public <T>T getSystemService(Class<T> c){return c.cast(c==android.hardware.usb.UsbManager.class?usb:
        c==android.net.ConnectivityManager.class?connectivity:c==android.hardware.display.DisplayManager.class?displays:guard);}}''',
    'android/os/Handler.java': '''package android.os;import java.util.*;public class Handler {
      static class Task {Runnable action;long at;Task(Runnable r,long t){action=r;at=t;}}
      static List<Task> tasks=new ArrayList<>();public Handler(){}public Handler(Looper l){}
      public boolean post(Runnable r){r.run();return true;}
      public boolean postDelayed(Runnable r,long ms){tasks.add(new Task(r,SystemClock.now+ms));return true;}
      public void removeCallbacks(Runnable r){tasks.removeIf(t->t.action==r);}
      public static void reset(){tasks.clear();}
      public static void advance(long ms){long until=SystemClock.now+ms;
        while(true){Task next=null;for(Task t:tasks)if(t.at<=until&&(next==null||t.at<next.at))next=t;
          if(next==null)break;tasks.remove(next);SystemClock.now=next.at;next.action.run();}SystemClock.now=until;}}''',
    'android/app/Activity.java': '''package android.app;public class Activity extends android.content.Context {
      public boolean finishing,destroyed;public int stops,starts;public android.view.Window window=new android.view.Window();
      public android.content.Intent intent=new android.content.Intent();
      public boolean isFinishing(){return finishing;}public boolean isDestroyed(){return destroyed;}
      public void finish(){finishing=true;}public android.view.Window getWindow(){return window;}
      public android.content.Intent getIntent(){return intent;}
      public void startForegroundService(android.content.Intent i){starts++;}public boolean stopService(android.content.Intent i){stops++;return true;}}''',
    'android/app/KeyguardManager.java': '''package android.app;public class KeyguardManager {
      public boolean locked;public boolean isKeyguardLocked(){return locked;}}''',
    'android/content/Intent.java': '''package android.content;public class Intent {
      public java.util.Map<String,Object> extras=new java.util.HashMap<>();
      public Intent(){}public Intent(Intent i){extras.putAll(i.extras);}public Intent(Context c,Class<?> t){}
      public Intent putExtra(String k,Object v){extras.put(k,v);return this;}public Intent putExtra(String k,boolean v){extras.put(k,v);return this;}
      public long getLongExtra(String k,long d){return extras.containsKey(k)?((Number)extras.get(k)).longValue():d;}}''',
    'android/content/DialogInterface.java': '''package android.content;public interface DialogInterface {
      interface OnClickListener {void onClick(DialogInterface d,int which);}interface OnCancelListener {void onCancel(DialogInterface d);}}''',
    'android/app/AlertDialog.java': '''package android.app;import android.content.*;public class AlertDialog {
      public void setOnCancelListener(DialogInterface.OnCancelListener l){}public void show(){}
      public static class Builder {public Builder(Context c,int theme){}public Builder setTitle(String s){return this;}
      public Builder setMessage(String s){return this;}public Builder setPositiveButton(String s,DialogInterface.OnClickListener l){return this;}
      public AlertDialog create(){return new AlertDialog();}}}''',
    'android/R.java': 'package android;public class R {public static class style {public static final int Theme_Material_Dialog_Alert=1;}}',
    'android/widget/Toast.java': '''package android.widget;public class Toast {public static final int LENGTH_LONG=1;
      public static Toast makeText(android.content.Context c,String s,int duration){return new Toast();}public void show(){}}''',
    'android/view/Display.java': '''package android.view;public class Display {
      public static final int STATE_ON=2,FLAG_PRESENTATION=8;public int id,state=2;public boolean xreal;
      public Mode current;public Display(int id,boolean xr,Mode m,Mode... unused){this.id=id;xreal=xr;current=m;}
      public int getDisplayId(){return id;}public int getState(){return state;}public int getFlags(){return FLAG_PRESENTATION;}
      public Mode getMode(){return current;}public static class Mode {int w,h;float hz;
      public Mode(int id,int w,int h,float hz){this.w=w;this.h=h;this.hz=hz;}
      public int getPhysicalWidth(){return w;}public int getPhysicalHeight(){return h;}public float getRefreshRate(){return hz;}}}''',
    'android/view/View.java': '''package android.view;public class View {Display display;Object params;
      public View(){}public View(Display d,Object p){display=d;params=p;}public boolean isAttachedToWindow(){return true;}
      public Display getDisplay(){return display;}public Object getLayoutParams(){return params;}public boolean isShown(){return true;}}''',
    'android/view/Window.java': 'package android.view;public class Window {public View getDecorView(){return new View();}}',
    'android/view/WindowManager.java': '''package android.view;public class WindowManager {public static class LayoutParams {
      public static final int TYPE_PRIVATE_PRESENTATION=2030;public int type;public LayoutParams(int type){this.type=type;}}}''',
    'android/view/inspector/WindowInspector.java': '''package android.view.inspector;public class WindowInspector {
      public static java.util.List<android.view.View> roots=new java.util.ArrayList<>();
      public static java.util.List<android.view.View> getGlobalWindowViews(){return roots;}}''',
    'dev/beampro/port/DisplayStartupCompat.java': '''package dev.beampro.port;public class DisplayStartupCompat {
      public static boolean xreal(android.view.Display d){return d!=null&&d.xreal;}}''',
    'dev/beampro/port/BackgroundUnitySurface.java': '''package dev.beampro.port;public class BackgroundUnitySurface {
      public BackgroundUnitySurface(Object o){}public void close(){}}''',
    'dev/beampro/port/ArSessionService.java': 'package dev.beampro.port;public class ArSessionService {static final String TOKEN="session";}',
}
for unused in ('dev/beampro/port/PresentationOnlyCompat.java', 'ai/nreal/activitylife/NRXRApp.java',
               'ai/nreal/activitylife/NRFakeActivity.java'):
    STUBS.pop(unused, None)


class PresentationNetworkTests(unittest.TestCase):
    def test_delayed_release_switches_stale_timers_and_failure_cleanup(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, code in STUBS.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(code)
                sources.append(path)
            sources += [ROOT / 'port-client/src/dev/beampro/port' / n for n in
                        ('PresentationOnlyCompat.java', 'PresentationReadiness.java', 'GlassesNetworkCompat.java', 'PingCallbackCompat.java')]
            sources += [ROOT / 'compat/src/dev/beampro/compat/GlassesIdentity.java',
                        ROOT / 'tools/GlassesNetworkTest.java', ROOT / 'tools/PresentationNetworkTest.java']
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp, *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp, 'dev.beampro.port.PresentationNetworkTest'],
                                    capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS renderer network lifecycle assertions:', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
