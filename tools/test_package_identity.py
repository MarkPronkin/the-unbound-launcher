"""Exercise package relocation against the actual Android and Unity payloads."""
import hashlib
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib

from build_embedded_adb import BASE
from package_identity import LEGACY_PACKAGE, PACKAGE, METADATA, patch_binary_identity, repackage_dex
from verify_embedded_adb import classes, elements, verify_attribute_order


class PackageIdentityTests(unittest.TestCase):
    def test_android_components_and_permission_contracts(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('AndroidManifest.xml')
        moved = patch_binary_identity('AndroidManifest.xml', original)
        expected = [(tag, {key: value.replace(LEGACY_PACKAGE, PACKAGE) if isinstance(value, str) else value
                           for key, value in attrs.items()}) for tag, attrs in elements(original)]
        self.assertEqual(elements(moved), expected)
        verify_attribute_order(moved)
        self.assertEqual(patch_binary_identity('AndroidManifest.xml', moved, restore=True), original)
        before_classes = [a['name'] for t, a in elements(original)
                          if t in ('application', 'activity', 'service', 'provider', 'receiver')]
        after_classes = [a['name'] for t, a in elements(moved)
                         if t in ('application', 'activity', 'service', 'provider', 'receiver')]
        self.assertEqual(before_classes, after_classes)

    def test_unity_metadata_and_resource_identifiers_keep_all_offsets(self):
        with zipfile.ZipFile(BASE) as archive:
            for name, encoding, expected in [('resources.arsc', 'utf-16le', 1), (METADATA, 'utf-8', 2)]:
                with self.subTest(name=name):
                    original = archive.read(name)
                    self.assertEqual(original.count(LEGACY_PACKAGE.encode(encoding)), expected)
                    moved = patch_binary_identity(name, original)
                    self.assertEqual(len(moved), len(original))
                    self.assertEqual(moved.count(PACKAGE.encode(encoding)), expected)
                    self.assertEqual(patch_binary_identity(name, moved, restore=True), original)

    def test_real_dex_retains_classes_valid_checksums_and_sorted_strings(self):
        java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
        with tempfile.TemporaryDirectory() as temporary, zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes.dex')
            moved = repackage_dex(original, temporary, java)
            self.assertEqual(classes(moved), classes(original))
            self.assertNotIn(LEGACY_PACKAGE.encode(), moved)
            self.assertIn(PACKAGE.encode(), moved)
            self.assertEqual(moved[12:32], hashlib.sha1(moved[32:]).digest())
            self.assertEqual(struct.unpack_from('<I', moved, 8)[0], zlib.adler32(moved[12:]) & 0xffffffff)
            count, offset = struct.unpack_from('<II', moved, 56)
            strings = []
            for index in range(count):
                at = struct.unpack_from('<I', moved, offset + index * 4)[0]
                while moved[at] & 0x80:
                    at += 1
                at += 1
                # DEX MUTF-8 uses surrogate pairs; compare UTF-16 code units.
                raw = moved[at:moved.index(b'\0', at)].replace(b'\xc0\x80', b'\0')
                value = raw.decode('utf-8', errors='surrogatepass')
                strings.append(value.encode('utf-16be', errors='surrogatepass'))
            self.assertEqual(strings, sorted(strings))
            self.assertEqual(len(strings), len(set(strings)))
            restored = repackage_dex(moved, Path(temporary) / 'reverse', java, restore=True)
            self.assertEqual(classes(restored), classes(original))
            self.assertNotIn(PACKAGE.encode(), restored)


if __name__ == '__main__':
    unittest.main()
