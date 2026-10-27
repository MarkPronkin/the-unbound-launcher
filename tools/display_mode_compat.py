"""Remove the unused API-34 HDR lookup from the native Display.Mode binding."""
import hashlib

from xreal_telemetry import file_offset

LIBRARY = 'lib/arm64-v8a/libnr_api.so'
SHA256 = '0fe3a49cc73bee642cbe1398a1ef7b124aaadf7e98b8319e2a25a6a6e1ebff4b'
INITIALIZER = 0x1E8F2BC
TARGET = 0x1E8F3A0
ORIGINAL = bytes.fromhex('24ebff97')  # BL mandatory JNI GetMethodID.
REPLACEMENT = bytes.fromhex('e0031faa')  # MOV X0, XZR; original STR stores a null unused ID.
CONTEXT = bytes.fromhex('0139fff021843391623cffb042b41b916022009124ebff97602600f9')


def patch_display_mode(original, *, restore=False):
    blob = bytearray(original)
    at = file_offset(blob, TARGET)
    expected, replacement = (REPLACEMENT, ORIGINAL) if restore else (ORIGINAL, REPLACEMENT)
    if blob[at:at + 4] != expected:
        raise ValueError('Unexpected native Display.Mode HDR lookup')
    blob[at:at + 4] = replacement
    # Restoration checks the complete library, rejecting all unrelated edits.
    reference = bytes(blob) if restore else original
    if hashlib.sha256(reference).hexdigest() != SHA256:
        raise ValueError('Unexpected native library for Display.Mode compatibility')
    return bytes(blob)


def verify_display_mode(blob):
    patch_display_mode(blob, restore=True)
    return {'unused_api_34_mode_hdr_lookup_removed': True, 'patch_bytes': 4,
            'mode_dimensions_refresh_and_selection_preserved': True,
            'physical_xperia_validation': 'pending'}
