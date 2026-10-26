"""Check the native default against the actual M preset and preserve patch scope."""
import struct
import unittest
import zipfile

from app_limit import patch_app_limit, verify_app_limit
from build_port_bootstrap import SOURCE
from icon_placement import patch_icon_placement
from quick_access import patch_quick_access_library
from window_size import (DEFAULT_FOV_PERCENT, TARGETS, has_window_size_patch,
                         patch_window_size, restore_window_size)
from xreal_telemetry import LIBRARY, file_offset, patch_library, verify_library


def float_immediate(word):
    """Decode AArch64 FMOV (scalar, immediate), including its destination."""
    if word & 0xFFE01FE0 != 0x1E201000:
        raise AssertionError("Expected FMOV S register, immediate")
    immediate = (word >> 13) & 0xFF
    sign, exponent_bit = immediate >> 7, (immediate >> 6) & 1
    exponent = ((exponent_bit ^ 1) << 7) | (0x7C if exponent_bit else 0) | ((immediate >> 4) & 3)
    bits = (sign << 31) | (exponent << 23) | ((immediate & 15) << 19)
    return word & 31, struct.unpack("<f", struct.pack("<I", bits))[0]


class WindowSizeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(SOURCE) as archive:
            cls.original = archive.read(LIBRARY)
        cls.baseline = patch_icon_placement(patch_quick_access_library(
            patch_app_limit(patch_library(cls.original)[0])[0])[0])[0]

    def test_default_matches_native_m_preset_and_stores_correct_fields(self):
        # The adjustment proxy initializes M and L from this pinned vector.
        self.assertEqual(self.original[0x2D0D318:0x2D0D31C].hex(), "41edc33d")
        self.assertEqual(self.original[0x2D0D340:0x2D0D344].hex(), "0181813c")
        native_m, native_l = struct.unpack_from("<2f", self.original, 0x3606FB0)
        self.assertEqual(DEFAULT_FOV_PERCENT, native_m)
        self.assertLess(native_m, native_l)
        patched, _ = patch_window_size(self.baseline)
        registers = {}
        for target in TARGETS[:2]:
            offset = file_offset(patched, target["address"])
            register, value = float_immediate(struct.unpack_from("<I", patched, offset)[0])
            registers[register] = value
        store = struct.unpack("<I", bytes.fromhex(TARGETS[2]["replacement"]))[0]
        self.assertEqual(store & 0xFFC00000, 0x2D000000)  # STP S, signed offset
        base, first, second = (store >> 5) & 31, store & 31, (store >> 10) & 31
        offset = ((store >> 15) & 0x7F) * 4
        self.assertEqual(base, 20)  # preference object retained in x20
        self.assertEqual({offset: registers[first], offset + 4: registers[second]},
                         {0x1C: 1.0, 0x20: native_m})

    def test_only_create_branch_changes_and_existing_fixes_survive(self):
        patched, targets = patch_window_size(self.baseline)
        self.assertEqual(len(targets), 3)
        self.assertTrue(has_window_size_patch(patched))
        self.assertFalse(has_window_size_patch(self.baseline))
        self.assertEqual(restore_window_size(patched), self.baseline)
        self.assertEqual(verify_app_limit(patched), 10)
        self.assertEqual(verify_library(patched), 40)
        # Found preferences and createIfNotExist=false skip the entire patched
        # initializer; neither persisted choices nor live resizing are changed.
        for address in (0x186DA40, 0x186DA44):
            offset = file_offset(patched, address)
            self.assertEqual(patched[offset:offset + 4], self.original[offset:offset + 4])
            word = struct.unpack_from("<I", patched, offset)[0]
            immediate = (word >> 5) & 0x3FFF
            if immediate & 0x2000:
                immediate -= 0x4000
            self.assertEqual(address + immediate * 4, 0x186DAE0)

    def test_rejects_wrong_baseline_unrelated_edits_and_partial_patch(self):
        with self.assertRaises(ValueError):
            patch_window_size(self.original)
        damaged = bytearray(self.baseline)
        damaged[-1] ^= 1
        with self.assertRaises(ValueError):
            patch_window_size(damaged)
        patched = bytearray(patch_window_size(self.baseline)[0])
        patched[-1] ^= 1
        with self.assertRaises(ValueError):
            restore_window_size(patched)
        patched[-1] ^= 1
        for target in TARGETS:
            broken = bytearray(patched)
            offset = file_offset(broken, target["address"])
            broken[offset:offset + 4] = bytes.fromhex(target["original"])
            with self.subTest(target=target["name"]), self.assertRaises(ValueError):
                restore_window_size(broken)


if __name__ == "__main__":
    unittest.main()
