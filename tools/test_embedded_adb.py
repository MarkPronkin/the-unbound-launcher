"""Meaningful regression checks for the binary manifest boundary of on-phone pairing."""
import unittest
import zipfile
import struct

from build_embedded_adb import BASE
from embedded_adb_manifest import (
    patch_manifest, patch_release_manifest, patch_usb_attach_manifest,
    AUTHORITY, SHIZUKU_AUTHORITY, SHIZUKU_PERMISSION, SHIZUKU_V3_META,
)
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
        self.assertEqual(len(self.before) + 7, len(self.after))

    def test_shizuku_provider_and_metadata_registered(self):
        perms = [attrs for tag, attrs in self.after if tag == "uses-permission" and attrs.get("name") == SHIZUKU_PERMISSION]
        self.assertEqual(len(perms), 1)
        meta = [attrs for tag, attrs in self.after if tag == "meta-data" and attrs.get("name") == SHIZUKU_V3_META]
        self.assertEqual(len(meta), 1)
        self.assertEqual(meta[0]["value"], 0xFFFFFFFF)
        providers = [attrs for tag, attrs in self.after if tag == "provider" and attrs.get("name") == "rikka.shizuku.ShizukuProvider"]
        self.assertEqual(len(providers), 1)
        self.assertEqual(providers[0]["authorities"], SHIZUKU_AUTHORITY)
        self.assertEqual(providers[0]["exported"], 0xFFFFFFFF)

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

    def test_ar_backdrop_is_private(self):
        matches=[attrs for tag,attrs in self.after if attrs.get('name')=='dev.beampro.port.ArBackdropActivity']
        self.assertEqual(len(matches),1)
        self.assertEqual(matches[0]['exported'],0)

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
