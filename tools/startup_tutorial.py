"""Skip the glasses launcher's first-use tutorial while retaining manual entry."""
import hashlib

from xreal_telemetry import file_offset

# Pinned launcher library after patch_color_native, including prior native fixes.
BASELINE_SHA256 = "fd1b5def17de85e53b2c4fb87dcbb461a71b508e5233f859d80b4cb27ccb5e9c"
NEED_OPEN_BASIC_TUTORIAL = 0x29A0D20
ORIGINAL = bytes.fromhex("ff8301d1f96302a9")
REPLACEMENT = bytes.fromhex("e0031f2ac0035fd6")  # mov w0, wzr; ret


def patch_startup_tutorial(original, *, restore=False):
    """Make EPTHelper.NeedOpenBasicTutorial false for every device and data state.

    Both loading and tutorial startup consult this predicate. Returning false
    uses the existing normal startup/controller-enable path without writing
    fabricated tutorial completion data. Manual tutorial notifications still
    call EnterLobby/StartBasic directly.
    """
    if not restore and hashlib.sha256(original).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unexpected native library for startup tutorial patch")
    offset = file_offset(original, NEED_OPEN_BASIC_TUTORIAL)
    expected, replacement = (REPLACEMENT, ORIGINAL) if restore else (ORIGINAL, REPLACEMENT)
    if original[offset:offset + len(expected)] != expected:
        raise ValueError("Unexpected first-use tutorial predicate instructions")
    blob = bytearray(original)
    blob[offset:offset + len(expected)] = replacement
    if restore and hashlib.sha256(blob).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unrelated native changes in startup tutorial patch")
    return bytes(blob)
