"""Regression checks for telemetry patch scope and fail-closed binary guards."""
import json
from pathlib import Path
import struct
import unittest
import zipfile

from build_port_bootstrap import SOURCE, chunks, patch_manifest
from xreal_telemetry import TARGETS, MANIFEST_COMPONENTS, file_offset, patch_library, verify_library


def manifest_elements(blob):
    """Read active XML nodes independently of the manifest mutation loop."""
    _, start, header, _ = next(chunk for chunk in chunks(blob) if chunk[0] == 1)
    count, _, flags, string_start = struct.unpack_from("<IIII", blob, start + 8)
    assert not flags & 256
    strings = []
    for index in range(count):
        offset = start + string_start + struct.unpack_from("<I", blob, start + header + index * 4)[0]
        length = struct.unpack_from("<H", blob, offset)[0]
        strings.append(blob[offset + 2:offset + 2 + length * 2].decode("utf-16le"))
    elements = []
    for kind, position, _, _ in chunks(blob):
        if kind != 0x102:
            continue
        element = strings[struct.unpack_from("<I", blob, position + 20)[0]]
        start, size, count = struct.unpack_from("<HHH", blob, position + 24)
        attrs = {}
        for index in range(count):
            offset = position + 16 + start + size * index
            name = strings[struct.unpack_from("<I", blob, offset + 4)[0]]
            kind, value = blob[offset + 15], struct.unpack_from("<I", blob, offset + 16)[0]
            attrs[name] = strings[value] if kind == 3 else value
        elements.append((element, attrs))
    return elements


class TelemetryPatchTests(unittest.TestCase):
    def test_manifest_retains_networking_and_unrelated_components(self):
        with zipfile.ZipFile(SOURCE) as archive:
            original = archive.read("AndroidManifest.xml")
        before = manifest_elements(original)
        after = manifest_elements(patch_manifest(original, allow_network=True))
        expected = []
        for element, attrs in before:
            if attrs.get("name") in MANIFEST_COMPONENTS:
                continue
            attrs = dict(attrs)
            if element == "manifest":
                attrs.pop("sharedUserId")
            if element == "application":
                attrs["debuggable"] = 0xFFFFFFFF
            expected.append((element, attrs))
        self.assertEqual(after, expected)
        internet = [node for node in after if node[0] == "uses-permission" and node[1].get("name") == "android.permission.INTERNET"]
        self.assertEqual(len(internet), 1)
        offline = manifest_elements(patch_manifest(original, allow_network=False))
        self.assertEqual(offline, [node for node in after if node not in internet])

    def test_native_patch_changes_only_audited_collector_instructions(self):
        original = (Path(__file__).resolve().parents[1] / "artifacts/beam-pro-system/il2cpp/libil2cpp.so").read_bytes()
        patched, targets = patch_library(original)
        self.assertEqual(verify_library(patched), 40)
        restored = bytearray(patched)
        for target in targets:
            offset = file_offset(original, target["address"])
            restored[offset:offset + 4] = bytes.fromhex(target["original"])
        self.assertEqual(restored, original)
        with self.assertRaisesRegex(ValueError, "unpinned"):
            patch_library(patched)
        with self.assertRaisesRegex(ValueError, "Active XREAL collector"):
            verify_library(original)

    def test_collector_targets_are_unique_and_scoped(self):
        targets = json.loads(TARGETS.read_text())
        self.assertEqual(len(targets), len({target["address"] for target in targets}))
        self.assertTrue(all(target["name"].startswith("NREAL.HOME.Analysis.") for target in targets))


if __name__ == "__main__":
    unittest.main()
