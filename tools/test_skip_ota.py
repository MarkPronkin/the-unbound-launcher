"""Audit the real stock skip switch and forced-update paths using the pinned APK."""
from pathlib import Path
import hashlib
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib

from build_embedded_adb import BASE
from connection_startup import patch_connection_startup
from skip_ota import ROOT, patch_skip_ota

JAVA = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())


class SkipOtaTests(unittest.TestCase):
    def test_skip_decision_forced_and_queued_ui_keep_sku_and_flash_guards(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes.dex')
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp)
            # Exercise the same ordering as the production builder: the SDK entry
            # already belongs to the connection recovery adapter.
            connection = patch_connection_startup(original, work / 'connection', JAVA)
            patched = patch_skip_ota(connection, work / 'patch', JAVA)
            subprocess.run([str(JAVA / 'bin/java'), '--class-path',
                            str(ROOT / 'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar'),
                            str(ROOT / 'tools/VerifySkipOtaDex.java'), str(work / 'patch/output.dex')], check=True)
            self.assertEqual(patch_skip_ota(patched, work / 'restore', JAVA,
                                           restore=True, baseline=connection), connection)
            self.assertEqual(patch_connection_startup(connection, work / 'connection-restore', JAVA,
                                                      restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_skip_ota(original, work / 'without-worker', JAVA)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_skip_ota(patched, work / 'double', JAVA, baseline=connection)
            self.assertIn(b'XREALManager.java', patched)
            damaged = bytearray(patched.replace(b'XREALManager.java', b'XREALManageq.java', 1))
            damaged[12:32] = hashlib.sha1(damaged[32:]).digest()
            struct.pack_into('<I', damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_skip_ota(damaged, work / 'unrelated', JAVA, restore=True, baseline=connection)


if __name__ == '__main__':
    unittest.main()
