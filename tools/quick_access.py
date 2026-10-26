"""Pinned quick-access fixes for the original Beam Pro launcher.

Hide the Game Mode row and allow resolved Android Intent handlers without a
launcher icon to obtain hidden catalog metadata for both launch and window title
lookups. The original Intent and existing spatial launch machinery are preserved.
"""
import hashlib
import json
from pathlib import Path

from xreal_telemetry import file_offset

MENU_ASSET = "assets/bin/Data/level1"
MENU_SHA256 = "50abb2f24d9455031161246ca150eb163f8ce79d549515b4b3d0b33de09d7262"
# Unity GameObject path ID 598, Button_SpaceMirror, contains the localized
# evapro_game_mode_name label. Change only its serialized m_IsActive flag.
GAME_MODE_ACTIVE_OFFSET = 121706
BASELINE_SHA256 = "9462771f955bc75b03725eb795343601e20d5323e6aef909d928562e5ec232e1"
TARGETS = json.loads(Path(__file__).with_name("quick_access_targets.json").read_text())


def patch_menu(original):
    if hashlib.sha256(original).hexdigest() != MENU_SHA256:
        raise ValueError("Unexpected Unity menu; refusing unpinned Game Mode removal")
    blob = bytearray(original)
    if blob[GAME_MODE_ACTIVE_OFFSET] != 1:
        raise ValueError("Unexpected Game Mode active flag")
    blob[GAME_MODE_ACTIVE_OFFSET] = 0
    return bytes(blob)


def verify_menu(blob):
    if len(blob) <= GAME_MODE_ACTIVE_OFFSET or blob[GAME_MODE_ACTIVE_OFFSET] != 0:
        raise ValueError("Game Mode is still active in quick access")
    baseline = bytearray(blob)
    baseline[GAME_MODE_ACTIVE_OFFSET] = 1
    if patch_menu(baseline) != blob:
        raise ValueError("Unexpected menu patch payload")
    return True


def patch_quick_access_library(original):
    if hashlib.sha256(original).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unexpected Unity library; apply telemetry and ten-app patches first")
    blob = bytearray(original)
    for target in TARGETS:
        offset = file_offset(blob, target["address"])
        before, after = bytes.fromhex(target["original"]), bytes.fromhex(target["replacement"])
        if len(before) != len(after) or blob[offset:offset + len(before)] != before:
            raise ValueError(f"Unexpected quick-access instructions: {target['name']}")
        blob[offset:offset + len(after)] = after
    return bytes(blob), TARGETS


def restore_quick_access_library(blob):
    baseline = bytearray(blob)
    for target in TARGETS:
        offset = file_offset(blob, target["address"])
        before, after = bytes.fromhex(target["original"]), bytes.fromhex(target["replacement"])
        if blob[offset:offset + len(after)] != after:
            raise ValueError(f"Quick-access fix missing: {target['name']}")
        baseline[offset:offset + len(before)] = before
    if patch_quick_access_library(baseline)[0] != blob:
        raise ValueError("Unexpected quick-access patch payload")
    return bytes(baseline)


def has_quick_access_patch(blob):
    target = TARGETS[0]
    offset = file_offset(blob, target["address"])
    return blob[offset:offset + 4] == bytes.fromhex(target["replacement"])
