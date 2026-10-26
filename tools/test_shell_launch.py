"""Execute command-runner regressions using actual subprocesses and blocked output."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ShellLaunchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = tempfile.TemporaryDirectory(prefix="beampro-shell-launch-")
        cls.java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip()) / "bin"
        subprocess.run([
            str(cls.java / "javac"), "--release", "8", "-d", cls.work.name,
            str(ROOT / "bridge/src/dev/beampro/bridge/ShellCommand.java"),
            str(ROOT / "tools/ShellCommandTest.java"),
        ], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.work.cleanup()

    def check_mode(self, mode):
        result = subprocess.run([
            str(self.java / "java"), "-cp", self.work.name,
            "dev.beampro.bridge.ShellCommandTest", mode,
        ], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_normal_launch(self):
        self.check_mode("success")

    def test_rejected_launch_does_not_break_next_launch(self):
        self.check_mode("rejections")

    def test_timeout_does_not_break_next_launch(self):
        self.check_mode("timeout")

    def test_output_is_drained_and_bounded(self):
        self.check_mode("noisy")

    def test_missing_command_does_not_break_next_launch(self):
        self.check_mode("missing")


if __name__ == "__main__":
    unittest.main()
