"""Run production screen controls with glasses and ordinary monitor identities."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_display_startup import JAVA, ROOT, STUBS


class ExternalDisplayControlsTests(unittest.TestCase):
    def test_glasses_identity_and_keep_awake_release(self):
        stubs = dict(STUBS)
        stubs['android/os/Handler.java'] = stubs['android/os/Handler.java'].replace(
            'public boolean postDelayed', 'public boolean post(Runnable r){return postDelayed(r,0);} public boolean postDelayed')
        stubs['android/app/Application.java'] = stubs['android/app/Application.java'].replace(
            'void onActivityResumed(Activity a);',
            'void onActivityResumed(Activity a);default void onActivityPostResumed(Activity a){}')
        stubs['android/os/SystemClock.java'] = stubs['android/os/SystemClock.java'].replace(
            'public static long uptimeMillis()', 'public static long elapsedRealtime(){return now;} public static long uptimeMillis()')
        stubs['android/view/Display.java'] = stubs['android/view/Display.java'].replace(
            'public static final int STATE_ON=2;',
            '''public static final int STATE_ON=2,DEFAULT_DISPLAY=0;
            public int type=2;public Product product=new Product();
            public int getType(){return type;}public Product getDeviceProductInfo(){return product;}
            public static class Product {public String manufacturer="MRG",id="1";
              public String getManufacturerPnpId(){return manufacturer;}public String getProductId(){return id;}}''')
        stubs['android/hardware/display/DisplayManager.java'] = stubs['android/hardware/display/DisplayManager.java'].replace(
            'public Display[] getDisplays()',
            '''public static final String DISPLAY_CATEGORY_PRESENTATION="presentation";
            public Display[] getDisplays(String category){return displays;}public Display[] getDisplays()''')
        stubs['android/view/WindowManager.java'] = '''package android.view;
            public class WindowManager {public static class LayoutParams {
              public static final int FLAG_KEEP_SCREEN_ON=128;public int flags,preferredDisplayModeId;}}'''
        stubs['android/view/View.java'] = '''package android.view; public class View {boolean keep;
            public boolean getKeepScreenOn(){return keep;}public void setKeepScreenOn(boolean value){keep=value;}}'''
        stubs['android/view/Window.java'] = '''package android.view;public class Window {
            WindowManager.LayoutParams attrs=new WindowManager.LayoutParams();View decor=new View();
            public View getDecorView(){return decor;}public void addFlags(int value){attrs.flags|=value;}
            public void clearFlags(int value){attrs.flags&=~value;}
            public WindowManager.LayoutParams getAttributes(){return attrs;}
            public void setAttributes(WindowManager.LayoutParams value){attrs=value;}}'''
        stubs['android/os/IBinder.java'] = '''package android.os;public interface IBinder {
            interface DeathRecipient {void binderDied();}void linkToDeath(DeathRecipient recipient,int flags);
            boolean unlinkToDeath(DeathRecipient recipient,int flags);boolean isBinderAlive();}'''
        stubs['ai/nreal/nebula/space/LaunchSpaceAcrivity.java'] = '''package ai.nreal.nebula.space;
            public class LaunchSpaceAcrivity extends android.app.Activity {}'''
        platform = Path.home() / 'Library/Android/sdk/platforms/android-34/android.jar'
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, contents in stubs.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(contents)
                sources.append(path)
            sources += [ROOT / name for name in (
                'port-client/src/dev/beampro/port/ArKeepAwake.java',
                'port-client/src/dev/beampro/port/DisplayStartupCompat.java',
                'bridge/src/dev/beampro/bridge/PhoneScreenPower.java',
                'bridge/src/dev/beampro/bridge/PhoneScreenWakeKeys.java',
                'bridge/src/dev/beampro/bridge/ShellCommand.java',
                'compat/src/dev/beampro/compat/GlassesIdentity.java',
                'tools/ExternalDisplayControlsTest.java')]
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-cp', str(platform),
                            '-d', str(work), *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', str(work) + ':' + str(platform),
                                     'dev.beampro.bridge.ExternalDisplayControlsTest'], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS glasses-only screen control assertions:', result.stdout)


if __name__ == '__main__':
    unittest.main()
