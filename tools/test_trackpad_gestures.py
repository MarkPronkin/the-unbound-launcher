"""Exercise durable temporary OEM gesture settings without modifying the host."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class TrackpadGestureTests(unittest.TestCase):
    def test_settings_lease_and_recovery(self):
        java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
        with tempfile.TemporaryDirectory() as out:
            subprocess.run([str(java / 'bin/javac'), '--release', '8', '-d', out,
                str(ROOT / 'bridge/src/dev/beampro/bridge/TrackpadGesturePolicy.java'),
                str(ROOT / 'tools/TrackpadGesturePolicyTest.java')], check=True)
            result = subprocess.check_output([str(java / 'bin/java'), '-cp', out,
                                              'TrackpadGesturePolicyTest'], text=True)
            self.assertIn('PASS:', result)
            print(result.strip())


class TrackpadGestureCapabilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='unbound-oplus-capability-')
        cls.work = Path(cls.temp.name)
        cls.java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
        cls.platform = Path.home() / 'Library/Android/sdk/platforms/android-34/android.jar'
        cls.production = cls.work / 'production'
        cls.production.mkdir()
        # Compile the real sources against Android, then supply only the runtime
        # fixtures needed to execute their installation/capability decisions.
        subprocess.run([str(cls.java / 'bin/javac'), '--release', '8', '-cp', str(cls.platform),
            '-d', str(cls.production),
            str(ROOT / 'port-client/src/dev/beampro/port/TrackpadGestureCompat.java'),
            *[str(ROOT / 'bridge/src/dev/beampro/bridge' / name) for name in
              ('TrackpadGestures.java', 'TrackpadGesturePolicy.java', 'ShellCommand.java')]],
            check=True, capture_output=True)
        fixtures = {
            'android/content/Context.java': '''package android.content;
                public class Context { public Context getApplicationContext() { return this; } }''',
            'android/app/Application.java': '''package android.app;
                public class Application extends android.content.Context {
                    public interface ActivityLifecycleCallbacks {}
                    public int registrations;
                    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callbacks) { registrations++; }
                }''',
            'android/os/Looper.java': '''package android.os;
                public class Looper { public static Looper getMainLooper() { return new Looper(); } }''',
            'android/os/Handler.java': '''package android.os;
                public class Handler { public Handler(Looper looper) {} }''',
            'android/os/Build.java': '''package android.os;
                public class Build { public static String MANUFACTURER, BRAND; }''',
        }
        paths = []
        for name, source in fixtures.items():
            path = cls.work / 'src' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            paths.append(str(path))
        cls.runtime = cls.work / 'runtime'
        subprocess.run([str(cls.java / 'bin/javac'), '--release', '8', '-cp',
            str(cls.production) + ':' + str(cls.platform), '-d', str(cls.runtime),
            *paths, str(ROOT / 'tools/TrackpadGestureCapabilityTest.java')],
            check=True, capture_output=True)
        source = cls.work / 'oplus-src/android/os/OplusGestureMonitorManager.java'
        source.parent.mkdir(parents=True)
        source.write_text('package android.os; public final class OplusGestureMonitorManager {}')
        cls.oplus = cls.work / 'oplus'
        subprocess.run([str(cls.java / 'bin/javac'), '--release', '8', '-d', str(cls.oplus), str(source)],
                       check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_capability(self, maker, framework):
        paths = [self.runtime, self.production, self.platform]
        if framework: paths.insert(0, self.oplus)
        result = subprocess.run([str(self.java / 'bin/java'), '-cp', ':'.join(map(str, paths)),
            'TrackpadGestureCapabilityTest', maker, str(framework).lower()],
            text=True, capture_output=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS ' + maker, result.stdout)
        print(result.stdout.strip())

    def test_shared_framework_enables_oppo_oneplus_realme_and_other_branding(self):
        for maker in ('OPPO', 'OnePlus', 'realme', 'Other Oplus device'):
            with self.subTest(maker=maker): self.run_capability(maker, True)

    def test_brand_alone_cannot_enable_changes_without_the_framework(self):
        for maker in ('OPPO', 'Google'):
            with self.subTest(maker=maker): self.run_capability(maker, False)


if __name__ == '__main__':
    unittest.main()
