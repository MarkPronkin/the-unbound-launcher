"""Regression tests for Shizuku manifest patching, IPC contracts, and shell/root UID support."""
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from build_embedded_adb import write_pairing_sources
from embedded_adb_manifest import (
    AUTHORITY, SHIZUKU_AUTHORITY, SHIZUKU_PERMISSION, SHIZUKU_V3_META, patch_manifest,
)
from verify_embedded_adb import elements, verify_attribute_order

ROOT = Path(__file__).resolve().parents[1]


def _build_minimal_binary_manifest(include_value_attr=True):
    """Build a minimal valid Android binary XML manifest with a UTF-16 string pool and 0x180 map."""
    attr_items = [
        ("name", 0x01010003),
        ("exported", 0x01010010),
        ("process", 0x01010011),
        ("authorities", 0x01010018),
    ]
    if include_value_attr:
        attr_items.append(("value", 0x01010024))
    strings = [k for k, _ in attr_items] + [
        "http://schemas.android.com/apk/res/android",
        "manifest",
        "application",
    ]
    resource_ids = [v for _, v in attr_items]

    offsets = []
    encoded_strings = bytearray()
    for s in strings:
        offsets.append(len(encoded_strings))
        u16 = s.encode("utf-16le")
        encoded_strings += struct.pack("<H", len(s)) + u16 + b"\0\0"
    if len(encoded_strings) % 4:
        encoded_strings += b"\0" * (4 - len(encoded_strings) % 4)
    strings_start = 28 + 4 * len(strings)
    pool = struct.pack(
        "<HHIIIIII",
        0x0001, 28, strings_start + len(encoded_strings),
        len(strings), 0, 0, strings_start, 0,
    )
    pool += struct.pack("<" + "I" * len(offsets), *offsets) + encoded_strings

    res_map = struct.pack(
        "<HHI" + "I" * len(resource_ids),
        0x0180, 8, 8 + 4 * len(resource_ids), *resource_ids,
    )

    def start_tag(tag_name):
        idx = strings.index(tag_name)
        return struct.pack("<HHIII", 0x0102, 16, 36, 0, 0xFFFFFFFF) + struct.pack(
            "<IIHHHHHH", 0xFFFFFFFF, idx, 20, 20, 0, 0, 0, 0
        )

    def end_tag(tag_name):
        idx = strings.index(tag_name)
        return struct.pack("<HHIIIII", 0x0103, 16, 24, 0, 0xFFFFFFFF, 0xFFFFFFFF, idx)

    body = pool + res_map + start_tag("manifest") + start_tag("application") + end_tag("application") + end_tag("manifest")
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


class ShizukuSupportTests(unittest.TestCase):
    def test_manifest_patch_adds_shizuku_provider_permission_and_metadata(self):
        for include_value in (True, False):
            with self.subTest(include_value=include_value):
                raw = _build_minimal_binary_manifest(include_value_attr=include_value)
                patched = patch_manifest(raw)
                verify_attribute_order(patched)
                parsed = elements(patched)
                self.assertIn(("uses-permission", {"name": SHIZUKU_PERMISSION}), parsed)
                self.assertIn(("meta-data", {"name": SHIZUKU_V3_META, "value": 0xFFFFFFFF}), parsed)
                self.assertIn((
                    "provider",
                    {
                        "name": "rikka.shizuku.ShizukuProvider",
                        "authorities": SHIZUKU_AUTHORITY,
                        "exported": 0xFFFFFFFF,
                    },
                ), parsed)
                self.assertEqual(
                    parsed[-1],
                    (
                        "provider",
                        {
                            "name": "dev.beampro.adb.LocalBridgeProvider",
                            "authorities": AUTHORITY,
                            "exported": 0xFFFFFFFF,
                            "process": ":unbound_bridge",
                        },
                    ),
                )

    def test_shizuku_classes_and_uid_checks_present(self):
        container = (ROOT / "embedded-adb/src/moe/shizuku/api/BinderContainer.java").read_text()
        self.assertIn("package moe.shizuku.api;", container)
        self.assertIn("public IBinder binder;", container)

        provider = (ROOT / "embedded-adb/src/rikka/shizuku/ShizukuProvider.java").read_text()
        self.assertIn("package rikka.shizuku;", provider)
        self.assertIn("moe.shizuku.privileged.api.intent.extra.BINDER", provider)
        self.assertIn("caller != 2000 && caller != 0", provider)

        client = (ROOT / "embedded-adb/src/dev/beampro/adb/ShizukuClient.java").read_text()
        self.assertIn("moe.shizuku.server.IShizukuService", client)
        self.assertIn("moe.shizuku.server.IShizukuApplication", client)
        self.assertIn("moe.shizuku.server.IRemoteProcess", client)
        self.assertIn("uid != 2000 && uid != 0", client)

        bootstrap = (ROOT / "embedded-adb/src/dev/beampro/adb/AdbBootstrap.java").read_text()
        self.assertIn("connectShizuku", bootstrap)
        self.assertIn("requestAndConnectShizuku", bootstrap)
        self.assertIn('!"2000".equals(uid) && !"0".equals(uid)', bootstrap)

        local_bridge = (ROOT / "embedded-adb/src/dev/beampro/adb/LocalBridgeProvider.java").read_text()
        self.assertIn("caller != 2000 && caller != 0", local_bridge)

        launch_server = (ROOT / "bridge/src/dev/beampro/bridge/LaunchServer.java").read_text()
        self.assertIn("initialUid != 2000 && initialUid != 0", launch_server)
        self.assertIn("caller != 2000 && caller != 0", launch_server)

    def test_pairing_sources_archive_includes_shizuku_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive_path = Path(tmp) / "pairing-sources.zip"
            write_pairing_sources(archive_path)
            with zipfile.ZipFile(archive_path) as zf:
                names = set(zf.namelist())
        self.assertIn("embedded-adb/src/dev/beampro/adb/ShizukuClient.java", names)
        self.assertIn("embedded-adb/src/rikka/shizuku/ShizukuProvider.java", names)
        self.assertIn("embedded-adb/src/moe/shizuku/api/BinderContainer.java", names)


if __name__ == "__main__":
    unittest.main()
