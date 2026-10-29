"""Verify the actual stock DEX edits and reject changes beyond the intended hooks."""
import hashlib
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib

from build_embedded_adb import BASE
from input_compat import patch_input_compat

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'artifacts/tools/python'))
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_PC, UC_ARM64_REG_LR, UC_ARM64_REG_SP

JAVA = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())


class InputCompatTests(unittest.TestCase):
    def test_real_unity_keyboard_state_blocks_both_touch_input_modes(self):
        # Execute the existing Unity dispatcher, including its keyboard event
        # handlers. Only the monitor lock and aim-mode getter are stubbed.
        root = Path(__file__).resolve().parents[1]
        with zipfile.ZipFile(root / 'artifacts/unbound-launcher-0.1.2-alpha/the-unbound-launcher.apk') as archive:
            native = archive.read('lib/arm64-v8a/libil2cpp.so')
        for mode, handler in [(1, 0x3086738), (2, 0x3086C4C), (3, 0x3086C4C)]:
            with self.subTest(mode=mode):
                uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
                for page in (0x3086000, 0x23A2000, 0x25C4000, 0x8000000, 0x8100000, 0x8200000):
                    uc.mem_map(page, 4096)
                uc.mem_write(0x3086000, native[0x3086000:0x3087000])
                reached = []

                def hook(machine, address, size, user):
                    if address == 0x23A2D98:  # Monitor.Enter.
                        machine.reg_write(UC_ARM64_REG_PC, machine.reg_read(UC_ARM64_REG_LR))
                    elif address == 0x25C40E8:  # XRInput.GetControllerRayAimMode.
                        machine.reg_write(UC_ARM64_REG_X0, mode)
                        machine.reg_write(UC_ARM64_REG_PC, machine.reg_read(UC_ARM64_REG_LR))
                    elif address in (handler, 0x8200000):
                        reached.append(address)
                        machine.emu_stop()

                uc.hook_add(UC_HOOK_CODE, hook)

                def run(address, action=0):
                    reached.clear()
                    uc.reg_write(UC_ARM64_REG_SP, 0x8000800)
                    uc.reg_write(UC_ARM64_REG_X0, 0x8100000)
                    uc.reg_write(UC_ARM64_REG_X1, action)
                    uc.reg_write(UC_ARM64_REG_LR, 0x8200000)
                    uc.emu_start(address, 0, count=100)

                run(0x30861A4)  # OnInputKeyboardShow.
                self.assertEqual(uc.mem_read(0x810001D, 1), b'\x01')
                for action in (0, 2, 1):  # Down, move and up all disappear.
                    run(0x30865D8, action)
                    self.assertEqual(reached, [0x8200000])
                run(0x30861B0)  # OnInputKeyboardHide.
                self.assertEqual(uc.mem_read(0x810001D, 1), b'\x00')
                run(0x30865D8)
                self.assertEqual(reached, [handler])

    def test_both_dex_hooks_restore_exactly_and_reject_unrelated_edits(self):
        with zipfile.ZipFile(BASE) as archive, tempfile.TemporaryDirectory() as temp:
            for name, marker in [('classes.dex', b'InputDetectService.java'), ('classes2.dex', b'InputHelper.java')]:
                with self.subTest(dex=name):
                    original = archive.read(name)
                    work = Path(temp) / name
                    patched = patch_input_compat(original, work / 'patch', JAVA)
                    self.assertNotEqual(original, patched)
                    self.assertEqual(patch_input_compat(patched, work / 'restore', JAVA, restore=True, baseline=original), original)
                    with self.assertRaises(subprocess.CalledProcessError):
                        patch_input_compat(patched, work / 'double', JAVA, baseline=original)
                    self.assertIn(marker, patched)
                    damaged = bytearray(patched.replace(marker, marker[:-6] + b'X.java', 1))
                    damaged[12:32] = hashlib.sha1(damaged[32:]).digest()
                    struct.pack_into('<I', damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
                    with self.assertRaises(subprocess.CalledProcessError):
                        patch_input_compat(damaged, work / 'unrelated', JAVA, restore=True, baseline=original)


if __name__ == '__main__': unittest.main()
