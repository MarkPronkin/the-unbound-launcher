"""Pinned launcher visibility extension; shares the existing LayoutConfig.json."""
import hashlib
import json
from pathlib import Path
from xreal_telemetry import file_offset

BASELINE_SHA256 = "68f80fd8bf8241a9f3f3c34222217012070d82a25c651c7972511f7082da1bf0"
TARGETS = json.loads(Path(__file__).with_name("app_menu_targets.json").read_text())
HIDDEN_POSITION = 2147483647


def patch_app_menu(original):
    if hashlib.sha256(original).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unexpected Unity library; use the verified M-default build")
    output = bytearray(original)
    for target in TARGETS:
        offset = file_offset(output, target["address"])
        before, after = bytes.fromhex(target["original"]), bytes.fromhex(target["replacement"])
        if len(before) != len(after) or output[offset:offset + len(before)] != before:
            raise ValueError(f"Unexpected app-menu instructions: {target['name']}")
        output[offset:offset + len(after)] = after
    return bytes(output)


def restore_app_menu(blob):
    original = bytearray(blob)
    for target in TARGETS:
        offset = file_offset(original, target["address"])
        before, after = bytes.fromhex(target["original"]), bytes.fromhex(target["replacement"])
        if original[offset:offset + len(after)] != after:
            raise ValueError(f"Missing app-menu patch: {target['name']}")
        original[offset:offset + len(before)] = before
    if patch_app_menu(original) != blob:
        raise ValueError("Unexpected app-menu patch scope")
    return bytes(original)
