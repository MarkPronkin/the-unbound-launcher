"""Check the presentation patch's exact scope and both configuration entrances."""
import hashlib
import unittest
import zipfile

from build_port_bootstrap import SOURCE
from render_sync import (MODE_SELECTION_CONTEXTS, TARGETS, XR_LIBRARY, XR_SHA256,
                         patch_render_sync, restore_render_sync, verify_render_sync)
from xreal_telemetry import file_offset


class RenderSyncTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(SOURCE) as archive:
            cls.original = archive.read(XR_LIBRARY)

    def test_restores_entire_original_and_keeps_mode_selection(self):
        patched, _ = patch_render_sync(self.original)
        self.assertEqual(hashlib.sha256(restore_render_sync(patched)).hexdigest(), XR_SHA256)
        self.assertEqual(restore_render_sync(patched), self.original)
        self.assertEqual(verify_render_sync(patched)["requested_nr_screen_buffer_mode"], 2)
        for address, expected in MODE_SELECTION_CONTEXTS:
            offset = file_offset(patched, address)
            self.assertEqual(patched[offset:offset + len(bytes.fromhex(expected))].hex(), expected)

    def test_startup_setter_stores_zero_without_changing_stack_or_link_register(self):
        patched, _ = patch_render_sync(self.original)
        offset = file_offset(patched, TARGETS[0]["address"])
        store = int.from_bytes(patched[offset:offset + 4], "little")
        self.assertEqual(store & 0xFFC00000, 0x39000000)  # STRB unsigned immediate
        self.assertEqual(store & 31, 31)                # WZR source
        self.assertEqual((store >> 5) & 31, 0)          # X0 receiver
        self.assertEqual((store >> 10) & 4095, 0x164)    # exact boolean field
        self.assertEqual(patched[offset + 4:offset + 8], bytes.fromhex("c0035fd6"))

    def test_runtime_request_is_zeroed_before_original_state_write(self):
        patched, _ = patch_render_sync(self.original)
        offset = file_offset(patched, TARGETS[1]["address"])
        # ORR W1, WZR, WZR (MOV W1, WZR); the next original instruction
        # stores the coerced value, so requests through this entry cannot
        # disagree with the SDK's subsequent mode selection.
        self.assertEqual(int.from_bytes(patched[offset:offset + 4], "little"), 0x2A1F03E1)
        self.assertEqual(patched[offset + 4:offset + 8].hex(), "01900539")

    def test_rejects_unexpected_inputs_and_unrelated_changes(self):
        corrupt = bytearray(self.original)
        corrupt[-1] ^= 1
        with self.assertRaises(ValueError):
            patch_render_sync(corrupt)
        corrupt = bytearray(patch_render_sync(self.original)[0])
        corrupt[-1] ^= 1
        with self.assertRaises(ValueError):
            verify_render_sync(corrupt)

    def test_requires_both_startup_and_runtime_protection(self):
        patched, _ = patch_render_sync(self.original)
        for target in TARGETS:
            corrupt = bytearray(patched)
            offset = file_offset(corrupt, target["address"])
            before = bytes.fromhex(target["original"])
            corrupt[offset:offset + len(before)] = before
            with self.assertRaises(ValueError):
                verify_render_sync(corrupt)


if __name__ == "__main__":
    unittest.main()
