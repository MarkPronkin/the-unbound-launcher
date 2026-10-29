"""Check locale-complete branding and preservation of unrelated resources."""
import struct
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib
from pathlib import Path

from branding import APP_NAME, ICON_PATHS, _table_details, app_name_values, icon_payloads, patch_resources


class BrandingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        apk = Path(__file__).resolve().parents[1] / "artifacts/port-default-m/myglasses-startup-candidate.apk"
        with zipfile.ZipFile(apk) as source:
            cls.original = source.read("resources.arsc")
            cls.names = set(source.namelist())

    def test_all_locales_are_renamed_including_german_and_chinese(self):
        before = app_name_values(self.original)
        self.assertEqual(len(before), 13)
        self.assertIn("Meine Brille", before)
        self.assertIn("我的眼镜", before)
        self.assertEqual(app_name_values(patch_resources(self.original)), (APP_NAME,) * len(before))

    def test_existing_pool_indices_and_strings_are_preserved(self):
        before = _table_details(self.original)
        after = _table_details(patch_resources(self.original))
        self.assertEqual(after[-2][:-1], before[-2])
        self.assertEqual(after[-2][-1], APP_NAME)

    def test_only_app_name_references_change_inside_the_package(self):
        before = _table_details(self.original)
        patched = patch_resources(self.original)
        after = _table_details(patched)
        old_packages = [self.original[p:p+s] for kind, p, _, s in before[1] if kind == 0x200]
        new_packages = [bytearray(patched[p:p+s]) for kind, p, _, s in after[1] if kind == 0x200]
        self.assertEqual(len(old_packages), 1)
        old_start = next(p for kind, p, _, _ in before[1] if kind == 0x200)
        new_start = next(p for kind, p, _, _ in after[1] if kind == 0x200)
        for (old_position, _), (new_position, _) in zip(before[-1], after[-1]):
            self.assertEqual(old_position - old_start, new_position - new_start)
            original_index = struct.unpack_from("<I", self.original, old_position)[0]
            struct.pack_into("<I", new_packages[0], new_position - new_start, original_index)
        self.assertEqual(bytes(new_packages[0]), old_packages[0])

    def test_patch_is_idempotent(self):
        patched = patch_resources(self.original)
        self.assertEqual(patch_resources(patched), patched)

    def test_pinned_apk_with_previous_brand_is_renamed_in_every_locale(self):
        with patch("branding.APP_NAME", "The Unbound Launcher"):
            previous = patch_resources(self.original)
        self.assertEqual(set(app_name_values(previous)), {"The Unbound Launcher"})
        renamed = patch_resources(previous)
        self.assertEqual(app_name_values(renamed), ("Unbound Launcher",) * 13)
        self.assertEqual(patch_resources(renamed), renamed)

    def test_unexpected_resource_table_is_rejected(self):
        with self.assertRaises((ValueError, struct.error)):
            patch_resources(b"not an Android resource table")

    def test_icon_targets_cover_all_densities_and_keep_adaptive_xml(self):
        self.assertEqual(set(map(len, ICON_PATHS.values())), {5})
        paths = [name for group in ICON_PATHS.values() for name in group]
        self.assertEqual(len(set(paths)), 20)
        self.assertFalse(set(paths) - self.names)
        self.assertTrue({"res/uF.xml", "res/oy.xml"} <= self.names)

    @unittest.skipUnless(all(shutil.which(tool) for tool in ("sips", "cwebp", "dwebp")), "native icon tools unavailable")
    def test_packaged_icons_preserve_transparent_foreground_and_solid_background(self):
        root = Path(__file__).resolve().parents[1]
        if not (root / "branding/app-icon.png").is_file() or not (root / "branding/symbol.png").is_file():
            self.skipTest("Brand artwork unavailable")
        with tempfile.TemporaryDirectory(prefix="unbound-branding-") as directory:
            output = Path(directory)
            with zipfile.ZipFile(root / "artifacts/port-default-m/myglasses-startup-candidate.apk") as source:
                payloads = icon_payloads(source, root / "branding/app-icon.png", output)
            self.assertEqual(set(payloads), {name for group in ICON_PATHS.values() for name in group})
            for path in payloads.values():
                self.assertEqual(path.read_bytes()[:4], b"RIFF")
                self.assertEqual(path.read_bytes()[8:12], b"WEBP")
            decoded = output / "decoded-foreground.png"
            subprocess.run([shutil.which("dwebp"), str(payloads[ICON_PATHS["foreground"][-1]]), "-o", str(decoded)],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            blob, position, image_data = decoded.read_bytes(), 8, b""
            while position < len(blob):
                size = struct.unpack_from(">I", blob, position)[0]
                kind, data = blob[position + 4:position + 8], blob[position + 8:position + 8 + size]
                if kind == b"IHDR":
                    self.assertEqual(struct.unpack(">IIBBBBB", data), (432, 432, 8, 6, 0, 0, 0))
                if kind == b"IDAT":
                    image_data += data
                position += size + 12
            # PNG row filters do not alter the first pixel on the first row.
            self.assertEqual(zlib.decompress(image_data)[4], 0)
            ppm = output / "decoded-background.ppm"
            subprocess.run([shutil.which("dwebp"), str(payloads[ICON_PATHS["background"][-1]]), "-ppm", "-o", str(ppm)],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            ppm_data = ppm.read_bytes()
            self.assertTrue(ppm_data.startswith(b"P6\n432 432\n255\n"))
            pixels = ppm_data[len(b"P6\n432 432\n255\n"):]
            self.assertEqual(pixels, bytes((0, 0, 0)) * (432 * 432))
            # The first opaque pixels in the adaptive mark must be white on black.
            sys_path = str(root / "artifacts/tools/python")
            import sys
            sys.path.insert(0, sys_path)
            from PIL import Image
            foreground = Image.open(decoded).convert("RGBA")
            opaque = [pixel for pixel in foreground.get_flattened_data() if pixel[3] == 255]
            self.assertTrue(opaque)
            self.assertTrue(all(pixel[:3] == (255, 255, 255) for pixel in opaque))
            legacy = output / "decoded-legacy.ppm"
            subprocess.run([shutil.which("dwebp"), str(payloads[ICON_PATHS["legacy"][-1]]), "-ppm", "-o", str(legacy)],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            pixels = legacy.read_bytes().split(b"\n255\n", 1)[1]
            self.assertEqual(pixels[:3], b"\0\0\0")
            self.assertIn(b"\xff\xff\xff", pixels)


if __name__ == "__main__":
    unittest.main()
