"""Exercise production connection recovery, missing Android intents and native callbacks."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from test_display_startup import ROOT, JAVA, STUBS as DISPLAY_STUBS
from build_embedded_adb import BASE
from connection_startup import patch_connection_startup, patch_connection_theme_manifest, CONNECTION_ACTIVITY

STUBS = dict(DISPLAY_STUBS)
STUBS.update({
    'dev/beampro/settings/DebugData.java': '''package dev.beampro.settings;public class DebugData {
      public static boolean fail;public static java.util.List<String[]> events=new java.util.ArrayList<>();
      public static void connectionEvent(android.content.Context c,String id,String event,String detail){
        if(fail)throw new IllegalStateException("diagnostic storage failed");events.add(new String[]{id,event,detail});}
      public static void connectionFailure(android.content.Context c,String reason){}
    }''',
    'dev/beampro/port/ConnectionAnimation.java': '''package dev.beampro.port;
      public class ConnectionAnimation {
        public static ConnectionAnimation last;public static boolean defer;public boolean disposed,failed,success,busy;public String phase;
        public Runnable retry,close,completion;public ConnectionAnimation(android.app.Activity a,Runnable r,Runnable c){last=this;retry=r;close=c;}
        public void show(android.app.Activity a){} public void stage(String s){phase=s;}
        public void starting(){}
        public void failure(String s,boolean b){failed=true;phase=s;busy=b;}
        public void connected(){success=true;}public void connected(Runnable r){success=true;completion=r;if(!defer)complete();}
        public void complete(){Runnable r=completion;completion=null;if(r!=null)r.run();}
        public void reveal(Runnable r){r.run();}
        public void dispose(){disposed=true;completion=null;}
      }''',
    'dev/beampro/port/ConnectionHandoff.java': '''package dev.beampro.port;
      public class ConnectionHandoff {
        public static ConnectionHandoff last;public static boolean defer;public boolean closed,cancelled,renderReady;
        final ConnectionAnimation animation;Runnable completion,failure;
        ConnectionHandoff(android.app.Activity a,ConnectionAnimation v){last=this;animation=v;}
        void start(Runnable r,Runnable f){completion=r;failure=f;if(!defer)new android.os.Handler().post(this::ready);}
        public void ready(){if(!closed){renderReady=true;if(animation!=null)animation.connected(()->animation.reveal(completion));else completion.run();}}
        public void failed(){if(!closed)failure.run();}
        boolean isReady(){return renderReady;}
        void close(boolean cancel){closed=true;cancelled=cancel;}
      }''',
    'android/os/Handler.java': '''package android.os;import java.util.*; public class Handler {
      static class Task {Handler h;Runnable r;long at;Task(Handler h,Runnable r,long at){this.h=h;this.r=r;this.at=at;}}
      static List<Task> tasks=new ArrayList<>();public Handler(Looper l){}public Handler(){}
      public boolean post(Runnable r){return postDelayed(r,0);}public boolean postDelayed(Runnable r,long ms){tasks.add(new Task(this,r,SystemClock.now+ms));return true;}
      public void removeCallbacks(Runnable r){tasks.removeIf(t->t.h==this&&t.r==r);}public void removeCallbacksAndMessages(Object token){tasks.removeIf(t->t.h==this);}
      public static void clear(){tasks.clear();}public static void advance(long ms){long end=SystemClock.now+ms;
        int steps=0;while(true){Task next=null;for(Task t:tasks)if(t.at<=end&&(next==null||t.at<next.at))next=t;
          if(next==null)break;if(++steps>10000)throw new AssertionError("unbounded queue");tasks.remove(next);SystemClock.now=next.at;next.r.run();}SystemClock.now=end;}}
    ''',
    'android/os/HandlerThread.java': '''package android.os;public class HandlerThread {
      public boolean stopped;public HandlerThread(String n){}public void start(){}public Looper getLooper(){return Looper.getMainLooper();}
      public boolean quitSafely(){stopped=true;return true;}}''',
    'android/content/Context.java': '''package android.content;import android.app.*;import android.hardware.display.*;import android.hardware.usb.*;
      public class Context {public static Application app;public static DisplayManager displays;public static ActivityManager manager;public static UsbManager usb;
      public Context getApplicationContext(){return app;}public String getPackageName(){return "dev.markpronkin.unbound";}
      public <T>T getSystemService(Class<T> c){return c.cast(c==DisplayManager.class?displays:c==UsbManager.class?usb:manager);}}''',
    'android/app/Activity.java': '''package android.app;import android.os.Bundle;import android.content.Intent;import android.view.*;import android.widget.*;
      public class Activity extends android.content.Context {public boolean finishing,destroyed;public Display display;public Bundle lastOptions;public Intent lastIntent;
      public Window window=new Window();public TextView mTvTitle=new TextView(),mTvSubTitle=new TextView();public View mTvStatus=new View(),mProgressBar=new View();
      public Button mBtnConfirm=new Button();public boolean isFinishing(){return finishing;}public boolean isDestroyed(){return destroyed;}
      public void finish(){finishing=true;}public Display getDisplay(){return display;}public Window getWindow(){return window;}
      public void startActivity(Intent i,Bundle b){lastIntent=i;lastOptions=b;}}''',
    'android/view/View.java': '''package android.view;public class View {public static final int VISIBLE=0,GONE=8;public int visibility;
      public void setVisibility(int v){visibility=v;}public int getVisibility(){return visibility;}}''',
    'android/widget/TextView.java': '''package android.widget;public class TextView extends android.view.View {
      public String text="";public void setText(CharSequence s){text=s.toString();}public CharSequence getText(){return text;}}''',
    'android/widget/Button.java': 'package android.widget;public class Button extends TextView {}',
    'ai/nreal/nebula/activity/setting/SettingActivity.java': '''package ai.nreal.nebula.activity.setting;public class SettingActivity extends android.app.Activity {
      public int refreshes;private void check3DBtn(){refreshes++;}}''',
    'android/hardware/usb/UsbDevice.java': '''package android.hardware.usb;public class UsbDevice {
      public int vendor;public UsbDevice(int v){vendor=v;}public int getVendorId(){return vendor;}public int getProductId(){return 1080;}}''',
    'android/hardware/usb/UsbManager.java': '''package android.hardware.usb;public class UsbManager {
      public boolean permission=true;public java.util.HashMap<String,UsbDevice> devices=new java.util.HashMap<>();
      public java.util.HashMap<String,UsbDevice> getDeviceList(){return devices;}public boolean hasPermission(UsbDevice d){return permission;}}''',
    'com/xreal/basicfunc/BasicFuncManager.java': '''package com.xreal.basicfunc;public class BasicFuncManager {
      public static String type;public static void setGlassesType(String t){type=t;}}''',
    'ai/nreal/nebula/manager/callback/ISdkStartResult.java': '''package ai.nreal.nebula.manager.callback;public interface ISdkStartResult {void onResult(int result);}''',
    'com/xreal/glasses/servicesdk/helper/PingGSeriesHelper.java': '''package com.xreal.glasses.servicesdk.helper;
      public class PingGSeriesHelper {
       public interface PingCallback {void onPingResult(boolean success);}
       public android.os.Handler mSdkHandler=new android.os.Handler();public int polls;
       public Runnable mRunnablePing=()->polls++;public PingCallback callback;
       public void release(){mSdkHandler.removeCallbacks(mRunnablePing);dev.beampro.port.PingCallbackCompat.released(this);}
      }''',
    'com/xreal/glassesdisplayplugevent/IGlassesEvent.java': '''package com.xreal.glassesdisplayplugevent;public interface IGlassesEvent {interface IDisplayEvent {}}''',
    'com/xreal/glassesdisplayplugevent/GlassesInitSetting.java': '''package com.xreal.glassesdisplayplugevent;public class GlassesInitSetting {
      static GlassesInitSetting instance=new GlassesInitSetting();public static GlassesInitSetting getInstance(){return instance;}
      public void unregisterIDisplayEvent(IGlassesEvent.IDisplayEvent e){}}''',
    'ai/nreal/nebula/manager/XREALManager.java': '''package ai.nreal.nebula.manager;
      import ai.nreal.nebula.manager.callback.ISdkStartResult;import dev.beampro.port.GlassesConnectionCompat;
      public class XREALManager {
       public static XREALManager instance;public static XREALManager getInstance(){return instance;}
       public int mGlassesType=7,starts,launches,unregistered,nextResult=0,target=5;public boolean mUsbConnect=true,hasGoLauncher;
       public FakeOta mOtaManager=new FakeOta();public FakeSdk mNRServiceControl=new FakeSdk();public Object mDisplayEvent;
       public ISdkStartResult callback;public boolean waitingDisplay,networkWait;
       public static class Event implements com.xreal.glassesdisplayplugevent.IGlassesEvent.IDisplayEvent {}
       public static class FakeOta {public int skuCalls;public Runnable onSecondSku;public String getSku(){skuCalls++;if(skuCalls==2&&onSecondSku!=null)onSecondSku.run();return "L";}public int getOtaNum(String s){throw new AssertionError("firmware probe during AR startup");}}
       public static class FakeSdk {public com.xreal.glasses.servicesdk.helper.PingGSeriesHelper mPingHelper=new com.xreal.glasses.servicesdk.helper.PingGSeriesHelper();
         public boolean started,isEthernetSeries=true;public int mode=5,stops,queries;public boolean isStartSdk(){return started;}public int get2D3D(){queries++;return mode;}
         public void stopSdk(){stops++;started=false;}}
       public void startSdk(ISdkStartResult cb){starts++;callback=cb;
         if(networkWait){mNRServiceControl.mPingHelper.callback=(com.xreal.glasses.servicesdk.helper.PingGSeriesHelper.PingCallback)
           dev.beampro.port.PingCallbackCompat.wrap(mNRServiceControl.mPingHelper,
             (com.xreal.glasses.servicesdk.helper.PingGSeriesHelper.PingCallback)ok->{
               if(GlassesConnectionCompat.acceptSdkCallback(cb)){if(ok){mNRServiceControl.started=true;GlassesConnectionCompat.launch(this);}cb.onResult(ok?0:1);}});return;}
         if(waitingDisplay){mNRServiceControl.started=true;mDisplayEvent=new Event();return;}
         if(nextResult<0)return;int r=nextResult;nextResult=0;
         if(r==0){mNRServiceControl.started=true;GlassesConnectionCompat.launch(this);}cb.onResult(r);}
       public void goLauncher(){hasGoLauncher=true;launches++;}public boolean isHasGoLauncher(){return hasGoLauncher;}
       private int getFinalDisplayConfig(){return target;}public void unregisterGlasses(Object o){unregistered++;}
       public void lateSuccess(){if(GlassesConnectionCompat.acceptSdkCallback(callback)){GlassesConnectionCompat.launch(this);callback.onResult(0);}}
      }''',
})


class ConnectionStartupTests(unittest.TestCase):
    def test_only_connection_activity_changes_to_opaque_theme(self):
        from verify_embedded_adb import elements
        from startup_theme import STYLE_ID
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('AndroidManifest.xml')
        patched = patch_connection_theme_manifest(original)
        expected = elements(original)
        for tag, attributes in expected:
            if tag == 'activity' and attributes.get('name') == CONNECTION_ACTIVITY:
                attributes['theme'] = STYLE_ID
                attributes['screenOrientation'] = 0xFFFFFFFF
        self.assertEqual(elements(patched), expected)
        self.assertEqual(len(patched), len(original))
        with self.assertRaises(ValueError): patch_connection_theme_manifest(patched)

    def test_real_adapter_recovers_usb_reenumeration_and_missing_display_events(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, code in STUBS.items():
                target = work / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(code)
                sources.append(target)
            sources += [ROOT / 'port-client/src/dev/beampro/port' / name for name in
                        ('GlassesConnectionCompat.java', 'DisplayStartupCompat.java', 'PingCallbackCompat.java')]
            sources.append(ROOT / 'tools/GlassesConnectionCompatTest.java')
            sources.append(ROOT/"compat/src/dev/beampro/compat/GlassesIdentity.java")
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', str(work), *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', str(work),
                                     'dev.beampro.port.GlassesConnectionCompatTest'], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS connection recovery assertions:', result.stdout)
            print(result.stdout.strip())

    def test_dex_scope_round_trip_and_double_patch_rejection(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes.dex')
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            patched = patch_connection_startup(original, work / 'patch', JAVA)
            self.assertEqual(patch_connection_startup(patched, work / 'restore', JAVA,
                                                    restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_connection_startup(patched, work / 'double', JAVA, baseline=original)


if __name__ == '__main__':
    unittest.main()
