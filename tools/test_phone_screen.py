"""Exercise the real independent watchdog protocol, including EOF and stalled leases."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PhoneScreenWatchdogTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="beam-phone-screen-")
        cls.work = Path(cls.temp.name)
        cls.java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip()) / "bin"
        platform = Path.home() / "Library/Android/sdk/platforms/android-34/android.jar"
        classes = cls.work / "production"
        classes.mkdir()
        subprocess.run([str(cls.java / "javac"), "--release", "8", "-cp", str(platform), "-d", str(classes),
                        str(ROOT / "bridge/src/dev/beampro/bridge/PhoneScreenPower.java")], check=True, capture_output=True)
        stubs = {
            "android/os/Process.java": "package android.os; public class Process { public static int myUid(){return 2000;} }",
            "android/os/IBinder.java": "package android.os; public interface IBinder {}",
            "android/view/SurfaceControl.java": """package android.view;
                public class SurfaceControl {
                    static class Token implements android.os.IBinder { final long id; Token(long id){this.id=id;} }
                    public static android.os.IBinder getPhysicalDisplayToken(long id){return new Token(id);}
                    public static void setDisplayPowerMode(android.os.IBinder panel, int mode){
                        System.out.println("POWER " + ((Token)panel).id + " " + mode);
                    }
                }""",
        }
        paths = []
        for name, code in stubs.items():
            path = cls.work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(code)
            paths.append(str(path))
        subprocess.run([str(cls.java / "javac"), "--release", "8", "-d", str(cls.work), *paths], check=True)
        cls.classpath = os.pathsep.join(map(str, [cls.work, classes, platform]))

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def worker(self):
        worker = subprocess.Popen([str(self.java / "java"), "-cp", self.classpath,
                                   "dev.beampro.bridge.PhoneScreenPower$Watchdog", "123456"],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.addCleanup(lambda: worker.poll() is None and worker.kill())
        self.assertEqual(worker.stdout.readline().strip(), "READY")
        return worker

    def off(self, worker):
        worker.stdin.write("OFF\n"); worker.stdin.flush()
        self.assertEqual(worker.stdout.readline().strip(), "POWER 123456 0")
        self.assertEqual(worker.stdout.readline().strip(), "OFF")

    def finish_worker(self, worker):
        worker.wait(timeout=11)
        output = worker.stdout.read()
        error = worker.stderr.read()
        worker.stdout.close(); worker.stderr.close()
        return worker.returncode, output, error

    def test_normal_restore_targets_only_requested_phone_panel(self):
        worker = self.worker(); self.off(worker)
        worker.stdin.write("PING\nON\n"); worker.stdin.close()
        code, output, error = self.finish_worker(worker)
        self.assertEqual(code, 0, error)
        self.assertEqual(output, "POWER 123456 2\nON\n")

    def test_helper_death_eof_restores_panel(self):
        worker = self.worker(); self.off(worker); worker.stdin.close()
        code, output, error = self.finish_worker(worker)
        self.assertEqual(code, 0, error)
        self.assertEqual(output, "POWER 123456 2\nON\n")

    def test_stalled_helper_lease_restores_without_stdin_eof(self):
        worker = self.worker(); self.off(worker)
        code, output, error = self.finish_worker(worker)
        worker.stdin.close()
        self.assertEqual(code, 0, error)
        self.assertEqual(output, "POWER 123456 2\nON\n")

    def test_invalid_command_fails_with_panel_restored(self):
        worker = self.worker(); self.off(worker)
        worker.stdin.write("INVALID\n"); worker.stdin.close()
        code, output, error = self.finish_worker(worker)
        self.assertNotEqual(code, 0)
        self.assertEqual(output, "POWER 123456 2\nON\n")
        self.assertIn("Invalid watchdog command", error)

    def test_failed_startup_without_off_does_not_change_panel_power(self):
        worker = self.worker(); worker.stdin.close()
        code, output, error = self.finish_worker(worker)
        self.assertEqual(code, 0, error)
        self.assertEqual(output, "ON\n")


if __name__ == "__main__":
    unittest.main()
