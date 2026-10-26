"""Pinned, minimal ARM64 patches for XREAL's Unity event/identifier collectors.

Normal rendering, pose tracking, networking and third-party crash reporting stay intact.
The audited targets and original instructions are checked in independently of the dump.
"""
import hashlib
import json
from pathlib import Path
import struct

LIBRARY = "lib/arm64-v8a/libil2cpp.so"
LIBRARY_SHA256 = "5e28b50c1bf6a7e8065821c65fc91543690d77cbe15bd98f6b9eaf948db5f178"
TARGETS = Path(__file__).with_name("xreal_telemetry_targets.json")
RETURN = bytes.fromhex("c0035fd6")  # AArch64 RET; all selected methods return void.
MANIFEST_COMPONENTS = {
    "com.xreal.analytics.AnalyticsService",
    "com.sensorsdata.analytics.android.sdk.data.SensorsDataContentProvider",
    "com.xreal.analytics.android.MainProcessName",
}


def file_offset(blob, address):
    if blob[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", blob, 18)[0] != 183:
        raise ValueError("Expected little-endian AArch64 ELF")
    phoff = struct.unpack_from("<Q", blob, 32)[0]
    size, count = struct.unpack_from("<HH", blob, 54)
    for index in range(count):
        kind, flags, offset, virtual, _, filesz = struct.unpack_from("<IIQQQQ", blob, phoff + index * size)
        if kind == 1 and flags & 1 and virtual <= address and address + 4 <= virtual + filesz:
            return offset + address - virtual
    raise ValueError(f"Address {address:#x} is outside an executable segment")


def patch_library(original):
    if hashlib.sha256(original).hexdigest() != LIBRARY_SHA256:
        raise ValueError("Unexpected Unity library; refusing unpinned telemetry patches")
    blob = bytearray(original)
    targets = json.loads(TARGETS.read_text())
    for target in targets:
        offset = file_offset(blob, target["address"])
        if blob[offset:offset + 4].hex() != target["original"]:
            raise ValueError(f"Unexpected collector instructions: {target['name']}")
        blob[offset:offset + 4] = RETURN
    return bytes(blob), targets


def verify_library(blob):
    targets = json.loads(TARGETS.read_text())
    for target in targets:
        offset = file_offset(blob, target["address"])
        if blob[offset:offset + 4] != RETURN:
            raise ValueError(f"Active XREAL collector remains: {target['name']}")
    return len(targets)
