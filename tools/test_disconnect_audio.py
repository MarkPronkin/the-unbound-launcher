"""Audit the real disconnect task that crashed the Pixel's service on USB detach."""
from pathlib import Path
import hashlib
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib

from build_embedded_adb import BASE
from disconnect_audio import patch_disconnect_audio

JAVA = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())


class DisconnectAudioTests(unittest.TestCase):
    def test_only_protected_audio_hint_changes_and_exact_restoration_rejects_other_edits(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes2.dex')
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp)
            patched = patch_disconnect_audio(original, work / 'patch', JAVA)
            self.assertNotEqual(patched, original)
            self.assertEqual(patch_disconnect_audio(patched, work / 'restore', JAVA,
                                                   restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_disconnect_audio(patched, work / 'double', JAVA, baseline=original)
            self.assertIn(b'JobService.java', patched)
            damaged = bytearray(patched.replace(b'JobService.java', b'JobServicf.java', 1))
            damaged[12:32] = hashlib.sha1(damaged[32:]).digest()
            struct.pack_into('<I', damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_disconnect_audio(damaged, work / 'unrelated', JAVA,
                                       restore=True, baseline=original)


if __name__ == '__main__':
    unittest.main()
