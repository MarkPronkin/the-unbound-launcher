"""Remove the launcher's fixed video deadline and show elapsed recording time."""
import hashlib

from xreal_telemetry import file_offset

# After color, tutorial, stereo, overlay and app-surface patches, before identity.
BASELINE_SHA256 = "02ab031416fb329f0df8fb0c4e1b7bdf2542cd940c9c26a1f027c1e9e884258f"
BEGIN = 0xF0A018
UPDATE = 0xF0A0AC
TARGETS = (
    {"name": "RecordStatusBar.Begin: store the start time without adding the duration",
     "address": 0xF0A07C, "original": "0028211e", "replacement": "1f2003d5"},
    {"name": "RecordStatusBar.Update: elapsed = now - start",
     "address": 0xF0A0C8, "original": "0039201e", "replacement": "0038281e"},
    {"name": "RecordStatusBar.Update: skip the deadline stop callback",
     "address": 0xF0A0D4, "original": "08010054", "replacement": "08000014"},
)


def patch_recording_duration(original, *, restore=False):
    """Remove the shared AR video timer used by Eye and screen recording.

    Keep the stock formatter, blinking badge, Stop button, capture/encoder and
    interruption cleanup. This removes the time limit rather than substituting
    a larger deadline; serialized and debug durations cannot stop recording.
    """
    if not restore and hashlib.sha256(original).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unexpected native library for recording duration patch")
    blob = bytearray(original)
    for target in TARGETS:
        before, after = bytes.fromhex(target["original"]), bytes.fromhex(target["replacement"])
        expected, replacement = (after, before) if restore else (before, after)
        offset = file_offset(blob, target["address"])
        if blob[offset:offset + len(expected)] != expected:
            raise ValueError("Unexpected recording duration instructions: " + target["name"])
        blob[offset:offset + len(expected)] = replacement
    if restore and hashlib.sha256(blob).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unrelated native changes in recording duration patch")
    return bytes(blob)


def verify_recording_duration(blob):
    patch_recording_duration(blob, restore=True)
    return {"fixed_time_limit_removed": True, "status_time": "elapsed",
            "shared_eye_and_ar_screen_recording": True, "patch_bytes": 12,
            "manual_stop_and_interruption_cleanup_preserved": True,
            "encoder_and_phone_storage_path_unchanged": True,
            "hardware_validation": "user-confirmed recording time-limit removal"}
