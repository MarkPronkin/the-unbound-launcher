"""Exercise passive startup retention, timeout boundaries, restart and UTF-8 framing."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_display_startup import ROOT, JAVA


class StartupTraceTests(unittest.TestCase):
    def test_retained_first_failure_and_streaming_log_boundaries(self):
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run([str(JAVA / "bin/javac"), "--release", "8", "-d", tmp,
                            str(ROOT / "settings-ui/src/dev/beampro/settings/StartupTrace.java"),
                            str(ROOT / "tools/StartupTraceTest.java")], check=True)
            result = subprocess.run([str(JAVA / "bin/java"), "-cp", tmp,
                                     "dev.beampro.settings.StartupTraceTest", str(Path(tmp) / "data")],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
