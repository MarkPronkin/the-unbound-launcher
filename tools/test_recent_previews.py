"""Run the real capture/cache against legacy and Android 17 Binder contracts."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
JAVA=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip())/'bin'
STUBS={
 'android/os/IBinder.java':'package android.os;public interface IBinder {}',
 'android/os/ServiceManager.java':'''package android.os;public class ServiceManager {
 public static java.util.Map<String,IBinder> services=new java.util.HashMap<>();public static IBinder getService(String name){return services.get(name);}}''',
 'android/content/ComponentName.java':'package android.content;public class ComponentName {}',
 'android/content/Intent.java':'package android.content;public class Intent {public ComponentName getComponent(){return new ComponentName();}}',
 'android/content/pm/PackageManager.java':'package android.content.pm;public class PackageManager {public android.content.Intent getLaunchIntentForPackage(String name){return new android.content.Intent();}}',
 'android/content/Context.java':'''package android.content;public class Context {
 public static int factoryCalls;public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
 public <T>T getSystemService(Class<T> type){factoryCalls++;throw new AssertionError("App-bound service cache used");}}''',
 'android/view/WindowManagerGlobal.java':'''package android.view;public class WindowManagerGlobal {
 public static Object getWindowManagerService(){throw new AssertionError("ApplicationSharedMemory not initialized");}}''',
 'android/hardware/display/IDisplayManager.java':'''package android.hardware.display;public interface IDisplayManager extends android.os.IBinder {
 Object getDisplayInfo(int id);public static class Stub {public static IDisplayManager asInterface(android.os.IBinder b){return (IDisplayManager)b;}}}''',
 'android/hardware/HardwareBuffer.java':'''package android.hardware;public class HardwareBuffer implements AutoCloseable {
 public static int closed;boolean done;public void close(){if(done)throw new AssertionError("double close");done=true;closed++;}}''',
 'android/graphics/Bitmap.java':'''package android.graphics;import java.io.*;public class Bitmap {
 public enum Config {ARGB_8888,HARDWARE}public enum CompressFormat {JPEG}
 public static int hardwareRecycled,softwareRecycled,hardwareCompressed;public static boolean failCopy,large;boolean hardware,recycled;
 public Bitmap(boolean h){hardware=h;}public Bitmap copy(Config config,boolean mutable){if(failCopy)return null;if(config!=Config.ARGB_8888||mutable)throw new AssertionError();return new Bitmap(false);}
 public boolean compress(CompressFormat format,int quality,OutputStream output){if(hardware){hardwareCompressed++;throw new AssertionError();}try{output.write(large?new byte[262145]:new byte[]{-1,-40,1,2,3,-1,-39});return true;}catch(IOException e){throw new RuntimeException(e);}}
 public void recycle(){if(recycled)throw new AssertionError("double recycle");recycled=true;if(hardware)hardwareRecycled++;else softwareRecycled++;}public int getWidth(){return 400;}public int getHeight(){return 240;}}'''
}

CAPTURE='''package android.window;import android.graphics.Bitmap;import android.hardware.HardwareBuffer;
public class ROOT_CAPTURE {
 public static class CaptureArgs {
  public float scale;public boolean captureSecure,allowProtected;public int securePolicy,protectedPolicy;
  CaptureArgs(Builder b){scale=b.scale;}
  public static class Builder {float scale=1;public Builder setFrameScale(float value){scale=value;return this;}public CaptureArgs build(){return new CaptureArgs(this);}}
 }
 public static class ScreenshotHardwareBuffer {
  HardwareBuffer buffer=new HardwareBuffer();public boolean secure,noBitmap;
  public ScreenshotHardwareBuffer(boolean s,boolean n){secure=s;noBitmap=n;}
  public HardwareBuffer getHardwareBuffer(){return buffer;}public boolean containsSecureLayers(){return secure;}
  public Bitmap asBitmap(){return noBitmap?null:new Bitmap(true);}
 }
 public static class ScreenCaptureListener {public ScreenshotHardwareBuffer result;}
 public static abstract class SynchronousScreenCaptureListener extends ScreenCaptureListener {public abstract ScreenshotHardwareBuffer getBuffer();}
 public static SynchronousScreenCaptureListener createSyncCaptureListener(){return new SynchronousScreenCaptureListener(){public ScreenshotHardwareBuffer getBuffer(){return result;}};}
}'''

WINDOWS='''package android.view;import android.window.ROOT_CAPTURE;
public interface IWindowManager extends android.os.IBinder {
 void captureDisplay(int id,ROOT_CAPTURE.CaptureArgs args,ROOT_CAPTURE.ScreenCaptureListener listener);
 public static class Stub {public static IWindowManager asInterface(android.os.IBinder b){return (IWindowManager)b;}}
}'''

SERVICE='''package dev.beampro.bridge;import android.window.ROOT_CAPTURE;
public class TestWindows implements android.view.IWindowManager {
 int calls;float scale;boolean noResult,fail,secure,noBitmap;Runnable during;
 public void captureDisplay(int id,ROOT_CAPTURE.CaptureArgs args,ROOT_CAPTURE.ScreenCaptureListener listener){
  calls++;if(id!=17||args.captureSecure||args.allowProtected||args.securePolicy!=0||args.protectedPolicy!=0)throw new AssertionError("Unexpected capture request");
  if(fail)throw new SecurityException("capture denied");scale=args.scale;
  if(!noResult)listener.result=new ROOT_CAPTURE.ScreenshotHardwareBuffer(secure,noBitmap);if(during!=null)during.run();
 }
}'''


class RecentPreviewTests(unittest.TestCase):
 def check_api(self,root):
  with tempfile.TemporaryDirectory() as tmp:
   p=Path(tmp);sources=[];stubs=dict(STUBS)
   stubs[f'android/window/{root}.java']=CAPTURE.replace('ROOT_CAPTURE',root)
   if root=='ScreenCaptureInternal':stubs['android/window/ScreenCapture.java']='package android.window;public class ScreenCapture {}'
   stubs['android/view/IWindowManager.java']=WINDOWS.replace('ROOT_CAPTURE',root)
   stubs['dev/beampro/bridge/TestWindows.java']=SERVICE.replace('ROOT_CAPTURE',root)
   for name,text in stubs.items():
    path=p/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text);sources.append(path)
   sources += [ROOT/'bridge/src/dev/beampro/bridge'/name for name in ['HeadlessScreenCapture.java','RecentAppPreviews.java','RecentAppsStore.java']]
   sources.append(ROOT/'tools/RecentAppPreviewsTest.java')
   subprocess.run([str(JAVA/'javac'),'--release','8','-d',str(p),*map(str,sources)],check=True)
   state=p/'state';state.mkdir()
   result=subprocess.run([str(JAVA/'java'),'-cp',str(p),'dev.beampro.bridge.RecentAppPreviewsTest',str(state),root],text=True,capture_output=True)
   self.assertEqual(result.returncode,0,result.stdout+result.stderr)
   self.assertIn('PASS preview regressions:',result.stdout)
   self.assertIn('BEAM_RECENT_CAPTURE_API android.window.'+root,result.stdout)
   print(result.stdout.splitlines()[-1])

 def test_legacy_capture_api(self):self.check_api('ScreenCapture')
 def test_android17_internal_capture_api_without_legacy_builder(self):self.check_api('ScreenCaptureInternal')

 def test_recorded_pixel_failure_matches_framework_rename(self):
  log=(ROOT/'artifacts/pixel-recents-20261026/helper-log.txt').read_text()
  self.assertIn('ClassNotFoundException: android.window.ScreenCapture$CaptureArgs$Builder',log)
  window=(ROOT/'artifacts/pixel-display-016-user-test-20261026/framework-source/android/view/IWindowManager.java').read_text()
  self.assertIn('void captureDisplay(int i, ScreenCaptureInternal.CaptureArgs',window)
  public=(ROOT/'artifacts/pixel-recents-20261026/framework-source/android/window/ScreenCapture.java').read_text()
  self.assertNotIn('class CaptureArgs',public)


if __name__=='__main__':unittest.main()
