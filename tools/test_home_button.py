"""Verify Home cancellation hooks compose with the complete controller DEX and restore exactly."""
from pathlib import Path
import hashlib
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib

from build_embedded_adb import BASE
from home_button import patch_home_button

JAVA = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())


class HomeButtonTests(unittest.TestCase):
    def test_all_modes_restore_exactly_and_reject_unrelated_edits(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes.dex')
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp)
            patched = patch_home_button(original, work / 'patch', JAVA)
            self.assertNotEqual(patched, original)
            self.assertEqual(patch_home_button(patched, work / 'restore', JAVA,
                                               restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_home_button(patched, work / 'double', JAVA)
            damaged = bytearray(patched.replace(b'AirMouseFragment.java', b'AirMouseFragmenx.java', 1))
            self.assertNotEqual(damaged, patched)
            damaged[12:32] = hashlib.sha1(damaged[32:]).digest()
            struct.pack_into('<I', damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_home_button(damaged, work / 'unrelated', JAVA, restore=True, baseline=original)


if __name__ == '__main__': unittest.main()
