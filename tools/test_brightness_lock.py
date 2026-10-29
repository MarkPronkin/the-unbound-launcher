"""Verify the brightness lock filter changes one observer call and restores exactly."""
from pathlib import Path
import hashlib
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib

from build_embedded_adb import BASE
from brightness_lock import patch_brightness_lock

JAVA = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())


class BrightnessLockTests(unittest.TestCase):
    def test_observer_restores_exactly_and_rejects_unrelated_edits(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes2.dex')
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp)
            patched = patch_brightness_lock(original, work / 'patch', JAVA)
            self.assertNotEqual(patched, original)
            self.assertIn(b'Ldev/beampro/port/BrightnessLockCompat;', patched)
            self.assertEqual(patch_brightness_lock(patched, work / 'restore', JAVA,
                                                   restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_brightness_lock(patched, work / 'double', JAVA)
            damaged = bytearray(patched.replace(b'ScreenBrightnessMonitor.java', b'ScreenBrightnessMonitox.java', 1))
            self.assertNotEqual(damaged, patched)
            damaged[12:32] = hashlib.sha1(damaged[32:]).digest()
            struct.pack_into('<I', damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_brightness_lock(damaged, work / 'unrelated', JAVA, restore=True, baseline=original)


if __name__ == '__main__': unittest.main()
