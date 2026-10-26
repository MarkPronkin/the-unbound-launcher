"""Pinned ARM64 patches raising My Glasses' native and total app limits to ten."""
import hashlib

from xreal_telemetry import LIBRARY_SHA256, file_offset

MAX_APPS = 10
TELEMETRY_REMOVED_SHA256 = "e6de53205bb74ff28507a94ccffcf6a43711160c05314884f7f846401cb527e0"
# Keep class initialization, launch comparisons and close/input paths. Raise the
# two limits and stop the replacement chooser at the end of its serialized slots.
TARGETS = (
    {"name": "NREAL.HOME.WidgetManager..cctor: MaxNativeAppCount = 10",
     "address": 0x309D8E8, "original": "49008052", "replacement": "49018052"},
    {"name": "NREAL.HOME.WidgetManager.get_MaxOpenedWidgetNumber: return 10",
     "address": 0x3094D18, "original": "003940b9", "replacement": "40018052"},
    {"name": "NREAL.HOME.EvaProAppReplaceView.OnWillOpenNativeApp: stop at chooser capacity",
     "address": 0x33CF958, "original": "420a0054", "replacement": "c2030054"},
)


def patch_app_limit(original):
    """Accept the pinned source library, including its audited telemetry removal."""
    if hashlib.sha256(original).hexdigest() not in (LIBRARY_SHA256, TELEMETRY_REMOVED_SHA256):
        raise ValueError("Unexpected Unity library; refusing unpinned app-limit patches")
    blob = bytearray(original)
    for target in TARGETS:
        offset = file_offset(blob, target["address"])
        if blob[offset:offset + 4].hex() != target["original"]:
            raise ValueError(f"Unexpected app-limit instructions: {target['name']}")
        blob[offset:offset + 4] = bytes.fromhex(target["replacement"])
    return bytes(blob), list(TARGETS)


def verify_app_limit(blob):
    from window_size import has_window_size_patch, restore_window_size
    from icon_placement import has_icon_placement_patch, restore_icon_placement
    from quick_access import has_quick_access_patch, restore_quick_access_library
    if has_window_size_patch(blob):
        blob = restore_window_size(blob)
    if has_icon_placement_patch(blob):
        blob = restore_icon_placement(blob)
    if has_quick_access_patch(blob):
        blob = restore_quick_access_library(blob)
    baseline = bytearray(blob)
    for target in TARGETS:
        offset = file_offset(blob, target["address"])
        if blob[offset:offset + 4].hex() != target["replacement"]:
            raise ValueError(f"Ten-app limit missing: {target['name']}")
        baseline[offset:offset + 4] = bytes.fromhex(target["original"])
    # This also rejects edits anywhere outside the three audited instructions.
    expected, _ = patch_app_limit(baseline)
    if expected != blob:
        raise ValueError("Unexpected app-limit patch payload")
    return MAX_APPS
