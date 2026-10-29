"""Exercise production Binder routing and watchdog restoration after EOF or stalled leases."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PhoneScreenWakeKeyTests(unittest.TestCase):
    def test_volume_recovery_does_not_require_or_consume_game_focus(self):
        java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip()) / "bin"
        with tempfile.TemporaryDirectory(prefix="unbound-screen-wake-") as tmp:
            subprocess.run([str(java / "javac"), "--release", "8", "-d", tmp,
                            str(ROOT / "bridge/src/dev/beampro/bridge/ShellCommand.java"),
                            str(ROOT / "bridge/src/dev/beampro/bridge/PhoneScreenWakeKeys.java"),
                            str(ROOT / "tools/PhoneScreenWakeKeysTest.java")], check=True, capture_output=True)
            result = subprocess.run([str(java / "java"), "-cp", tmp,
                                     "dev.beampro.bridge.PhoneScreenWakeKeysTest", tmp],
                                    check=True, capture_output=True, text=True, timeout=15)
            self.assertIn("PASS focus-independent volume recovery", result.stdout)


class PhoneScreenBinderTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="beam-phone-binder-")
        cls.work = Path(cls.temp.name)
        cls.java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
        cls.sdk = Path.home() / "Library/Android/sdk"
        cls.platform = cls.sdk / "platforms/android-34/android.jar"
        cls.env = dict(os.environ, JAVA_HOME=str(cls.java))
        cls.server_sources = [ROOT / "compat/src/dev/beampro/compat/GlassesIdentity.java"] + sorted((ROOT / "bridge/src").rglob("*.java")) + [
            ROOT / "settings-ui/src/dev/beampro/settings" / name
            for name in ("NetworkDiagnostics.java", "DisplayDiagnostics.java", "ControlTrace.java")]
        cls.server = cls.build(cls.server_sources, "server")
        cls.client = cls.build(sorted((ROOT / "port-client/src").rglob("*.java")) + [ROOT / "compat/src/dev/beampro/compat/GlassesIdentity.java"], "client")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    @classmethod
    def build(cls, sources, name, collision=False):
        work = cls.work / name
        classes = work / "classes"; classes.mkdir(parents=True)
        staged = []
        for source in sources:
            text = source.read_text()
            if source.name == "LaunchServer.java":
                # The bug was masked when USB permissions were disabled in the
                # development helper. Exercise the real production dispatch.
                text = text.replace("ENABLE_DISPLAY_MANAGEMENT = false", "ENABLE_DISPLAY_MANAGEMENT = true")
            if collision and source.name == "PhoneScreenPower.java":
                text = text.replace("TRANSACTION = 0x00FFFFF2", "TRANSACTION = 0x00FFFFF8")
            target = work / "src" / source.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text); staged.append(str(target))
        subprocess.run([str(cls.java / "bin/javac"), "--release", "8", "-cp", str(cls.platform),
                        "-d", str(classes), *staged], check=True, capture_output=True)
        jar = work / "classes.jar"
        subprocess.run([str(cls.java / "bin/jar"), "cf", str(jar), "-C", str(classes), "."], check=True, capture_output=True)
        dex = work / "dex"; dex.mkdir()
        subprocess.run([str(cls.sdk / "build-tools/36.0.0/d8"), "--min-api", "29", "--lib", str(cls.platform),
                        "--output", str(dex), str(jar)], check=True, capture_output=True, env=cls.env)
        return dex / "classes.dex"

    def verify(self, server):
        return subprocess.run([str(self.java / "bin/java"), "--class-path",
                               str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
                               str(ROOT / "tools/VerifyPhoneScreenDex.java"), str(server), str(self.client)],
                              capture_output=True, text=True, timeout=30)

    def test_production_helper_and_client_have_distinct_matching_routes(self):
        result = self.verify(self.server)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_original_usb_collision_is_rejected(self):
        server = self.build(self.server_sources, "collision", collision=True)
        result = self.verify(server)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Binder transaction collision", result.stderr)
        self.assertIn("PhoneScreenPower", result.stderr)
        self.assertIn("XrealUsbPermissions", result.stderr)


class PhoneScreenSurfaceTests(unittest.TestCase):
    def test_untitled_glasses_surface_is_measured_on_external_stack_only(self):
        from verify_phone_screen_runtime import surface_state
        snapshot = """
Composition list (top to bottom)
LayerStack=0
  Layer [1] SurfaceView[phone](BLAST)#1
    visible reason= buffer=1 frame=9999
LayerStack=14
  Layer [2] SurfaceView[](BLAST)#2
    visible reason= buffer=2 frame=180
Input list
LayerStack=14
  Layer [3] SurfaceView[stale](BLAST)#3
    visible reason= buffer=3 frame=99999
Displays (2 entries)
Display 100
    port=0
    connectionType=Internal
    powerMode=Off
Display 200
    port=123
    connectionType=External
    powerMode=On
Display 100 (physical, "Phone")
   layerFilter={layerStack=0 toInternalDisplay=true }
Display 200 (physical, "Glasses")
   layerFilter={layerStack=14 toInternalDisplay=false }
"""
        self.assertEqual(surface_state(snapshot), ({"100": "Off", "200": "On"}, 180))
        self.assertEqual(surface_state(snapshot.replace("frame=180", "no frame"))[1], -1)


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
                        str(ROOT / "compat/src/dev/beampro/compat/GlassesIdentity.java"),
                        str(ROOT / "bridge/src/dev/beampro/bridge/ShellCommand.java"),
                        str(ROOT / "bridge/src/dev/beampro/bridge/PhoneScreenWakeKeys.java"),
                        str(ROOT / "bridge/src/dev/beampro/bridge/PhoneScreenPower.java")], check=True, capture_output=True)
        stubs = {
            "dev/beampro/bridge/PhoneScreenWakeKeys.java": """package dev.beampro.bridge;
                final class PhoneScreenWakeKeys implements AutoCloseable {
                    static PhoneScreenWakeKeys start(Runnable wake) {
                        String delay = System.getProperty("test.volumeWakeMs");
                        if (delay != null) {
                            Thread key = new Thread(() -> {
                                try { Thread.sleep(Long.parseLong(delay)); wake.run(); }
                                catch (InterruptedException ignored) {}
                            });
                            key.setDaemon(true); key.start();
                        }
                        return new PhoneScreenWakeKeys();
                    }
                    public void close() { System.out.println("KEY_READERS_CLOSED"); }
                }""",
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

    def worker(self, volume_wake=False):
        options = ["-Dtest.volumeWakeMs=300"] if volume_wake else []
        worker = subprocess.Popen([str(self.java / "java"), *options, "-cp", self.classpath,
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
        self.assertEqual(output, "POWER 123456 2\nON\nKEY_READERS_CLOSED\n")

    def test_helper_death_eof_restores_panel(self):
        worker = self.worker(); self.off(worker); worker.stdin.close()
        code, output, error = self.finish_worker(worker)
        self.assertEqual(code, 0, error)
        self.assertEqual(output, "POWER 123456 2\nON\nKEY_READERS_CLOSED\n")

    def test_stalled_helper_lease_restores_without_stdin_eof(self):
        worker = self.worker(); self.off(worker)
        code, output, error = self.finish_worker(worker)
        worker.stdin.close()
        self.assertEqual(code, 0, error)
        self.assertEqual(output, "POWER 123456 2\nON\nKEY_READERS_CLOSED\n")

    def test_invalid_command_fails_with_panel_restored(self):
        worker = self.worker(); self.off(worker)
        worker.stdin.write("INVALID\n"); worker.stdin.close()
        code, output, error = self.finish_worker(worker)
        self.assertNotEqual(code, 0)
        self.assertEqual(output, "POWER 123456 2\nON\nKEY_READERS_CLOSED\n")
        self.assertIn("Invalid watchdog command", error)

    def test_failed_startup_without_off_does_not_change_panel_power(self):
        worker = self.worker(); worker.stdin.close()
        code, output, error = self.finish_worker(worker)
        self.assertEqual(code, 0, error)
        self.assertEqual(output, "ON\nKEY_READERS_CLOSED\n")

    def test_volume_callback_restores_panel_and_closes_key_readers(self):
        worker = self.worker(volume_wake=True); self.off(worker)
        code, output, error = self.finish_worker(worker)
        worker.stdin.close()
        self.assertEqual(code, 0, error)
        self.assertEqual(output, "POWER 123456 2\nON\nKEY_READERS_CLOSED\n")


if __name__ == "__main__":
    unittest.main()
