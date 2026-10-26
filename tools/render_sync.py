"""Pinned candidate selecting the XR SDK's buffered screen presentation path.

The stock plugin defaults to single buffering. Its startup configuration and
runtime setter choose NRScreenBufferMode 1 for true, 2 for false. Keep the SDK's
mode selection and lifecycle intact, but require false at both entry points.
NRFrameBufferMode (stereo/mono) is a separate setting and is not changed.

This addresses the front-buffer mechanism implicated by the recorded mixed frame;
hardware validation is still required before calling the visual issue fixed.
"""
import hashlib

from xreal_telemetry import file_offset

XR_LIBRARY = "lib/arm64-v8a/libXREALXRPlugin.so"
XR_SHA256 = "0381362bcc46808df9ae27711d058250810c7a013c6fd5320e33b392135937e5"
TARGETS = (
    {
        "name": "DisplayManager.ApplyUseSingleBufferMode: require buffered presentation at initialization",
        "address": 0xC9318,
        "original": "ff0301d1fe4f03a9",
        # STRB WZR, [X0, #0x164]; RET. This void configuration function becomes
        # a leaf setter. No stack allocation, calls or callee-saved registers.
        "replacement": "1f900539c0035fd6",
    },
    {
        "name": "DisplayManager.SetSingleBufferMode: reject runtime requests for single buffering",
        "address": 0xCD884,
        "original": "21000012",
        # MOV W1, WZR replaces AND W1, W1, #1. The original field write,
        # logging, renderer lifecycle and mode-2 API call remain in place.
        "replacement": "e1031f2a",
    },
)

# The existing callers translate a false flag to NRScreenBufferMode 2. Pin
# these contexts so the distinction from stereo/mono is reviewable explicitly.
MODE_SELECTION_CONTEXTS = (
    (0xC7F6C, "eb04009468924539603640f91f010071280080520115881a13050094"),
    (0xCD8B8, "68924539603640f9f44f41a91f010071280080520115881afe0742f8bfeeff17"),
)


def _check_contexts(blob):
    for address, expected in MODE_SELECTION_CONTEXTS:
        offset = file_offset(blob, address)
        if blob[offset:offset + len(bytes.fromhex(expected))].hex() != expected:
            raise ValueError("Unexpected XR screen-buffer mode selection")


def patch_render_sync(original):
    if hashlib.sha256(original).hexdigest() != XR_SHA256:
        raise ValueError("Unexpected XR plugin; refusing unpinned presentation patch")
    _check_contexts(original)
    blob = bytearray(original)
    for target in TARGETS:
        offset = file_offset(blob, target["address"])
        before, after = bytes.fromhex(target["original"]), bytes.fromhex(target["replacement"])
        if len(before) != len(after) or blob[offset:offset + len(before)] != before:
            raise ValueError(f"Unexpected presentation instructions: {target['name']}")
        blob[offset:offset + len(after)] = after
    return bytes(blob), list(TARGETS)


def restore_render_sync(blob):
    _check_contexts(blob)
    original = bytearray(blob)
    for target in TARGETS:
        offset = file_offset(blob, target["address"])
        before, after = bytes.fromhex(target["original"]), bytes.fromhex(target["replacement"])
        if blob[offset:offset + len(after)] != after:
            raise ValueError(f"Buffered presentation patch missing: {target['name']}")
        original[offset:offset + len(before)] = before
    if hashlib.sha256(original).hexdigest() != XR_SHA256:
        raise ValueError("Unrelated XR plugin data changed")
    return bytes(original)


def verify_render_sync(blob):
    restore_render_sync(blob)
    return {
        "enabled": True,
        "single_buffer_requests_forced_off": True,
        "requested_nr_screen_buffer_mode": 2,
        "stereo_frame_buffer_mode_unchanged": True,
        "native_patch_regions": len(TARGETS),
        "hardware_validation": "pending; offline candidate only",
    }
