"""Keep existing anchored windows in place when another app finishes opening."""
import hashlib

from xreal_telemetry import RETURN, file_offset

# After high-performance app handling, before package identity relocation.
BASELINE_SHA256 = "88cfa64aee581db872fc6ee81efcee0fd58ff647f39e4a87b96d47f3164ed667"
FIRST_REBUILD = 0x34F6C38
LAYOUT_CALL = 0x34F6C8C
REBUILD_LAYOUT = 0x1700588
IN_ANCHOR_MODE = 0x16FEF14  # EvaProWindowLayoutBuilder2.get_In3Or6DofMode
WRAPPER = 0x12CF9C0

# FirstRebuildWindowLayout normally calls RebuildLayout(true), which runs
# AvoidWidgetOverlap against already-open windows. Only gate that app-open
# call: initial placement, first-layout flag cleanup, user recentering, dragging,
# resizing and tracking-mode changes retain their original implementations.
# The wrapper reads the live layout mode (3/6DoF = anchored). Unknown and 0DoF
# modes keep the stock call. Its only argument is the constant animation=true.
#
# str x30, [sp, #-16]!
# bl get_In3Or6DofMode
# tbnz w0, #0, done
# mov w0, #1
# mov x1, xzr
# bl RebuildLayout
# done: ldr x30, [sp], #16
# ret
WRAPPER_CODE = bytes.fromhex(
    "fe0f1ff854bd10948000003720008052e1031faaedc21094fe0741f8c0035fd6")
TARGETS = (
    {"name": "FirstRebuildWindowLayout: preserve existing anchored windows",
     "address": LAYOUT_CALL, "original": "3f268897", "replacement": "4d637797"},
    {"name": "App-open layout guard after disabled analytics RET",
     "address": WRAPPER,
     "original": "fa0701a9f96302a9f75b03a9f55304a9f37b05a916b101d0c8564e39f30303aa",
     "replacement": WRAPPER_CODE.hex()},
)


def patch_anchor_windows(original, *, restore=False):
    if not restore and hashlib.sha256(original).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unexpected native library for anchored-window placement")
    blob = bytearray(original)
    guard = file_offset(blob, WRAPPER - 4)
    if blob[guard:guard + 4] != RETURN:
        raise ValueError("Anchored-window guard requires the disabled analytics RET")
    for target in TARGETS:
        before, after = bytes.fromhex(target["original"]), bytes.fromhex(target["replacement"])
        expected, replacement = (after, before) if restore else (before, after)
        offset = file_offset(blob, target["address"])
        if len(before) != len(after) or blob[offset:offset + len(expected)] != expected:
            raise ValueError("Unexpected anchored-window instructions: " + target["name"])
        blob[offset:offset + len(expected)] = replacement
    if restore and hashlib.sha256(blob).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unrelated native changes in anchored-window placement")
    return bytes(blob)


def verify_anchor_windows(blob):
    patch_anchor_windows(blob, restore=True)
    return {"existing_anchor_windows_stay_in_place_on_app_open": True,
            "scope": "FirstRebuildWindowLayout app-open callback in 3/6DoF",
            "smooth_follow_layout_preserved": True,
            "new_window_placement_and_manual_recenter_preserved": True,
            "patch_bytes": 36,
            "hardware_validation": "pending"}
