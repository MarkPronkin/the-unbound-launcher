"""Exercise persistent launcher history with real file writes and helper restarts."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class RecentAppsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = tempfile.TemporaryDirectory(prefix="beampro-recents-classes-")
        cls.java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip()) / "bin"
        subprocess.run([
            str(cls.java / "javac"), "--release", "8", "-d", cls.work.name,
            str(ROOT / "bridge/src/dev/beampro/bridge/RecentAppsStore.java"),
            str(ROOT / "tools/RecentAppsStoreTest.java"),
        ], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.work.cleanup()

    def check_mode(self, mode):
        with tempfile.TemporaryDirectory(prefix="beampro-recents-state-") as state:
            result = subprocess.run([
                str(self.java / "java"), "-cp", self.work.name,
                "dev.beampro.bridge.RecentAppsStoreTest", mode, state,
            ], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_newest_first_deduplication_and_persistence(self):
        self.check_mode("order")

    def test_bounded_history(self):
        self.check_mode("limit")

    def test_dismissal_persists_and_reopening_restores_entry(self):
        self.check_mode("dismiss")

    def test_corrupt_file_does_not_break_helper(self):
        self.check_mode("corrupt")

    def test_invalid_record_does_not_mutate_history(self):
        self.check_mode("invalid")


if __name__ == "__main__":
    unittest.main()
