"""Exercise the real Android adapter with app-bound service factories unavailable."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_display_startup import ROOT, JAVA

SDK = Path.home() / 'Library/Android/sdk/platforms/android-34/android.jar'
STUBS = {
    'android/content/Context.java': '''package android.content; public class Context {
      public static int factoryCalls;
      public <T>T getSystemService(Class<T> cls){factoryCalls++;throw new IllegalStateException("ApplicationSharedMemory not initialized");}
      public ContentResolver getContentResolver(){throw new IllegalStateException("No app-bound resolver");}
    }''',
    'android/os/IBinder.java': 'package android.os; public interface IBinder {}',
    'android/os/ServiceManager.java': '''package android.os; public class ServiceManager {
      public static java.util.Map<String,IBinder> services=new java.util.HashMap<>();
      public static IBinder getService(String name){return services.get(name);}
    }''',
    'android/view/Display.java': '''package android.view; public class Display { public static class Mode {
      int width,height;float rate; public Mode(int w,int h,float hz){width=w;height=h;rate=hz;}
      public int getPhysicalWidth(){return width;}public int getPhysicalHeight(){return height;}
      public float getRefreshRate(){return rate;}
    }}''',
    'android/view/IWindowManager.java': '''package android.view; public interface IWindowManager extends android.os.IBinder {
      boolean isKeyguardLocked();public static class Stub {public static IWindowManager asInterface(android.os.IBinder b){return (IWindowManager)b;}}
    }''',
    'android/os/IThermalService.java': '''package android.os; public interface IThermalService extends IBinder {
      int getCurrentThermalStatus();public static class Stub {public static IThermalService asInterface(IBinder b){return (IThermalService)b;}}
    }''',
    'android/hardware/display/IDisplayManager.java': '''package android.hardware.display; import android.view.Display;
      public interface IDisplayManager extends android.os.IBinder {
        int[] getDisplayIds(boolean disabled);Object getDisplayInfo(int id);
        int getConnectionPreference(String unique);void setConnectionPreference(String unique,int choice);
        Display.Mode getUserPreferredDisplayMode(int id);void setUserPreferredDisplayMode(int id,Display.Mode m,boolean store);
        boolean shouldAlwaysRespectAppRequestedMode();void setShouldAlwaysRespectAppRequestedMode(boolean value);
        void enableConnectedDisplay(int id);
        public static class Stub {public static IDisplayManager asInterface(android.os.IBinder b){return (IDisplayManager)b;}}
    }''',
    'dev/beampro/bridge/LaunchServer.java': '''package dev.beampro.bridge; public class LaunchServer {
      public static android.content.Context createShellContext(){return new android.content.Context();}
    }''',
    'dev/beampro/bridge/ShellCommand.java': '''package dev.beampro.bridge; public class ShellCommand {
      interface Runner {Result run(String[] command)throws Exception;}static Runner runner;
      static class Result {int exitCode;boolean timedOut;String output;
        Result(int code,String text){exitCode=code;output=text;}
        boolean succeeded(){return !timedOut&&exitCode==0&&!output.contains("Exception:")&&!output.contains("Error:");}}
      static Result run(long timeout,String... command)throws Exception{return runner.run(command);}
    }'''
}


class ManagedDisplayPlatformTests(unittest.TestCase):
    def run_adapter(self, source):
        with tempfile.TemporaryDirectory() as tmp:
            work=Path(tmp);files=[]
            for name,contents in STUBS.items():
                path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(contents);files.append(path)
            files += [ROOT/"compat/src/dev/beampro/compat/GlassesIdentity.java",source, ROOT/'bridge/src/dev/beampro/bridge/ManagedDisplayPolicy.java',
                      ROOT/'bridge/src/dev/beampro/bridge/DisplaySnapshotReader.java',
                      ROOT/'tools/ManagedDisplayPlatformTest.java']
            subprocess.run([str(JAVA/'bin/javac'),'--release','8','-cp',str(SDK),'-d',str(work),*map(str,files)],check=True)
            return subprocess.run([str(JAVA/'bin/java'),'-cp',str(work)+':'+str(SDK),
                                   'dev.beampro.bridge.ManagedDisplayPlatformTest',
                                   str(ROOT/'artifacts/pixel-display-014-live-20261026/display.txt')],text=True,capture_output=True)

    def test_full_platform_without_app_shared_memory(self):
        result=self.run_adapter(ROOT/'bridge/src/dev/beampro/bridge/ManagedDisplay.java')
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertIn('PASS headless Android platform regressions:',result.stdout)

    def test_previous_adapter_reproduces_recorded_crash(self):
        result=self.run_adapter(ROOT/'artifacts/unbound-launcher-display-discovery/build/server-src/ManagedDisplay.java')
        self.assertNotEqual(result.returncode,0)
        self.assertIn('ApplicationSharedMemory not initialized',result.stderr)

    def test_guard_contracts_match_inspected_pixel_framework(self):
        base=ROOT/'artifacts/pixel-display-016-user-test-20261026/framework-source'
        self.assertIn('boolean isKeyguardLocked()', (base/'android/view/IWindowManager.java').read_text())
        self.assertIn('int getCurrentThermalStatus()', (base/'android/os/IThermalService.java').read_text())
        log=(ROOT/'artifacts/pixel-display-016-user-test-20261026/helper-log.txt').read_text()
        self.assertIn('AndroidPlatform.unlockedAndCool',log)
        self.assertIn('ApplicationSharedMemory not initialized',log)


if __name__=='__main__':unittest.main()
