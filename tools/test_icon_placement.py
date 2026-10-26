"""Check clone initialization, native patch scope, and existing patch composition."""
import hashlib
import struct
import unittest
import zipfile

from app_limit import patch_app_limit, verify_app_limit
from build_port_bootstrap import SOURCE
from icon_placement import (BASELINE_SHA256, TARGETS, has_icon_placement_patch,
                            patch_icon_placement, restore_icon_placement)
from quick_access import patch_quick_access_library, restore_quick_access_library
from xreal_telemetry import LIBRARY, RETURN, file_offset, patch_library, verify_library


class IconPlacementTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(SOURCE) as archive:
            original = archive.read(LIBRARY)
        cls.baseline = patch_quick_access_library(patch_app_limit(patch_library(original)[0])[0])[0]

    def test_exact_restore_and_existing_fixes(self):
        self.assertEqual(hashlib.sha256(self.baseline).hexdigest(), BASELINE_SHA256)
        self.assertFalse(has_icon_placement_patch(self.baseline))
        patched, _ = patch_icon_placement(self.baseline)
        self.assertTrue(has_icon_placement_patch(patched))
        self.assertEqual(restore_icon_placement(patched), self.baseline)
        self.assertEqual(verify_app_limit(patched), 10)
        self.assertEqual(verify_library(patched), 40)
        restore_quick_access_library(restore_icon_placement(patched))
        # The code cave never reactivates its telemetry collector.
        offset = file_offset(patched, TARGETS[1]["address"] - 4)
        self.assertEqual(patched[offset:offset + 4], RETURN)

    def test_rejects_unrelated_changes_and_partial_patch(self):
        damaged = bytearray(self.baseline)
        damaged[-1] ^= 1
        with self.assertRaises(ValueError):
            patch_icon_placement(damaged)
        patched = bytearray(patch_icon_placement(self.baseline)[0])
        patched[-1] ^= 1
        with self.assertRaises(ValueError):
            restore_icon_placement(patched)
        patched[-1] ^= 1
        offset = file_offset(patched, TARGETS[1]["address"])
        patched[offset] ^= 1
        with self.assertRaises(ValueError):
            restore_icon_placement(patched)

    def test_wrapper_calls_instantiate_and_virtual_initializer_then_returns_clone(self):
        def branch_target(address, raw):
            word = struct.unpack("<I", raw)[0]
            self.assertEqual(word >> 26, 0b100101)
            displacement = word & 0x3FFFFFF
            if displacement & 0x2000000:
                displacement -= 0x4000000
            return address + displacement * 4
        hook, stub = TARGETS
        self.assertEqual(branch_target(hook["address"], bytes.fromhex(hook["replacement"])), stub["address"])
        self.assertEqual(branch_target(hook["address"], bytes.fromhex(hook["original"])), 0x199ABF8)
        code = bytes.fromhex(stub["replacement"])
        self.assertEqual(branch_target(stub["address"] + 12, code[12:16]), 0x199ABF8)
        words = struct.unpack("<15I", code)
        # Keep source and result across calls; initialize from original metadata.
        self.assertEqual(words[2], 0xAA0003F3)  # mov x19, x0
        self.assertEqual(words[4], 0xAA0003F4)  # mov x20, x0
        self.assertEqual(words[6], 0xF9404661)  # ldr x1, [x19, #0x88]
        self.assertEqual(words[7:11], (0xF9400008, 0xF940BD09, 0xF940C102, 0xD63F0120))
        self.assertEqual(words[11], 0xAA1403E0)  # mov x0, x20
        # A null clone skips initialization and returns null to the caller.
        self.assertEqual(words[5] & 0xFF00001F, 0xB4000000)
        self.assertEqual(20 + ((words[5] >> 5) & 0x7FFFF) * 4, 44)
        self.assertEqual(words[-1], 0xD65F03C0)


if __name__ == "__main__":
    unittest.main()
