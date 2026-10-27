"""Exercise the real native mode cache that aborts on pre-Android-14 phones."""
from pathlib import Path
import struct
import sys
import unittest
import zipfile

from build_embedded_adb import BASE
from display_mode_compat import (LIBRARY, INITIALIZER, TARGET, CONTEXT,
                                 patch_display_mode, verify_display_mode)

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'artifacts/tools/python'))
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *


class DisplayModeCompatTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as archive:
            cls.original = archive.read(LIBRARY)
        cls.patched = patch_display_mode(cls.original)

    def test_exact_single_instruction_scope_and_full_restoration(self):
        self.assertEqual(self.original[TARGET - 20:TARGET + 8], CONTEXT)
        self.assertEqual(self.patched[:TARGET], self.original[:TARGET])
        self.assertEqual(self.patched[TARGET + 4:], self.original[TARGET + 4:])
        self.assertEqual(patch_display_mode(self.patched, restore=True), self.original)
        self.assertEqual(verify_display_mode(self.patched)['patch_bytes'], 4)
        with self.assertRaises(ValueError): patch_display_mode(self.patched)
        with self.assertRaises(ValueError): patch_display_mode(self.original, restore=True)
        for restore, value in ((False, self.original), (True, self.patched)):
            damaged = bytearray(value);damaged[-1] ^= 1
            with self.assertRaises(ValueError): patch_display_mode(damaged, restore=restore)

    def run_initializer(self, library, available):
        machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        machine.mem_map(0, (len(library) + 4095) & ~4095)
        machine.mem_write(0, library)
        machine.mem_map(0x8000000, 0x10000)
        stack, receiver, tls, end = 0x800f000, 0x8001000, 0x8002000, 0x8003000
        machine.mem_write(receiver, b'\xa5' * 0x60)
        machine.reg_write(UC_ARM64_REG_SP, stack)
        machine.reg_write(UC_ARM64_REG_X0, receiver)
        machine.reg_write(UC_ARM64_REG_LR, end)
        machine.reg_write(UC_ARM64_REG_TPIDR_EL0, tls)
        saved = {r: 0x1000 + r for r in (UC_ARM64_REG_X19, UC_ARM64_REG_X20,
                 UC_ARM64_REG_X21, UC_ARM64_REG_X22, UC_ARM64_REG_X23, UC_ARM64_REG_X24,
                 UC_ARM64_REG_X25, UC_ARM64_REG_X26, UC_ARM64_REG_X27, UC_ARM64_REG_X28,
                 UC_ARM64_REG_X29)}
        for r, value in saved.items(): machine.reg_write(r, value)
        calls, failed = [], []

        def string(address):
            raw = bytes(machine.mem_read(address, 100))
            return raw.split(b'\0', 1)[0].decode()

        def hook(uc, address, size, _):
            if address == 0x1E8A030:
                name = string(uc.reg_read(UC_ARM64_REG_X1))
                signature = string(uc.reg_read(UC_ARM64_REG_X2))
                calls.append((name, signature))
                if name not in available:
                    failed.append(name);uc.emu_stop();return
                uc.reg_write(UC_ARM64_REG_X0, 0x100 + len(calls))
            elif address == 0x1E8A66C:
                self.assertEqual(string(uc.reg_read(UC_ARM64_REG_X1)), 'android/view/Display$Mode')
                uc.mem_write(uc.reg_read(UC_ARM64_REG_X0), b'\0' * 32)
            elif address not in (0x1E88624, 0x1E88C64, 0x1E8C7A0):
                return
            uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_LR))

        machine.hook_add(UC_HOOK_CODE, hook)
        machine.emu_start(INITIALIZER, end, count=500)
        if not failed:
            self.assertEqual(machine.reg_read(UC_ARM64_REG_PC), end)
            self.assertEqual(machine.reg_read(UC_ARM64_REG_SP), stack)
            for r, value in saved.items(): self.assertEqual(machine.reg_read(r), value)
            self.assertEqual(struct.unpack('<5Q', machine.mem_read(receiver + 0x28, 40)),
                             (0x101, 0x102, 0x103, 0x104, 0))
        return calls, failed

    def test_original_initializer_reproduces_missing_method_but_patched_keeps_required_ids(self):
        required = {'getModeId', 'getPhysicalWidth', 'getPhysicalHeight', 'getRefreshRate'}
        calls, failed = self.run_initializer(self.original, required)
        self.assertEqual(failed, ['getSupportedHdrTypes'])
        self.assertEqual(calls[-1], ('getSupportedHdrTypes', '()[I'))
        for available in (required, required | {'getSupportedHdrTypes'}):
            calls, failed = self.run_initializer(self.patched, available)
            self.assertFalse(failed)
            self.assertEqual(calls, [('getModeId', '()I'), ('getPhysicalWidth', '()I'),
                                     ('getPhysicalHeight', '()I'), ('getRefreshRate', '()F')])

    def test_mode_cache_has_no_hdr_consumer(self):
        # Pin every ADRP-based reference to the complete static mode class cache,
        # including address materialization, not just reads of the last slot.
        # The sole consumer reads width, height and refresh; the other references
        # acquire/release its constructor guard, initialize it or register cleanup.
        reads, addresses = [], set()
        for at in range(0xB0FE80, 0x20B60E0, 4):
            word = struct.unpack_from('<I', self.original, at)[0]
            if word & 0x9F000000 != 0x90000000: continue
            register = word & 31
            immediate = ((word >> 29) & 3) | (((word >> 5) & 0x7FFFF) << 2)
            if immediate & 0x100000: immediate -= 0x200000
            if (at & ~4095) + (immediate << 12) != 0x2292000: continue
            for distance in range(1, 6):
                instruction = struct.unpack_from('<I', self.original, at + distance * 4)[0]
                if (instruction >> 5) & 31 != register: continue
                kind = instruction & 0xFFC00000
                if kind == 0xF9400000: offset = ((instruction >> 10) & 4095) * 8
                elif kind == 0x91000000: offset = (instruction >> 10) & 4095
                else: continue
                if 0x3F8 <= offset <= 0x448:
                    addresses.add(at + distance * 4)
                    if kind == 0xF9400000: reads.append(offset)
        self.assertEqual(reads, [0x438, 0x428, 0x430])
        self.assertEqual(len(addresses), 17)
        self.assertTrue(all(0x1827EE8 <= at < 0x1828218 for at in addresses))


if __name__ == '__main__':
    unittest.main()
