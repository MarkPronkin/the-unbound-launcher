"""Exercise timed display experiments and the production SDK adapter without physical glasses."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_display_startup import ROOT, JAVA
from test_usb_permission import STUBS as USB_STUBS

STUBS = dict(USB_STUBS)
STUBS.update({
    'android/content/Context.java': '''package android.content;public class Context {
      public final android.hardware.usb.UsbManager usb=new android.hardware.usb.UsbManager();
      public final ContentResolver resolver=new ContentResolver();public Context getApplicationContext(){return this;}
      public ContentResolver getContentResolver(){return resolver;}
      public <T>T getSystemService(Class<T> c){return c.cast(usb);}}''',
    'android/os/Bundle.java': '''package android.os;public class Bundle extends java.util.HashMap<String,Object> {
      public IBinder getBinder(String k){return (IBinder)get(k);}public void putBinder(String k,IBinder v){put(k,v);}
      public void putBoolean(String k,boolean v){put(k,v);}public boolean getBoolean(String k){return Boolean.TRUE.equals(get(k));}
      public void putString(String k,String v){put(k,v);}public String getString(String k){return (String)get(k);}
      public void putInt(String k,int v){put(k,v);}public int getInt(String k,int fallback){return containsKey(k)?(Integer)get(k):fallback;}}''',
    'android/os/Handler.java': '''package android.os;public class Handler {
      public final java.util.Queue<Runnable> jobs=new java.util.concurrent.ConcurrentLinkedQueue<>();public boolean stopped;
      public boolean post(Runnable r){if(stopped)return false;jobs.add(r);return true;}
      public void drain(){Runnable r;while((r=jobs.poll())!=null)r.run();}}''',
    'android/util/Log.java': '''package android.util;public class Log {
      public static int w(String t,String m,Throwable x){return 0;}}''',
    'dev/beampro/port/GlassesConnectionCompat.java': '''package dev.beampro.port;public class GlassesConnectionCompat {
      public static boolean available=true;public static boolean displayTestsAvailable(){return available;}}''',
    'dev/beampro/port/GlassesNetworkCompat.java': '''package dev.beampro.port;public class GlassesNetworkCompat {
      public static int open,closed;public static boolean ethernet=true;
      public static Lease select(android.content.Context c){if(!ethernet)return null;open++;return new Lease();}
      public static class Lease {public void close(){open--;closed++;}}}''',
    'com/xreal/basicfunc/BasicFuncManager.java': '''package com.xreal.basicfunc;public class BasicFuncManager {
      public static String type;public static void setGlassesType(String s){type=s;}}''',
    'com/xreal/glasses/servicesdk/NRServiceControl.java': '''package com.xreal.glasses.servicesdk;
      public class NRServiceControl {
        public interface OnStartSDKCallback {void onStartSDKResult(boolean ok);}
        public final Ping mPingHelper=new Ping();public volatile OnStartSDKCallback callback;
        public final java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
        public boolean isStartSdk,isEthernetSeries=true,hold,throwOnStart,modeResult=true;
        public int starts,stops,teardowns;public Runnable duringMode;
        public final java.util.List<Integer> modes=new java.util.ArrayList<>();
        public static class Ping {public final android.os.Handler mSdkHandler=new android.os.Handler();}
        public void startBackgroundSdk(OnStartSDKCallback cb){starts++;callback=cb;entered.countDown();
          if(throwOnStart)throw new IllegalStateException("native startup rejected");if(!hold)complete(true);}
        public void complete(boolean ok){isStartSdk=ok;callback.onStartSDKResult(ok);}
        public boolean set2D3D(int mode){
          if(isEthernetSeries&&dev.beampro.port.GlassesNetworkCompat.open!=1)throw new AssertionError("mode command lacks network lease");
          if(!isStartSdk)throw new AssertionError("mode command before SDK ready");modes.add(mode);
          if(duringMode!=null){Runnable r=duringMode;duringMode=null;r.run();}return modeResult;}
        public void stopSdk(){stops++;isStartSdk=false;mPingHelper.mSdkHandler.post(()->teardowns++);}
      }''',
    'ai/nreal/nebula/manager/XREALManager.java': '''package ai.nreal.nebula.manager;
      public class XREALManager {
        public static volatile XREALManager instance=new XREALManager();public int mGlassesType=6;
        public final com.xreal.glasses.servicesdk.NRServiceControl mNRServiceControl=new com.xreal.glasses.servicesdk.NRServiceControl();
        public final Ota mOtaManager=new Ota();public static XREALManager getInstance(){return instance;}
        public void setOtaManagerGlassesType(){}
        public void startSdk(Object callback){throw new AssertionError("Diagnostic entered the AR startup path");}
        public void goLauncher(){throw new AssertionError("Diagnostic launched Unity");}
        public static class Ota {public boolean bad;public int skuCalls;
          public String readFWVersion(int component){
            if(bad)return "unsupported firmware";
            if(instance.mGlassesType==2){if(component==1)return "07.1.02.387_20240428";if(component==2)return "1140";return "ROME_IA8201_REL_2122_9_16_Kn_indie";}
            return component==6?"1.9.1.20260914175625":component==1?"15.1.03.603_20260914":"15.A.01.097_20250430";}
          public String getSku(){skuCalls++;return "test-sku";}}
      }''',
})


class DisplayTestsTests(unittest.TestCase):
    def test_timed_sequence_fallback_cancellation_and_recovery(self):
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp,
                            str(ROOT / 'settings-ui/src/dev/beampro/settings/DisplayTestRun.java'),
                            str(ROOT / 'tools/DisplayTestRunTest.java')], check=True)
            output = subprocess.check_output([str(JAVA / 'bin/java'), '-cp', tmp,
                                               'dev.beampro.settings.DisplayTestRunTest'], text=True)
            self.assertIn('PASS guided display test assertions:', output)
            print(output.strip())

    def test_production_sdk_adapter_serializes_commands_and_cleanup(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, code in STUBS.items():
                target = work / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(code)
                sources.append(target)
            sources += [ROOT / 'port-client/src/dev/beampro/port' / name for name in
                        ('DisplayTestCompat.java', 'FirmwarePolicy.java')]
            sources += [ROOT / 'compat/src/dev/beampro/compat/GlassesIdentity.java', ROOT / 'tools/DisplayTestCompatTest.java']
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp, *map(str, sources)], check=True)
            output = subprocess.check_output([str(JAVA / 'bin/java'), '-cp', tmp,
                                              'dev.beampro.port.DisplayTestCompatTest'], text=True, timeout=20)
            self.assertIn('PASS display test SDK adapter assertions:', output)
            print(output.strip())


if __name__ == '__main__':
    unittest.main()
