"""Audit binary scope and composition of the menu and Intent metadata fixes."""
import hashlib
import struct
import unittest
import zipfile

from app_limit import patch_app_limit, verify_app_limit
from build_port_bootstrap import SOURCE
from quick_access import (BASELINE_SHA256, GAME_MODE_ACTIVE_OFFSET, MENU_ASSET,
                          TARGETS, patch_menu, patch_quick_access_library,
                          restore_quick_access_library, verify_menu)
from xreal_telemetry import LIBRARY, file_offset, patch_library, verify_library


class QuickAccessTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(SOURCE) as archive:
            cls.menu = archive.read(MENU_ASSET)
            cls.original = archive.read(LIBRARY)
        cls.baseline = patch_app_limit(patch_library(cls.original)[0])[0]

    def test_game_mode_only_changes_its_active_flag(self):
        patched = patch_menu(self.menu)
        self.assertTrue(verify_menu(patched))
        self.assertEqual([i for i, (a, b) in enumerate(zip(self.menu, patched)) if a != b],
                         [GAME_MODE_ACTIVE_OFFSET])

    def test_restores_exact_previous_build_and_preserves_other_patches(self):
        self.assertEqual(hashlib.sha256(self.baseline).hexdigest(), BASELINE_SHA256)
        patched, _ = patch_quick_access_library(self.baseline)
        self.assertEqual(restore_quick_access_library(patched), self.baseline)
        self.assertEqual(verify_app_limit(patched), 10)
        self.assertEqual(verify_library(patched), 40)
        # Reused method is still inert when called as an analytics collector.
        self.assertEqual(patched[file_offset(patched, 0x12CA050):][:4], bytes.fromhex("c0035fd6"))

    def test_rejects_unrelated_native_or_menu_changes(self):
        for original, patch in [(self.menu, patch_menu), (self.baseline, patch_quick_access_library)]:
            corrupt = bytearray(original)
            corrupt[-1] ^= 1
            with self.assertRaises(ValueError):
                patch(corrupt)
        corrupt = bytearray(patch_quick_access_library(self.baseline)[0])
        corrupt[-1] ^= 1
        with self.assertRaises(ValueError):
            restore_quick_access_library(corrupt)

    def test_intent_call_enters_stub_and_calls_existing_metadata_functions(self):
        def branch_target(address, raw):
            word = struct.unpack("<I", raw)[0]
            self.assertEqual(word >> 26, 0b100101)  # BL
            displacement = word & 0x3FFFFFF
            if displacement & 0x2000000:
                displacement -= 0x4000000
            return address + displacement * 4
        hook, stub = TARGETS
        self.assertEqual(branch_target(hook["address"], bytes.fromhex(hook["replacement"])), stub["address"])
        code = bytes.fromhex(stub["replacement"])
        for offset, destination in [(16, 0x3505840), (32, 0x34B99A4), (52, 0x350D360),
                                    (80, 0x3506984), (100, 0xD601E0), (124, 0x1B65244)]:
            self.assertEqual(branch_target(stub["address"] + offset, code[offset:offset + 4]), destination)
        # A catalog hit and null results all return through the shared epilogue.
        for offset in [20, 36, 56]:
            word = struct.unpack_from("<I", code, offset)[0]
            self.assertIn(word & 0xFF00001F, [0xB4000000, 0xB5000000])
            self.assertEqual(offset + ((word >> 5) & 0x7FFFF) * 4, 132)
        for offset in [92, 104]:
            word = struct.unpack_from("<I", code, offset)[0]
            immediate = ((word >> 29) & 3) | (((word >> 5) & 0x7FFFF) << 2)
            self.assertEqual(((stub["address"] + offset) & ~0xFFF) + (immediate << 12), 0x45E7000)


if __name__ == "__main__":
    unittest.main()
