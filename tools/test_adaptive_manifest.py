import struct
import unittest
import zipfile
from pathlib import Path

from adaptive_manifest import PHONE_ACTIVITIES, patch_adaptive_manifest
from build_port_bootstrap import chunks
from permission_manifest import patch_manifest


class AdaptiveManifestTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(__file__).resolve().parents[1] / "artifacts/port-default-m/myglasses-startup-candidate.apk"
        with zipfile.ZipFile(path) as source:
            cls.baseline = source.read("AndroidManifest.xml")
        cls.original = patch_manifest(patch_manifest(cls.baseline), "dev.beampro.settings.AppMenuActivity")

    def test_only_four_phone_orientation_values_change(self):
        patched = patch_adaptive_manifest(self.original)
        self.assertEqual(len(patched), len(self.original))
        changed_nodes = []
        for kind, at, header, size in chunks(self.original):
            before, after = self.original[at:at + size], patched[at:at + size]
            if before == after:
                continue
            self.assertEqual(kind, 0x102)
            differences = [index for index, (a, b) in enumerate(zip(before, after)) if a != b]
            self.assertEqual(len(differences), 4)
            first = differences[0]
            self.assertEqual(differences, list(range(first, first + 4)))
            self.assertEqual(struct.unpack_from("<I", before, first)[0], 1)
            self.assertEqual(struct.unpack_from("<I", after, first)[0], 0xFFFFFFFF)
            # The changed value belongs to screenOrientation, by framework resource ID.
            name_index = struct.unpack_from("<I", before, first - 12)[0]
            _, mapping, mapping_header, _ = next(node for node in chunks(self.original) if node[0] == 0x180)
            self.assertEqual(struct.unpack_from("<I", self.original, mapping + mapping_header + name_index * 4)[0], 0x0101001E)
            changed_nodes.append(at)
        self.assertEqual(len(changed_nodes), len(PHONE_ACTIVITIES))

    def test_repeated_builds_are_idempotent(self):
        patched = patch_adaptive_manifest(self.original)
        self.assertEqual(patch_adaptive_manifest(patched), patched)

    def test_rejects_an_incomplete_phone_manifest(self):
        with self.assertRaisesRegex(ValueError, "all four phone pages"):
            patch_adaptive_manifest(self.baseline)


if __name__ == "__main__":
    unittest.main()
