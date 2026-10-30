"""Omit the branded overlay from newly captured videos."""
import hashlib

from xreal_telemetry import file_offset

# After anchored-window placement, before package identity relocation.
BASELINE_SHA256 = "cc9aaf5ad2a557906174fe8f237e01d663db52a3125a148b854e547c41e96c04"
TARGETS = (
    {"name": "VideoRecordView.Start: pass no sprite to the video logo builder",
     "address": 0xF0B22C, "original": "e10318aa", "replacement": "e1031faa"},
)


def patch_recording_logo(original, *, restore=False):
    """Use CaptureToolBase.SetLogo's existing null-sprite early return.

    Only the video builder's sprite argument changes. Keep the shared photo
    builder, logo assets, resolution, blend mode, encoder, audio and saving.
    """
    if not restore and hashlib.sha256(original).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unexpected native library for recording logo patch")
    blob = bytearray(original)
    for target in TARGETS:
        before, after = bytes.fromhex(target["original"]), bytes.fromhex(target["replacement"])
        expected, replacement = (after, before) if restore else (before, after)
        offset = file_offset(blob, target["address"])
        if blob[offset:offset + len(expected)] != expected:
            raise ValueError("Unexpected recording logo instructions: " + target["name"])
        blob[offset:offset + len(expected)] = replacement
    if restore and hashlib.sha256(blob).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unrelated native changes in recording logo patch")
    return bytes(blob)


def verify_recording_logo(blob):
    patch_recording_logo(blob, restore=True)
    return {"video_logo_overlay_removed": True,
            "scope": "New Eye camera and AR screen recordings",
            "existing_null_sprite_path_used": True,
            "photo_capture_and_logo_assets_preserved": True,
            "encoder_audio_resolution_and_saving_preserved": True,
            "patch_bytes": 4,
            "hardware_validation": "pending"}
