"""Decode real kernel socket-table formats and exercise the change-only control trace."""
import subprocess
import tempfile
import unittest

from test_display_startup import ROOT, JAVA


class ControlTraceTests(unittest.TestCase):
    def test_kernel_tables_change_only_sampling_and_budgets(self):
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run([str(JAVA / "bin/javac"), "--release", "8", "-d", tmp,
                            str(ROOT / "settings-ui/src/dev/beampro/settings/ControlTrace.java"),
                            str(ROOT / "tools/ControlTraceTest.java")], check=True)
            result = subprocess.run([str(JAVA / "bin/java"), "-cp", tmp, "dev.beampro.settings.ControlTraceTest"],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("PASS control trace assertions", result.stdout)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
