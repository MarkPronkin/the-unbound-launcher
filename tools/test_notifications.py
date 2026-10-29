"""Audit the notification DEX edit and reject unrelated changes on restoration."""
from pathlib import Path
import hashlib
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib

from build_embedded_adb import BASE
from notifications import patch_notifications

JAVA = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())


class NotificationTests(unittest.TestCase):
    def test_restore_exactly_and_reject_double_patch_and_unrelated_changes(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes2.dex')
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            patched = patch_notifications(original, work / 'patch', JAVA)
            self.assertNotEqual(patched, original)
            self.assertEqual(patch_notifications(patched, work / 'restore', JAVA,
                                                restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_notifications(patched, work / 'double', JAVA)
            damaged = bytearray(patched.replace(b'NotifyService.java', b'NotifyServicf.java', 1))
            self.assertNotEqual(damaged, patched)
            damaged[12:32] = hashlib.sha1(damaged[32:]).digest()
            struct.pack_into('<I', damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_notifications(damaged, work / 'unrelated', JAVA, restore=True, baseline=original)


if __name__ == '__main__':
    unittest.main()
