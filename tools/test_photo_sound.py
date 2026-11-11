"""The photo sound DEX hook must round-trip exactly and reject unrelated edits."""
import hashlib
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib

from build_embedded_adb import BASE
from photo_sound import patch_photo_sound

JAVA = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())


class PhotoSoundTests(unittest.TestCase):
    def test_exact_restoration_and_rejection_of_double_patch_and_unrelated_changes(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes.dex')
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            patched = patch_photo_sound(original, work / 'patch', JAVA)
            self.assertNotEqual(original, patched)
            self.assertEqual(patch_photo_sound(patched, work / 'restore', JAVA, restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_photo_sound(patched, work / 'double', JAVA)
            damaged = bytearray(patched.replace(b'AirMouseFragment.java', b'AirMouseFragmenx.java', 1))
            self.assertNotEqual(damaged, patched)
            damaged[12:32] = hashlib.sha1(damaged[32:]).digest()
            struct.pack_into('<I', damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_photo_sound(damaged, work / 'unrelated', JAVA, restore=True, baseline=original)


if __name__ == '__main__':
    unittest.main()
