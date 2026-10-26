"""Audit exact DEX composition and reject clients that would crash on missing hooks."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from build_settings_ui import BASELINE, BASELINE_SHA, sha256
from wireless_cast import ROOT, patch_wireless_cast_dex


class WirelessCastTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
        cls.env = dict(os.environ, JAVA_HOME=str(cls.java))
        if sha256(BASELINE) != BASELINE_SHA:
            raise ValueError("Unexpected casting regression baseline")
        with zipfile.ZipFile(BASELINE) as archive:
            cls.original = archive.read("classes.dex")
            cls.old_client = archive.read("classes4.dex")

    def test_cast_guards_restore_entire_original_dex_byte_for_byte(self):
        with tempfile.TemporaryDirectory(prefix="beam-wireless-") as directory:
            patched = patch_wireless_cast_dex(self.original, directory, self.java, self.env)
            self.assertNotEqual(patched, self.original)
            self.assertEqual((Path(directory) / "wireless-restored.dex").read_bytes(), self.original)

    def test_stale_client_without_capability_helper_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="beam-wireless-") as directory:
            client = Path(directory) / "old-client.dex"
            client.write_bytes(self.old_client)
            result = subprocess.run([
                str(self.java / "bin/java"), "--class-path",
                str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
                str(ROOT / "tools/PatchWirelessCastDex.java"), "verify-client", str(client), str(client),
            ], env=self.env, text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Missing or stale wireless cast client", result.stderr)


if __name__ == "__main__":
    unittest.main()
