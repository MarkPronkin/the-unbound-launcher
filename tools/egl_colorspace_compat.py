"""Use the OpenGL colorspace attribute in the SDK's type-1 GLES profile."""
import hashlib

from xreal_telemetry import file_offset

LIBRARY = 'lib/arm64-v8a/libnr_api.so'
# Input is the pinned SDK library with only display_mode_compat applied.
SHA256 = 'c7b5a0a483c27f76f283efb9d110170853e7594c1714734287bab69fa71493a3'
TARGET = 0x14B0688
ORIGINAL = (0x52800000 | (0x3087 << 5)).to_bytes(4, 'little')
REPLACEMENT = (0x52800000 | (0x309D << 5)).to_bytes(4, 'little')


def patch_egl_colorspace(original, *, restore=False):
    blob = bytearray(original)
    at = file_offset(blob, TARGET)
    expected, replacement = (REPLACEMENT, ORIGINAL) if restore else (ORIGINAL, REPLACEMENT)
    if blob[at:at + 4] != expected:
        raise ValueError('Unexpected native GLES colorspace attribute')
    blob[at:at + 4] = replacement
    reference = bytes(blob) if restore else original
    if hashlib.sha256(reference).hexdigest() != SHA256:
        raise ValueError('Unexpected native library for EGL colorspace compatibility')
    return bytes(blob)


def verify_egl_colorspace(blob):
    patch_egl_colorspace(blob, restore=True)
    return {'gles_colorspace_attribute_corrected': True, 'patch_bytes': 4,
            'colorspace_values_buffer_modes_and_protection_preserved': True,
            'physical_oppo_validation': 'pending'}
