"""Keep synthetic trackpad clicks pressed long enough for Unity input polling."""
import hashlib
import struct
from pathlib import Path
import subprocess

from xreal_telemetry import file_offset

BASELINE_SHA256 = "1a135ac2cce68f0d78d6df1c4496af7d90247cf048dd2e4b1a3d1e05c64bb292"
TAP_RELEASE = 0x3088E94
WRAPPER = 0x12CA544
QUEUE = 0x1646A74
DELAYED_QUEUE = 0x163CA78
RELEASE_DELAY_SECONDS = 0.0625
ROOT = Path(__file__).resolve().parents[1]


def branch(source, target):
    distance = target - source
    if distance % 4 or not -(1 << 27) <= distance < (1 << 27):
        raise ValueError("Unaligned or out-of-range branch")
    return struct.pack("<I", 0x14000000 | ((distance >> 2) & 0x3FFFFFF))


# x0 = dispatcher, x1 = the existing release Action, x2 = null cancellation
# token. The original tail has already restored its stack and callee-saved
# registers. Tail-call the existing delayed dispatcher with a null MethodInfo.
# The wrapper follows the retained RET in a disabled analytics method.
WRAPPER_CODE = bytes.fromhex("08b0a7520001271ee3031faa") + branch(WRAPPER + 12, DELAYED_QUEUE)
TARGETS = (
    {"name": "Trackpad synthetic tap: schedule release after input polling",
     "address": TAP_RELEASE, "original": branch(TAP_RELEASE, QUEUE),
     "replacement": branch(TAP_RELEASE, WRAPPER)},
    {"name": "Delayed tap release wrapper after disabled analytics RET",
     "address": WRAPPER, "original": bytes.fromhex("f55301a9f37b02a934b101f088824d39"),
     "replacement": WRAPPER_CODE},
)


def patch_trackpad_taps(original):
    if hashlib.sha256(original).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unexpected Unity library; apply the verified app-menu patch first")
    blob = bytearray(original)
    guard = file_offset(blob, WRAPPER - 4)
    if blob[guard:guard + 4] != bytes.fromhex("c0035fd6"):
        raise ValueError("Trackpad wrapper requires the retained analytics RET")
    for target in TARGETS:
        offset = file_offset(blob, target["address"])
        before, after = target["original"], target["replacement"]
        if len(before) != len(after) or blob[offset:offset + len(before)] != before:
            raise ValueError(f"Unexpected trackpad instructions: {target['name']}")
        blob[offset:offset + len(after)] = after
    return bytes(blob)


def restore_trackpad_taps(patched):
    blob = bytearray(patched)
    for target in TARGETS:
        offset = file_offset(blob, target["address"])
        after = target["replacement"]
        if blob[offset:offset + len(after)] != after:
            raise ValueError(f"Missing trackpad patch: {target['name']}")
        blob[offset:offset + len(after)] = target["original"]
    if patch_trackpad_taps(blob) != patched:
        raise ValueError("Unexpected trackpad patch scope")
    return bytes(blob)


def patch_trackpad_dex(original, build, java, env):
    build = Path(build)
    before, patched, restored = [build / ("trackpad-" + name + ".dex") for name in ("before", "patched", "restored")]
    before.write_bytes(original)
    command = [str(Path(java) / "bin/java"), "--class-path",
               str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"), str(ROOT / "tools/PatchTrackpadDex.java")]
    for mode, source, target in (("patch", before, patched), ("restore", patched, restored)):
        subprocess.run([*command, mode, str(source), str(target)], check=True, env=env)
    if restored.read_bytes() != original:
        raise ValueError("Trackpad callbacks did not restore the exact original DEX")
    return patched.read_bytes()
