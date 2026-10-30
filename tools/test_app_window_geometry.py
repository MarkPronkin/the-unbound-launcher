"""Exercise buffer/window geometry and audit the extracted service's DEX hook."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
JADX = ROOT / 'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar'
STUBS = {
    'android/graphics/Point.java': 'package android.graphics; public class Point {public int x,y;}',
    'android/view/Display.java': '''package android.view; public class Display {
      public String name="com.xreal.virtualdisplay-com.android.documentsui/.LauncherActivity";
      public String unique="virtual:com.android.shell,2000,"+name+",0";
      public int width,height,rotation;public Mode mode;
      public String getName(){return name;}public String getUniqueId(){return unique;}
      public int getDisplayId(){return 23;}public int getRotation(){return rotation;}
      public void getRealSize(android.graphics.Point p){p.x=width;p.y=height;}
      public Mode getMode(){return mode;}public static class Mode {
        int w,h;public Mode(int width,int height){w=width;h=height;}
        public int getPhysicalWidth(){return w;}public int getPhysicalHeight(){return h;}}
    }''',
    'dev/beampro/bridge/ShellCommand.java': '''package dev.beampro.bridge; public class ShellCommand {
      static int calls,failure;static String output;
      static class Result {String output;boolean ok;Result(String text,boolean good){output=text;ok=good;}
        boolean succeeded(){return ok;}}
      static Result run(long timeout,String... command)throws Exception {
        calls++;if(timeout>1000||!java.util.Arrays.equals(command,new String[]{"dumpsys","-t","1","display"}))
          throw new AssertionError("Unexpected command");
        if(failure==2)throw new java.io.IOException("unavailable");
        if(failure==3)throw new InterruptedException();return new Result(output,failure==0);}
    }''',
}


class AppWindowGeometryTests(unittest.TestCase):
    def test_geometry_on_normal_and_fixed_rotation_devices(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            files = []
            for name, content in STUBS.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
                files.append(path)
            files += [ROOT / 'bridge/src/dev/beampro/bridge/AppWindowGeometry.java',
                      ROOT / 'tools/AppWindowGeometryTest.java']
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', str(work),
                            *map(str, files)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', str(work),
                                     'dev.beampro.bridge.AppWindowGeometryTest'], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS app window geometry assertions:', result.stdout)

    def test_extracted_service_hook_and_scope(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            baseline, patched = work / 'baseline.dex', work / 'server.dex'
            with zipfile.ZipFile(ROOT / 'artifacts/beam-pro-system/myglasses-dex.zip') as archive:
                baseline.write_bytes(archive.read('classes.dex'))
            java = [str(JAVA / 'bin/java'), '--class-path', str(JADX)]
            subprocess.run([*java, str(ROOT / 'tools/PatchLaunchDex.java'), 'server',
                            str(baseline), str(patched)], check=True)
            subprocess.run([*java, str(ROOT / 'tools/VerifyAppWindowGeometryDex.java'),
                            str(baseline), str(patched)], check=True)


if __name__ == '__main__':
    unittest.main()
