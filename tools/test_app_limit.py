"""Check exact binary scope, composition and rejection of unexpected libraries."""
import unittest
import zipfile

from app_limit import TARGETS, patch_app_limit, verify_app_limit
from build_port_bootstrap import SOURCE
from xreal_telemetry import LIBRARY, file_offset, patch_library, verify_library


class AppLimitTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(SOURCE) as archive:
            cls.original = archive.read(LIBRARY)

    def test_only_three_instructions_change(self):
        patched, targets = patch_app_limit(self.original)
        restored = bytearray(patched)
        self.assertEqual(verify_app_limit(patched), 10)
        self.assertEqual(len(targets), 3)
        for target in TARGETS:
            offset = file_offset(patched, target["address"])
            restored[offset:offset + 4] = bytes.fromhex(target["original"])
        self.assertEqual(restored, self.original)

    def test_preserves_telemetry_removal(self):
        clean, _ = patch_library(self.original)
        patched, _ = patch_app_limit(clean)
        self.assertEqual(verify_app_limit(patched), 10)
        self.assertEqual(verify_library(patched), 40)

    def test_rejects_unexpected_library(self):
        corrupt = bytearray(self.original)
        corrupt[-1] ^= 1
        with self.assertRaisesRegex(ValueError, "Unexpected Unity library"):
            patch_app_limit(corrupt)

    def test_verifier_requires_all_patches(self):
        patched, _ = patch_app_limit(self.original)
        for target in TARGETS:
            with self.subTest(target=target["name"]):
                corrupt = bytearray(patched)
                offset = file_offset(corrupt, target["address"])
                corrupt[offset:offset + 4] = bytes.fromhex(target["original"])
                with self.assertRaisesRegex(ValueError, "Ten-app limit missing"):
                    verify_app_limit(corrupt)

    def test_chooser_capacity_branch_exits_instead_of_throwing(self):
        target = TARGETS[2]
        instruction = int.from_bytes(bytes.fromhex(target["replacement"]), "little")
        self.assertEqual(instruction & 0xFF00001F, 0x54000002)  # B.HS
        immediate = (instruction >> 5) & 0x7FFFF
        if immediate & 0x40000:
            immediate -= 0x80000
        self.assertEqual(target["address"] + immediate * 4, 0x33CF9D0)


if __name__ == "__main__":
    unittest.main()
