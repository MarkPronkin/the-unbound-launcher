"""Remove the separate single high-performance app restriction in both launch paths."""
import hashlib

from xreal_telemetry import file_offset

# After the recording-duration patch, before package identity relocation.
BASELINE_SHA256 = "4c79c73de496efe2e4113e591f8747e996d101deaabe77b3987eb93869e5d790"
# Both commands gather existing multilayer windows, then consult
# Application2DManager.APPLICATIONN_LIMIT_MULTILAYER_APP_COUNT. Always take the
# existing unrestricted launch branch instead of RemindCloseMultilayerAppWindow
# ("Opening a new app will close the current high-performance app"). Keep the
# earlier ten-window checks, app metadata, surfaces and protected-content paths.
TARGETS = (
    {"name": "EPHNativeAppOpenCMD.OpenEPHApp: skip high-performance replacement",
     "address": 0x33C3B9C, "original": "c8020034", "replacement": "16000014"},
    {"name": "EPHIntentAppOpenCMD.OpenEPHApp: skip high-performance replacement",
     "address": 0x33BF554, "original": "a8070034", "replacement": "3d000014"},
)


def patch_high_performance_apps(original, *, restore=False):
    if not restore and hashlib.sha256(original).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unexpected native library for high-performance app patch")
    blob = bytearray(original)
    for target in TARGETS:
        before, after = bytes.fromhex(target["original"]), bytes.fromhex(target["replacement"])
        expected, replacement = (after, before) if restore else (before, after)
        offset = file_offset(blob, target["address"])
        if blob[offset:offset + 4] != expected:
            raise ValueError("Unexpected high-performance app instructions: " + target["name"])
        blob[offset:offset + 4] = replacement
    if restore and hashlib.sha256(blob).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unrelated native changes in high-performance app patch")
    return bytes(blob)


def verify_high_performance_apps(blob):
    patch_high_performance_apps(blob, restore=True)
    return {"separate_high_performance_app_limit_removed": True,
            "normal_and_intent_launches_patched": True,
            "total_app_window_limit": 10,
            "app_metadata_and_surface_selection_preserved": True,
            "patch_bytes": 8,
            "hardware_validation": "pending"}
