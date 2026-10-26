"""Pinned ARM64 fix for the launcher's uninitialized floating icon copy."""
import hashlib
import json
from pathlib import Path

from xreal_telemetry import file_offset

BASELINE_SHA256 = "c815f594ac84d9704a94194ed90195820356778aaed9954bda42df61ee8b6db6"
TARGETS = json.loads(Path(__file__).with_name("icon_placement_targets.json").read_text())


def patch_icon_placement(original):
    if hashlib.sha256(original).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unexpected Unity library; apply quick-access fixes before icon placement")
    blob = bytearray(original)
    for target in TARGETS:
        offset = file_offset(blob, target["address"])
        before, after = bytes.fromhex(target["original"]), bytes.fromhex(target["replacement"])
        if len(before) != len(after) or blob[offset:offset + len(before)] != before:
            raise ValueError(f"Unexpected icon-placement instructions: {target['name']}")
        blob[offset:offset + len(after)] = after
    return bytes(blob), TARGETS


def restore_icon_placement(blob):
    baseline = bytearray(blob)
    for target in TARGETS:
        offset = file_offset(blob, target["address"])
        before, after = bytes.fromhex(target["original"]), bytes.fromhex(target["replacement"])
        if blob[offset:offset + len(after)] != after:
            raise ValueError(f"Icon-placement fix missing: {target['name']}")
        baseline[offset:offset + len(before)] = before
    if patch_icon_placement(baseline)[0] != blob:
        raise ValueError("Unexpected icon-placement patch payload")
    return bytes(baseline)


def has_icon_placement_patch(blob):
    target = TARGETS[0]
    offset = file_offset(blob, target["address"])
    return blob[offset:offset + 4] == bytes.fromhex(target["replacement"])
