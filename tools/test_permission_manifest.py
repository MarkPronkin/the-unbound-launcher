import struct
import unittest
import zipfile
from pathlib import Path

from build_port_bootstrap import chunks
from permission_manifest import ACTIVITY, patch_manifest


class PermissionManifestTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        apk = Path(__file__).resolve().parents[1] / 'artifacts/port-default-m/myglasses-startup-candidate.apk'
        with zipfile.ZipFile(apk) as source:
            cls.original = source.read('AndroidManifest.xml')

    def test_existing_manifest_nodes_and_resources_are_byte_identical(self):
        patched = patch_manifest(self.original)
        before = [self.original[p:p+s] for kind,p,_,s in chunks(self.original) if kind != 1]
        after = [patched[p:p+s] for kind,p,_,s in chunks(patched) if kind != 1]
        # Exactly one start/end pair was inserted; every pre-existing node remains intact.
        cursor = 0
        extra = []
        for node in after:
            if cursor < len(before) and node == before[cursor]: cursor += 1
            else: extra.append(node)
        self.assertEqual(cursor, len(before))
        self.assertEqual([struct.unpack_from('<H', n)[0] for n in extra], [0x102,0x103])
        self.assertEqual(struct.unpack_from('<H', extra[0],28)[0], 3)

    def test_existing_strings_retain_their_indices_and_raw_contents(self):
        patched = patch_manifest(self.original)
        def pool(blob):
            _,p,h,s = next(n for n in chunks(blob) if n[0] == 1)
            count = struct.unpack_from('<I',blob,p+8)[0]
            start = struct.unpack_from('<I',blob,p+20)[0]
            return blob[p:p+s],h,count,start
        old,h,count,start = pool(self.original)
        new,_,new_count,new_start = pool(patched)
        self.assertEqual(new_count,count+1)
        self.assertEqual(old[h:h+4*count],new[h:h+4*count])
        self.assertEqual(new[new_start:new_start+len(old)-start],old[start:])
        self.assertIn(ACTIVITY.encode('utf-16le'),new)

    def test_rejects_a_second_setup_activity(self):
        with self.assertRaises(AssertionError): patch_manifest(patch_manifest(self.original))


if __name__ == '__main__': unittest.main()
