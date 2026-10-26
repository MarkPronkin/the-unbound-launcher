"""Protect existing resource identities and activity themes during the launch fix."""
from pathlib import Path
import struct
import unittest
from zipfile import ZipFile
from branding import _chunks, _pool_strings, app_name_values, patch_resources
from startup_theme import add_startup_theme, patch_splash_theme_manifest, ATTRS, STYLE_ID, STYLE_NAME, PARENT


class StartupThemeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = Path(__file__).resolve().parents[1]
        with ZipFile(root / "artifacts/port-default-m/myglasses-startup-candidate.apk") as source:
            cls.original = patch_resources(source.read("resources.arsc"))
            cls.manifest = source.read("AndroidManifest.xml")
        cls.patched = add_startup_theme(cls.original)

    def package(self, blob):
        header = struct.unpack_from("<H", blob, 2)[0]
        _, at, size_header, size = next(n for n in _chunks(blob, header, len(blob)) if n[0] == 0x200)
        return blob[at:at + size], size_header

    def test_existing_resource_ids_offsets_and_values_are_retained(self):
        before, header = self.package(self.original)
        after, new_header = self.package(self.patched)
        self.assertEqual(header, new_header)
        self.assertEqual(before[8:header], after[8:header])
        old_nodes = list(_chunks(before, header, len(before)))
        new_nodes = list(_chunks(after, header, len(after)))
        self.assertEqual(len(old_nodes), len(new_nodes))
        key_offset = struct.unpack_from("<I", before, 276)[0]
        for old, new in zip(old_nodes, new_nodes):
            kind, at, chunk_header, size = old
            new_kind, new_at, new_chunk_header, new_size = new
            self.assertEqual((kind, chunk_header), (new_kind, new_chunk_header))
            a, b = before[at:at + size], after[new_at:new_at + new_size]
            if at == key_offset:
                self.assertEqual(_pool_strings(b)[:-1], _pool_strings(a))
                self.assertEqual(_pool_strings(b)[-1], STYLE_NAME)
            elif kind in (0x201, 0x202) and a[8] == 0x11 and size != new_size:
                self.assertEqual(struct.unpack_from("<I", b, 12)[0], struct.unpack_from("<I", a, 12)[0] + 1)
                if kind == 0x202:
                    self.assertEqual(a[16:], b[16:-4])
                    self.assertEqual(b[-4:], bytes(4))
                else:
                    count = struct.unpack_from("<I", a, 12)[0]
                    start = struct.unpack_from("<I", a, 16)[0]
                    new_start = struct.unpack_from("<I", b, 16)[0]
                    self.assertEqual(new_start, start + 4)
                    self.assertEqual(a[chunk_header:start], b[chunk_header:chunk_header + count * 4])
                    self.assertEqual(a[start:], b[new_start:new_start + len(a) - start])
            else:
                self.assertEqual(a, b)
        self.assertEqual(app_name_values(self.original), app_name_values(self.patched))

    def test_new_theme_is_black_and_uses_the_adaptive_launcher_icon(self):
        package, header = self.package(self.patched)
        _, at, chunk_header, size = next(n for n in _chunks(package, header, len(package))
            if n[0] == 0x201 and package[n[1] + 8] == 0x11 and not any(package[n[1] + 24:n[1] + n[2]]))
        entries_start = struct.unpack_from("<I", package, at + 16)[0]
        offset = struct.unpack_from("<I", package, at + chunk_header + (STYLE_ID & 0xFFFF) * 4)[0]
        entry = at + entries_start + offset
        entry_size, flags, key, parent, count = struct.unpack_from("<HHIII", package, entry)
        self.assertEqual((entry_size, flags, parent, count), (16, 1, PARENT, len(ATTRS)))
        values = {}
        for i in range(count):
            name, size, zero, kind, value = struct.unpack_from("<IHBBI", package, entry + 16 + i * 12)
            self.assertEqual((size, zero), (8, 0)); values[name] = (kind, value)
        self.assertEqual(values[0x01010054], (0x1C, 0xFF000000))
        self.assertEqual(values[0x0101062C], (0x1C, 0xFF000000))
        self.assertEqual(values[0x01010630], (0x1C, 0xFF000000))
        self.assertEqual(values[0x0101062D], (1, 0x7F0D0001))
        self.assertEqual(values[0x01010058], (0x12, 0))

    def test_manifest_only_changes_the_splash_theme_reference(self):
        patched = patch_splash_theme_manifest(self.manifest)
        self.assertEqual(len(patched), len(self.manifest))
        changes = [i for i, (a, b) in enumerate(zip(self.manifest, patched)) if a != b]
        start = changes[0]
        self.assertEqual(self.manifest[start:start + 4], struct.pack("<I", 0x7F1101DA))
        self.assertEqual(patched, self.manifest[:start] + struct.pack("<I", STYLE_ID) + self.manifest[start + 4:])

    def test_duplicate_changes_are_rejected(self):
        with self.assertRaises(ValueError): add_startup_theme(self.patched)
        with self.assertRaises(ValueError): patch_splash_theme_manifest(patch_splash_theme_manifest(self.manifest))


if __name__ == "__main__": unittest.main()
