"""Exercise real bounded dump processes and ZIP budgets; Android checks cover pipe transport."""
import subprocess
import tempfile
import unittest
from test_display_startup import ROOT, JAVA


class DisplayDiagnosticsTests(unittest.TestCase):
    def test_fixed_commands_partial_failures_timeouts_and_output_budgets(self):
        with tempfile.TemporaryDirectory() as tmp:
            sources = [ROOT / 'settings-ui/src/dev/beampro/settings' / name for name in
                       ('DisplayDiagnostics.java', 'NetworkDiagnostics.java', 'ControlTrace.java')]
            sources.append(ROOT / 'tools/DisplayDiagnosticsTest.java')
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp, *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp, 'dev.beampro.settings.DisplayDiagnosticsTest'],
                                    text=True, capture_output=True, check=True, timeout=20)
            self.assertIn('PASS display diagnostic assertions', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
