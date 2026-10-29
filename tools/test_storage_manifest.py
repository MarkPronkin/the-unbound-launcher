import unittest
import zipfile
from pathlib import Path

from build_port_bootstrap import chunks
from storage_manifest import PERMISSION, patch_storage_manifest
from verify_embedded_adb import elements, verify_attribute_order


class StorageManifestTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        apk = Path(__file__).resolve().parents[1] / 'artifacts/port-default-m/myglasses-startup-candidate.apk'
        with zipfile.ZipFile(apk) as source:
            cls.original = source.read('AndroidManifest.xml')

    def test_adds_one_manifest_level_permission_and_preserves_everything_else(self):
        patched = patch_storage_manifest(self.original)
        before, after = elements(self.original), elements(patched)
        self.assertNotIn(('uses-permission', {'name': PERMISSION}), before)
        self.assertEqual([entry for entry in after if entry not in before], [('uses-permission', {'name': PERMISSION})])
        self.assertEqual(len(after), len(before) + 1)
        application = [tag for tag, _ in after].index('application')
        self.assertEqual(after[application - 1], ('uses-permission', {'name': PERMISSION}))
        old_nodes = [self.original[p:p + s] for kind, p, _, s in chunks(self.original) if kind != 1]
        new_nodes = [patched[p:p + s] for kind, p, _, s in chunks(patched) if kind != 1]
        self.assertEqual(len(new_nodes), len(old_nodes) + 2)
        verify_attribute_order(patched)

    def test_is_idempotent(self):
        patched = patch_storage_manifest(self.original)
        self.assertEqual(patch_storage_manifest(patched), patched)


if __name__ == '__main__':
    unittest.main()
