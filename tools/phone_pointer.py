"""Build the screen-off pointer control without changing the Unity renderer binary."""
from pathlib import Path
import subprocess

from hardware_buffer_texture import ROOT, ndk_clang

ENTRY = "lib/arm64-v8a/libUnboundPhonePointer.so"
SOURCE = ROOT / "native/phone-pointer/library.c"
MARKER = b"Motion pointer %s: flags=%d saved=%d"


def build_library(destination):
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(ndk_clang()), "-shared", "-fPIC", "-O2", "-std=c11", "-Wall", "-Wextra", "-Werror",
                    "-fvisibility=hidden", "-Wl,-soname,libUnboundPhonePointer.so", "-Wl,--no-undefined",
                    "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384", "-Wl,-z,noexecstack",
                    "-llog", "-ldl", "-o", str(destination), str(SOURCE)], check=True)
    value = destination.read_bytes()
    if MARKER not in value:
        raise ValueError("Pointer library is missing its state readback log")
    return value
