"""Pin the mode-switch crash fix to the real stock service and its exact DEX scope."""
from pathlib import Path
import hashlib
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib

from build_embedded_adb import BASE
from controller_mode import patch_controller_mode
from disconnect_audio import patch_disconnect_audio

JAVA = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())


class ControllerModeTests(unittest.TestCase):
    def test_pointer_flag_hook_preserves_service_and_all_unrelated_dex(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes2.dex')
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp)
            patched = patch_controller_mode(original, work / 'patch', JAVA)
            self.assertNotEqual(patched, original)
            self.assertIn(b'xreal_hide_pointicon', patched)
            self.assertEqual(patch_controller_mode(patched, work / 'restore', JAVA,
                                                  restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_controller_mode(patched, work / 'double', JAVA, baseline=original)
            damaged = bytearray(patched.replace(b'JobService.java', b'JobServicf.java', 1))
            damaged[12:32] = hashlib.sha1(damaged[32:]).digest()
            struct.pack_into('<I', damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_controller_mode(damaged, work / 'unrelated', JAVA, restore=True, baseline=original)

    def test_mode_hook_composes_with_existing_disconnect_fix(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes2.dex')
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp)
            audio = patch_disconnect_audio(original, work / 'audio', JAVA)
            patched = patch_controller_mode(audio, work / 'mode', JAVA)
            restored = patch_controller_mode(patched, work / 'restore-mode', JAVA, restore=True, baseline=audio)
            self.assertEqual(patch_disconnect_audio(restored, work / 'restore-audio', JAVA,
                                                   restore=True, baseline=original), original)


if __name__ == '__main__':
    unittest.main()
