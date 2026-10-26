"""Patch and audit AR startup/SDK-view hooks against a pinned baseline DEX."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def patch_display_startup(original, build, java, env=None, *, restore=False, baseline=None):
    build = Path(build)
    build.mkdir(parents=True, exist_ok=True)
    source = build / "display-input.dex"
    reference = build / "display-baseline.dex"
    output = build / "display-output.dex"
    source.write_bytes(original)
    reference.write_bytes(original if baseline is None else baseline)
    command = [str(Path(java) / "bin/java"), "--class-path",
               str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
               str(ROOT / "tools/PatchDisplayStartupDex.java")]
    subprocess.run([*command, "restore" if restore else "patch", str(source), str(output), str(reference)], check=True, env=env)
    result = output.read_bytes()
    if not restore:
        restored = build / "display-restored.dex"
        subprocess.run([*command, "restore", str(output), str(restored), str(reference)], check=True, env=env)
        if restored.read_bytes() != original:
            raise ValueError("Display startup hooks failed to restore the exact baseline DEX")
    return result
