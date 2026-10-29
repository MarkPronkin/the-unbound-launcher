"""Exercise OnePlus Cast startup through the production adapter, with Android 16 APIs."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_display_startup import JAVA, ROOT
from test_managed_display_platform import SDK, STUBS


class LegacyDisplayStartupTests(unittest.TestCase):
    def run_platform(self, supported):
        stubs = dict(STUBS)
        enable = 'void enableConnectedDisplay(int id);' if supported else ''
        stubs['android/hardware/display/IDisplayManager.java'] = '''package android.hardware.display;
          public interface IDisplayManager extends android.os.IBinder {
            int[] getDisplayIds(boolean disabled);Object getDisplayInfo(int id);
            ENABLE
            public static class Stub {public static IDisplayManager asInterface(android.os.IBinder b){return (IDisplayManager)b;}}
          }'''.replace('ENABLE', enable)
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, contents in stubs.items():
                target = work / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(contents)
                sources.append(target)
            sources += [ROOT / 'bridge/src/dev/beampro/bridge' / name for name in
                        ('ManagedDisplay.java', 'ManagedDisplayPolicy.java', 'DisplaySnapshotReader.java')]
            sources.append(ROOT / 'tools/LegacyDisplayPlatformTest.java')
            sources.append(ROOT/"compat/src/dev/beampro/compat/GlassesIdentity.java")
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-cp', str(SDK),
                            '-d', str(work), *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', str(work) + ':' + str(SDK),
                                     'dev.beampro.bridge.LegacyDisplayPlatformTest',
                                     'supported' if supported else 'unsupported'], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS legacy connected-display regressions:', result.stdout)
            return result.stdout

    def test_android_16_cast_and_stereo_reconnect(self):
        self.assertIn('regressions: 21', self.run_platform(True))

    def test_framework_without_connected_display_enable_is_unchanged(self):
        self.assertIn('regressions: 8', self.run_platform(False))

    def test_contract_matches_copied_oneplus_framework(self):
        source = (ROOT / 'artifacts/oneplus-cast-startup-20261026/framework-source/'
                  'android/hardware/display/IDisplayManager.java').read_text()
        self.assertIn('void enableConnectedDisplay(int i)', source)
        self.assertIn('int[] getDisplayIds(boolean z)', source)
        self.assertIn('void setUserPreferredDisplayMode(int i, Display.Mode mode)', source)
        self.assertNotIn('getConnectionPreference(', source)
        self.assertNotIn('setConnectionPreference(', source)
        self.assertNotIn('Display.Mode mode, boolean', source)


if __name__ == '__main__':
    unittest.main()
