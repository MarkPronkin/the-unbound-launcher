"""Relocate the installed app identity, retaining original Java/JNI class names.

The equal-length ID keeps Unity metadata and native literal offsets unchanged.
DEX files must be rewritten through dexlib2: editing their string bytes in place
would invalidate the sorted string/type/member tables.
"""
from pathlib import Path
import subprocess

LEGACY_PACKAGE = "com.xreal.evapro.nebula"
PACKAGE = "dev.markpronkin.unbound"
AUTHORITY = PACKAGE + ".unbound.bridge"
METADATA = "assets/bin/Data/Managed/Metadata/global-metadata.dat"
NATIVE = "lib/arm64-v8a/libil2cpp.so"
BINARY_ENCODINGS = {
    "AndroidManifest.xml": "utf-16le",
    "resources.arsc": "utf-16le",
    METADATA: "utf-8",
    NATIVE: "utf-8",
}
assert len(PACKAGE) == len(LEGACY_PACKAGE) == 23
ROOT = Path(__file__).resolve().parents[1]


def patch_binary_identity(name, original, *, restore=False):
    """Change only fixed-width app-ID literals, never classes or resource IDs."""
    encoding = BINARY_ENCODINGS[name]
    before, after = (PACKAGE, LEGACY_PACKAGE) if restore else (LEGACY_PACKAGE, PACKAGE)
    before, after = before.encode(encoding), after.encode(encoding)
    result = original.replace(before, after)
    if len(result) != len(original):
        raise ValueError("Package relocation changed a binary payload's length")
    return result


def repackage_dex(original, build, java, env=None, *, restore=False):
    before, after = (PACKAGE, LEGACY_PACKAGE) if restore else (LEGACY_PACKAGE, PACKAGE)
    if before.encode() not in original:
        return original
    build = Path(build)
    build.mkdir(parents=True, exist_ok=True)
    source, output = build / "identity-input.dex", build / "identity-output.dex"
    source.write_bytes(original)
    subprocess.run([str(Path(java) / "bin/java"), "--class-path",
                    str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
                    str(ROOT / "tools/RepackageDex.java"), str(source), str(output), before, after],
                   check=True, env=env)
    result = output.read_bytes()
    if before.encode() in result:
        raise ValueError("An old package literal remains in the relocated DEX")
    return result
