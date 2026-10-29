"""Verify automatic entry's caller, hardware, lock, cancellation and deduplication boundaries."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_display_startup import ROOT, JAVA
from test_usb_permission import STUBS


class AutoArEntryTests(unittest.TestCase):
    def test_production_policy(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, code in STUBS.items():
                p = work / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(code)
                sources.append(p)
            sources += [ROOT / 'bridge/src/dev/beampro/bridge' / name for name in
                        ('AutoArEntry.java', 'XrealUsbPermissions.java', 'ShellCommand.java')]
            sources.append(ROOT / 'tools/AutoArEntryTest.java')
            sources.append(ROOT/"compat/src/dev/beampro/compat/GlassesIdentity.java")
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', str(work), *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', str(work), 'dev.beampro.bridge.AutoArEntryTest'],
                                    text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS automatic AR entry assertions:', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
