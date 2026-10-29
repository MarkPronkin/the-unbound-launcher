"""Execute the SDK's real GLES attribute builder and hardware-profile methods."""
from pathlib import Path
import struct
import sys
import unittest
import zipfile

from build_embedded_adb import BASE
from display_mode_compat import patch_display_mode
from egl_colorspace_compat import (LIBRARY, TARGET, patch_egl_colorspace,
                                   verify_egl_colorspace)
from xreal_telemetry import file_offset

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'artifacts/tools/python'))
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *

BUILDER = 0x14CAD80
VTABLES = (0x2126250, 0x2126358, 0x21263F8, 0x2126498, 0x2126538)


class EglColorspaceCompatTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as archive:
            cls.original = patch_display_mode(archive.read(LIBRARY))
        cls.fixed = patch_egl_colorspace(cls.original)

    def test_exact_instruction_scope_and_full_restoration(self):
        at = file_offset(self.original, TARGET)
        self.assertEqual(self.original[at:at + 8], bytes.fromhex('e0108652c0035fd6'))
        self.assertEqual(self.fixed[:at], self.original[:at])
        self.assertEqual(self.fixed[at + 4:], self.original[at + 4:])
        self.assertEqual(patch_egl_colorspace(self.fixed, restore=True), self.original)
        self.assertEqual(verify_egl_colorspace(self.fixed)['patch_bytes'], 4)
        for value, restore in ((self.fixed, False), (self.original, True)):
            with self.assertRaises(ValueError): patch_egl_colorspace(value, restore=restore)
        for value, restore in ((self.original, False), (self.fixed, True)):
            damaged = bytearray(value); damaged[-1] ^= 1
            with self.assertRaisesRegex(ValueError, 'Unexpected native library'):
                patch_egl_colorspace(damaged, restore=restore)

    def attributes(self, library, profile, single, protected, srgb):
        machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        machine.mem_map(0, 0x2400000)
        machine.mem_write(0, library)
        # Apply the actual ELF RELATIVE relocations for the hardware vtable.
        section_table = struct.unpack_from('<Q', library, 40)[0]
        size, count = struct.unpack_from('<HH', library, 58)
        for i in range(count):
            section = struct.unpack_from('<IIQQQQIIQQ', library, section_table + i * size)
            if section[1] != 4: continue
            for at in range(section[4], section[4] + section[5], section[9]):
                address, info, value = struct.unpack_from('<QQq', library, at)
                if info & 0xFFFFFFFF == 1027 and VTABLES[profile] <= address < VTABLES[profile] + 0x58:
                    machine.mem_write(address, struct.pack('<Q', value))
        machine.mem_map(0x8000000, 0x10000)
        stack, flags, output, provider, tls, end = (0x800F000, 0x8001000,
            0x8002000, 0x8003000, 0x8004000, 0x8005000)
        machine.mem_write(flags, bytes((single, protected, srgb)))
        machine.mem_write(output, b'\xA5' * 64)
        machine.mem_write(provider, struct.pack('<Q', VTABLES[profile]))
        # Already-initialized, disabled logging and hardware-provider cache.
        machine.mem_write(0x227EC20, b'\x01')
        machine.mem_write(0x227F060, b'\x01')
        machine.reg_write(UC_ARM64_REG_TPIDR_EL0, tls)
        machine.reg_write(UC_ARM64_REG_SP, stack)
        machine.reg_write(UC_ARM64_REG_LR, end)
        machine.reg_write(UC_ARM64_REG_X0, 0)
        machine.reg_write(UC_ARM64_REG_X1, 1)
        machine.reg_write(UC_ARM64_REG_X2, flags)
        machine.reg_write(UC_ARM64_REG_X3, output)
        saved = {r: 0x1000 + r for r in (UC_ARM64_REG_X19, UC_ARM64_REG_X20,
            UC_ARM64_REG_X21, UC_ARM64_REG_X22, UC_ARM64_REG_X23, UC_ARM64_REG_X24,
            UC_ARM64_REG_X25, UC_ARM64_REG_X26, UC_ARM64_REG_X27,
            UC_ARM64_REG_X28, UC_ARM64_REG_X29)}
        for register, value in saved.items(): machine.reg_write(register, value)

        def hook(uc, address, size, _):
            if address != 0x14AD58C: return
            # Select the real SDK profile; every attribute getter executes its
            # original native instructions through the real relocated vtable.
            uc.mem_write(uc.reg_read(UC_ARM64_REG_X8), struct.pack('<QQ', provider, 0))
            uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_LR))

        machine.hook_add(UC_HOOK_CODE, hook)
        machine.emu_start(BUILDER, end, count=1000)
        self.assertEqual(machine.reg_read(UC_ARM64_REG_PC), end)
        self.assertEqual(machine.reg_read(UC_ARM64_REG_SP), stack)
        for register, value in saved.items(): self.assertEqual(machine.reg_read(register), value)
        count = machine.reg_read(UC_ARM64_REG_W0)
        return list(struct.unpack('<' + 'I' * count, machine.mem_read(output, count * 4)))

    def test_actual_builder_corrects_gl_token_and_preserves_values_protection_and_buffering(self):
        for single in (False, True):
            for protected in (False, True):
                for srgb in (False, True):
                    with self.subTest(single=single, protected=protected, srgb=srgb):
                        original = self.attributes(self.original, 0, single, protected, srgb)
                        fixed = self.attributes(self.fixed, 0, single, protected, srgb)
                        expected = [0x3086, 0x3085 if single else 0x3084]
                        if protected: expected += [0x32C0, 1]
                        expected += [0x3087, 0x3089 if srgb else 0x308A, 0x3038, 0x3038]
                        self.assertEqual(original, expected)
                        expected[-4] = 0x309D
                        self.assertEqual(fixed, expected)

    def test_other_four_profiles_are_unchanged(self):
        for profile in range(1, 5):
            for srgb in (False, True):
                with self.subTest(profile=profile, srgb=srgb):
                    self.assertEqual(self.attributes(self.fixed, profile, False, False, srgb),
                                     self.attributes(self.original, profile, False, False, srgb))


if __name__ == '__main__': unittest.main()
