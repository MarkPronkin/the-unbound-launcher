"""Use the launcher's exact M preset when creating app-window preferences."""
import hashlib

from xreal_telemetry import file_offset

BASELINE_SHA256 = "213e772d18cf22141732b18a2734fda5cbac941d897762cae567631ad9f51da5"
DEFAULT_SIZE = "M"
DEFAULT_FOV_PERCENT = 0.75
# GetUserPreferData2's create-only branch originally copies (1.0, 0.94) into
# (measureScale, fovPercent). Construct (1.0, 0.75) in registers instead. The
# latter is EvaProWidgetAdjustProxy.MLevel_Fov_Percent, not a renamed label.
# Existing preferences return before this branch and retain user adjustments.
TARGETS = (
    {"name": "GetUserPreferData2: keep initial measureScale = 1.0",
     "address": 0x186DA74, "original": "0aea00b0", "replacement": "01102e1e"},
    {"name": "GetUserPreferData2: initial fovPercent = M (0.75)",
     "address": 0x186DA80, "original": "41c143fd", "replacement": "02102d1e"},
    {"name": "GetUserPreferData2: store measureScale and M fovPercent",
     "address": 0x186DA90, "original": "81c201fc", "replacement": "818a032d"},
)


def patch_window_size(original):
    if hashlib.sha256(original).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unexpected Unity library; apply icon-placement fixes before window size")
    blob = bytearray(original)
    for target in TARGETS:
        offset = file_offset(blob, target["address"])
        if blob[offset:offset + 4].hex() != target["original"]:
            raise ValueError(f"Unexpected window-size instructions: {target['name']}")
        blob[offset:offset + 4] = bytes.fromhex(target["replacement"])
    return bytes(blob), list(TARGETS)


def restore_window_size(blob):
    baseline = bytearray(blob)
    for target in TARGETS:
        offset = file_offset(blob, target["address"])
        if blob[offset:offset + 4].hex() != target["replacement"]:
            raise ValueError(f"Default M window-size patch missing: {target['name']}")
        baseline[offset:offset + 4] = bytes.fromhex(target["original"])
    if patch_window_size(baseline)[0] != blob:
        raise ValueError("Unexpected window-size patch payload")
    return bytes(baseline)


def has_window_size_patch(blob):
    target = TARGETS[0]
    offset = file_offset(blob, target["address"])
    return blob[offset:offset + 4] == bytes.fromhex(target["replacement"])
