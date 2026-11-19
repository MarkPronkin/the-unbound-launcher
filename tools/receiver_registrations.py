"""Adapt the bundled SDK's non-system broadcast registrations for Android 14+."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def patch_receivers(name, dex, build, java, *, restore=False):
    build.mkdir(parents=True, exist_ok=True)
    source, result = build / "input.dex", build / "output.dex"
    source.write_bytes(dex)
    subprocess.run([java / "bin/java", "--class-path", ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar",
                    ROOT / "tools/PatchReceiverRegistrations.java", name, "restore" if restore else "patch",
                    source, result], check=True)
    return result.read_bytes()
