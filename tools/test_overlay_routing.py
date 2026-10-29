"""Multi-layer app windows stay on the Unity copy path; nothing else changes."""
from pathlib import Path
import sys
import unittest
import zipfile

from app_color import LIBRARY, patch_color_native
from build_embedded_adb import BASE
from experimental_stereo import patch_stereo_native
from overlay_routing import SITES, patch_overlay_routing, store, verify_overlay_routing
from startup_tutorial import patch_startup_tutorial
from display_mode_compat import LIBRARY as MODE_LIBRARY, patch_display_mode, verify_display_mode
from overlay_color import patch_overlay_color
from xreal_telemetry import file_offset

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "artifacts/tools/python"))
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM


def decode(blob, address):
    offset = file_offset(blob, address)
    instruction = next(Cs(CS_ARCH_ARM64, CS_MODE_ARM).disasm(blob[offset:offset + 4], address))
    return f"{instruction.mnemonic} {instruction.op_str}"


class OverlayRoutingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as archive:
            cls.baseline = archive.read(LIBRARY)
        cls.original = patch_stereo_native(patch_startup_tutorial(patch_color_native(cls.baseline)))
        cls.patched = patch_overlay_routing(cls.original)

    def test_only_the_six_flag_stores_change(self):
        changed = [at for at in range(len(self.original)) if self.original[at] != self.patched[at]]
        offsets = {file_offset(self.original, address) for address in SITES}
        self.assertTrue(changed)
        self.assertTrue(all(at - at % 4 in offsets for at in changed))
        for address, (method, base) in SITES.items():
            with self.subTest(method=method):
                self.assertEqual(decode(self.original, address), f"strb w8, [x{base}, #0x71]")
                self.assertEqual(decode(self.patched, address), f"strb wzr, [x{base}, #0x71]")

    def test_overlay_decisions_read_the_cleared_field(self):
        # RequestOpenNativeApp: isProtected, then useMultiLayer, else the copy path.
        self.assertEqual(decode(self.patched, 0x32FDA90), "ldrb w9, [x21, #0x72]")
        self.assertEqual(decode(self.patched, 0x32FDAE8), "ldrb w9, [x21, #0x71]")
        self.assertEqual(decode(self.patched, 0x32FDAEC), "cbz w9, #0x32fdb6c")
        # IsMultiLayerAppWindow: isProtected || useMultiLayer.
        self.assertEqual(decode(self.patched, 0x34EE820), "ldrb w9, [x8, #0x72]")
        self.assertEqual(decode(self.patched, 0x34EE830), "ldrb w0, [x8, #0x71]")

    def test_restores_exactly_through_the_native_chain(self):
        restored = patch_overlay_routing(self.patched, restore=True)
        self.assertEqual(restored, self.original)
        native = patch_startup_tutorial(patch_stereo_native(restored, restore=True), restore=True)
        self.assertEqual(patch_color_native(native, restore=True), self.baseline)
        self.assertEqual(verify_overlay_routing(self.patched)["patch_bytes"], 24)

    def test_stock_compositor_audit_rejects_the_retired_gamma_patches(self):
        with zipfile.ZipFile(BASE) as archive:
            original=archive.read(MODE_LIBRARY)
        mode_only=patch_display_mode(original)
        self.assertTrue(verify_display_mode(mode_only)['mode_dimensions_refresh_and_selection_preserved'])
        self.assertEqual(patch_display_mode(mode_only,restore=True),original)
        with self.assertRaisesRegex(ValueError,'Unexpected native library'):
            verify_display_mode(patch_overlay_color(mode_only))

    def test_rejects_unexpected_repeated_partial_or_unrelated_changes(self):
        for blob in (self.baseline, self.patched):
            with self.assertRaisesRegex(ValueError, "Unexpected native library"):
                patch_overlay_routing(blob)
        corrupt = bytearray(self.patched)
        corrupt[-1] ^= 1
        with self.assertRaisesRegex(ValueError, "Unrelated native changes"):
            patch_overlay_routing(bytes(corrupt), restore=True)
        partial = bytearray(self.patched)
        address, (method, base) = next(iter(SITES.items()))
        offset = file_offset(partial, address)
        partial[offset:offset + 4] = store(8, base)
        with self.assertRaisesRegex(ValueError, method):
            patch_overlay_routing(bytes(partial), restore=True)


if __name__ == "__main__":
    unittest.main()
