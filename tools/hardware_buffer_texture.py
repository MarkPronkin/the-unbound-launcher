"""Build the recents thumbnail library used by the launcher's Unity client."""
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ENTRY = "lib/arm64-v8a/libHardwareBufferTexture.so"
SOURCE = ROOT / "native/hardware-buffer-texture/library.c"
SAMPLING_MARK = b"Recents preview texture uses linear sampling"
ALLOWED_NEEDED = {"libc.so", "liblog.so", "libEGL.so", "libGLESv2.so", "libandroid.so", "libm.so", "libdl.so"}
EXPORTED = {"JNI_OnLoad", "TransformHardwareBufferToTexture", "DeleteNativeTexture"}


def ndk_clang():
    roots = []
    for name in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        if os.environ.get(name):
            roots.append(Path(os.environ[name]))
    roots.append(Path("/opt/homebrew/share/android-commandlinetools/ndk/23.2.8568313"))
    sdk_ndk = Path.home() / "Library/Android/sdk/ndk"
    if sdk_ndk.is_dir():
        roots.extend(sorted((path for path in sdk_ndk.iterdir() if path.is_dir()), reverse=True))
    for root in roots:
        prebuilt = root / "toolchains/llvm/prebuilt"
        if not prebuilt.is_dir():
            continue
        for host in sorted(prebuilt.iterdir()):
            clang = host / "bin/aarch64-linux-android29-clang"
            if clang.is_file():
                return clang
    raise ValueError("Android NDK clang for arm64 API 29 was not found")


def build_library(destination):
    """Compile the replacement and require 16 KB LOAD alignment plus the three exports."""
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    clang = ndk_clang()
    subprocess.run([
        str(clang), "-shared", "-fPIC", "-O2", "-std=c11", "-Wall", "-Wextra", "-Werror",
        "-fvisibility=hidden", "-Wl,-soname,libHardwareBufferTexture.so", "-Wl,--no-undefined",
        "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384", "-Wl,-z,noexecstack",
        "-llog", "-lEGL", "-lGLESv2", "-landroid", "-o", str(destination), str(SOURCE),
    ], check=True)
    verify_library(destination, clang.parent / "llvm-readelf", clang.parent / "llvm-nm")
    return destination.read_bytes()


def verify_library(path, readelf, nm):
    path = Path(path)
    blob = path.read_bytes()
    if SAMPLING_MARK not in blob or b"Failed to create EGLImage." not in blob:
        raise ValueError("Thumbnail library is missing its sampling or failure text")
    headers = subprocess.check_output([str(readelf), "-l", str(path)], text=True)
    loads = [line for line in headers.splitlines() if re.search(r"\bLOAD\b", line)]
    aligns = []
    for line in loads:
        token = line.split()[-1]
        aligns.append(int(token, 16 if token.lower().startswith("0x") else 10))
    if not aligns or any(align < 16384 for align in aligns):
        raise ValueError("Thumbnail library LOAD segments are not 16 KB aligned")
    needed = set(re.findall(r"Shared library: \[([^\]]+)\]", subprocess.check_output([str(readelf), "-d", str(path)], text=True)))
    if not needed or not needed <= ALLOWED_NEEDED:
        raise ValueError(f"Unexpected thumbnail library dependencies: {sorted(needed)}")
    defined = set()
    for line in subprocess.check_output([str(nm), "-D", "--defined-only", str(path)], text=True).splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[-2] == "T":
            defined.add(parts[-1])
    if defined != EXPORTED:
        raise ValueError(f"Unexpected thumbnail exports: {sorted(defined)}")
