"""Meaningful regression checks for the binary manifest boundary of on-phone pairing."""
import unittest
import zipfile
import struct

from build_embedded_adb import BASE
from embedded_adb_manifest import patch_manifest, patch_release_manifest, patch_usb_attach_manifest, AUTHORITY, AR_BACKDROP_THEME
from verify_embedded_adb import elements, verify_attribute_order


class EmbeddedAdbManifestTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as archive:
            cls.before = elements(archive.read("AndroidManifest.xml"))
            cls.after = elements(patch_manifest(archive.read("AndroidManifest.xml")))

    def test_existing_components_and_permissions_preserved(self):
        for element in self.before:
            self.assertIn(element, self.after)
        self.assertEqual(len(self.before) + 9, len(self.after))

    def test_samsung_process_preservation_is_application_metadata(self):
        key = 'com.samsung.android.multidisplay.keep_process_alive'
        self.assertEqual([a for t, a in self.after if t == 'meta-data' and a.get('name') == key],
                         [{'name': key, 'value': 0xFFFFFFFF}])
        from build_port_bootstrap import chunks
        from branding import _pool_strings
        with zipfile.ZipFile(BASE) as archive:
            manifest = patch_manifest(archive.read('AndroidManifest.xml'))
        nodes = list(chunks(manifest))
        _, at, _, size = next(n for n in nodes if n[0] == 1)
        strings = _pool_strings(manifest[at:at + size])
        stack = []
        for kind, at, header, _ in nodes:
            if kind == 0x102:
                tag = strings[struct.unpack_from('<I', manifest, at + header + 4)[0]]
                offset, stride, count = struct.unpack_from('<HHH', manifest, at + header + 8)
                for i in range(count):
                    attr = at + header + offset + i * stride
                    if manifest[attr + 15] == 3 and strings[struct.unpack_from('<I', manifest, attr + 16)[0]] == key:
                        self.assertEqual(tag, 'meta-data')
                        self.assertEqual(stack, ['manifest', 'application'])
                stack.append(tag)
            elif kind == 0x103:
                stack.pop()

    def test_automatic_usb_entry_is_a_private_broadcast_receiver(self):
        name = 'dev.beampro.adb.AutoGlassesReceiver'
        matches = [attrs for tag, attrs in self.after if tag == 'receiver' and attrs.get('name') == name]
        self.assertEqual(len(matches), 1)
        self.assertEqual(matches[0]['exported'], 0)
        index = next(i for i, (tag, attrs) in enumerate(self.after) if tag == 'receiver' and attrs.get('name') == name)
        self.assertEqual(self.after[index + 1], ('intent-filter', {}))
        self.assertEqual(self.after[index + 2:index + 4], [
            ('action', {'name': 'android.hardware.usb.action.USB_DEVICE_ATTACHED'}),
            ('action', {'name': 'android.hardware.usb.action.USB_DEVICE_DETACHED'}),
        ])

    def test_pairing_and_overlay_cannot_be_launched_by_other_apps(self):
        for name in ("AdbPairingActivity", "PairingOverlayService"):
            matches = [attrs for tag, attrs in self.after if attrs.get("name") == "dev.beampro.adb." + name]
            self.assertEqual(len(matches), 1)
            self.assertEqual(matches[0]["exported"], 0)

    def test_provider_has_its_own_authority_and_process(self):
        matches = [attrs for tag, attrs in self.after if attrs.get("authorities") == AUTHORITY]
        self.assertEqual(len(matches), 1)
        self.assertEqual(matches[0]["process"], ":unbound_bridge")
        self.assertEqual(matches[0]["exported"], 0xFFFFFFFF)

    def test_ar_backdrop_shares_renderer_process_and_declares_opaque_theme(self):
        matches=[attrs for tag,attrs in self.after if attrs.get('name')=='dev.beampro.port.ArBackdropActivity']
        self.assertEqual(len(matches),1)
        self.assertEqual(matches[0]['exported'],0)
        renderer=[attrs for tag,attrs in self.after if attrs.get('name')=='ai.nreal.nebula.space.LaunchSpaceAcrivity']
        self.assertEqual(len(renderer),1)
        self.assertEqual(renderer[0]['process'],':space')
        self.assertEqual(matches[0]['process'],renderer[0]['process'])
        self.assertEqual(matches[0]['theme'],AR_BACKDROP_THEME)

    def test_android_styled_attribute_order(self):
        with zipfile.ZipFile(BASE) as archive:
            manifest = patch_manifest(archive.read("AndroidManifest.xml"))
        verify_attribute_order(manifest)
        # Swapping the provider's authorities and exported attributes recreates
        # the shipped bug: raw values look right, Android silently skips them.
        from build_port_bootstrap import chunks
        broken = bytearray(manifest)
        node = [at for kind, at, _, _ in chunks(broken) if kind == 0x102][-1]
        offset, stride, count = struct.unpack_from("<HHH", broken, node + 24)
        self.assertEqual(count, 4)
        first = node + 16 + offset + stride
        last = first + stride * 2
        broken[first:first + stride], broken[last:last + stride] = broken[last:last + stride], broken[first:first + stride]
        with self.assertRaises(ValueError): verify_attribute_order(broken)

    def test_public_build_disables_debugging_and_preserves_other_attributes(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read("AndroidManifest.xml")
        release = patch_release_manifest(original)
        expected = elements(original)
        applications = [attrs for tag, attrs in expected if tag == "application"]
        self.assertEqual(applications[0]["debuggable"], 0xFFFFFFFF)
        applications[0]["debuggable"] = 0
        self.assertEqual(elements(release), expected)
        self.assertEqual(patch_release_manifest(release), release)
        verify_attribute_order(release)

    def test_usb_handler_confirmation_removed_but_launcher_and_receiver_remain(self):
        with zipfile.ZipFile(BASE) as archive:
            original=archive.read("AndroidManifest.xml")
        action="android.hardware.usb.action.USB_DEVICE_ATTACHED"
        expected=elements(original)
        first=next(i for i,(tag,attrs) in enumerate(expected) if tag=="action" and attrs.get("name")==action)
        self.assertEqual(expected[first-1],("intent-filter",{}))
        del expected[first-1:first+1]
        expected=[entry for entry in expected if not(entry[0]=="meta-data" and entry[1].get("name")==action)]
        patched=patch_usb_attach_manifest(original)
        self.assertEqual(elements(patched),expected)
        self.assertEqual(patch_usb_attach_manifest(patched),patched)
        self.assertIn(("action",{"name":"android.intent.action.MAIN"}),expected)
        self.assertIn(("category",{"name":"android.intent.category.LAUNCHER"}),expected)
        self.assertEqual(sum(tag=="action" and attrs.get("name")==action for tag,attrs in expected),1)
        verify_attribute_order(patched)


if __name__ == "__main__": unittest.main()
