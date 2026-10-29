"""Verify version updates preserve every unrelated original manifest attribute."""
from pathlib import Path
import unittest
from zipfile import ZipFile
from app_version import load_version, manifest_version, patch_manifest_version, _manifest_details


class AppVersionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = Path(__file__).resolve().parents[1]
        with ZipFile(root / "artifacts/port-default-m/myglasses-startup-candidate.apk") as source:
            cls.original = source.read("AndroidManifest.xml")

    def test_real_base_and_upgradable_alpha_package(self):
        base = manifest_version(self.original)
        self.assertEqual(base, {"version_name": "2.2.2", "version_code": 2873})
        version = load_version()
        self.assertEqual(version["version_name"], "0.2.3 alpha")
        self.assertGreater(version["version_code"], 2956)
        updated = patch_manifest_version(self.original, version)
        self.assertEqual(manifest_version(updated), version)
        self.assertEqual(patch_manifest_version(updated, version), updated)

    def test_only_version_attributes_and_appended_string_change(self):
        updated = patch_manifest_version(self.original, load_version())
        _, old_nodes, old_pool, _, old_strings, old_attrs = _manifest_details(self.original)
        _, new_nodes, new_pool, _, new_strings, _ = _manifest_details(updated)
        self.assertEqual(new_strings[:-1], old_strings)
        self.assertEqual(len(old_nodes), len(new_nodes))
        for old, new in zip(old_nodes, new_nodes):
            kind, at, header, size = old
            new_kind, new_at, new_header, new_size = new
            self.assertEqual((kind, header), (new_kind, new_header))
            if at == old_pool:
                self.assertEqual(new_at, new_pool)
                continue
            before = bytearray(self.original[at:at + size])
            after = bytearray(updated[new_at:new_at + new_size])
            for attribute in (0x0101021B, 0x0101021C):
                position = old_attrs[attribute] - at
                if 0 <= position < size:
                    before[position + 8:position + 12] = after[position + 8:position + 12]
                    before[position + 16:position + 20] = after[position + 16:position + 20]
            self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
