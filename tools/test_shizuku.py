"""Binary manifest invariants at the Shizuku permission/discovery boundary."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
import zipfile
from build_embedded_adb import BASE
from shizuku_manifest import patch_shizuku_manifest, PROVIDER, PERMISSION, METADATA, MANAGER
from verify_embedded_adb import elements, verify_attribute_order


class ShizukuManifestTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as archive:
            cls.original = archive.read('AndroidManifest.xml')
        cls.patched = patch_shizuku_manifest(cls.original)

    def test_preserves_all_existing_components_permissions_and_attributes(self):
        old = elements(self.original)
        new = elements(self.patched)
        for item in old: new.remove(item)
        self.assertEqual(len(new), 4)
        self.assertIn(('uses-permission', {'name': PERMISSION}), new)
        self.assertIn(('package', {'name': MANAGER}), new)
        self.assertIn(('meta-data', {'name': METADATA, 'value': 0xffffffff}), new)

    def test_provider_protected_by_system_permission_and_own_authority(self):
        providers = [a for t, a in elements(self.patched) if t == 'provider' and a.get('name') == PROVIDER]
        self.assertEqual(providers, [{'name': PROVIDER, 'permission': 'android.permission.INTERACT_ACROSS_USERS_FULL',
                                    'enabled': 0xffffffff, 'exported': 0xffffffff, 'multiprocess': 0,
                                    'authorities': 'dev.markpronkin.unbound.shizuku'}])

    def test_idempotent_and_android_attribute_order(self):
        self.assertEqual(self.patched, patch_shizuku_manifest(self.patched))
        verify_attribute_order(self.patched)

    def test_adds_queries_and_missing_resource_ids_on_minimal_manifest(self):
        sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'AppData/Local/Android/Sdk')))
        executable = sdk / ('build-tools/36.0.0/aapt2.exe' if os.name == 'nt' else 'build-tools/36.0.0/aapt2')
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = root / 'AndroidManifest.xml'
            manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="example.fixture"><application android:label="Fixture"/></manifest>')
            subprocess.run([str(executable), 'link', '-I', str(sdk / 'platforms/android-34/android.jar'), '--manifest', str(manifest), '-o', str(root / 'base.apk')], check=True, capture_output=True)
            with zipfile.ZipFile(root / 'base.apk') as archive: original = archive.read('AndroidManifest.xml')
            patched = patch_shizuku_manifest(original)
            verify_attribute_order(patched)
            entries = elements(patched)
            self.assertEqual(sum(t == 'queries' for t, _ in entries), 1)
            self.assertIn(('package', {'name': MANAGER}), entries)
            self.assertEqual(patched, patch_shizuku_manifest(patched))
            self.assertEqual([a['authorities'] for t, a in entries if t == 'provider'], ['example.fixture.shizuku'])


if __name__ == '__main__': unittest.main()
