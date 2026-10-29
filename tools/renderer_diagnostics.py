"""Build opt-in, read-only Unity renderer geometry diagnostics."""
from pathlib import Path
import subprocess
from hardware_buffer_texture import ROOT, ndk_clang

ENTRY = "lib/arm64-v8a/libUnboundRendererDiagnostics.so"
SOURCE = ROOT / "native/renderer-diagnostics/library.c"
MARKER = b"Renderer snapshot schema=1"


def build_library(destination):
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(ndk_clang()), "-shared", "-fPIC", "-O2", "-std=c11", "-Wall", "-Wextra", "-Werror",
                    "-fvisibility=hidden", "-Wl,-soname,libUnboundRendererDiagnostics.so", "-Wl,--no-undefined",
                    "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384", "-Wl,-z,noexecstack",
                    "-ldl", "-o", str(destination), str(SOURCE)], check=True)
    value = destination.read_bytes()
    if MARKER not in value:
        raise ValueError("Renderer diagnostic library is missing its schema marker")
    return value
