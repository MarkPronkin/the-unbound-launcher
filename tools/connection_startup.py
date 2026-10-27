"""Patch and restore the port's connection recovery against a pinned SDK DEX."""
from pathlib import Path
import subprocess
from startup_theme import patch_activity_theme_manifest

ROOT = Path(__file__).resolve().parents[1]
CONNECTION_ACTIVITY = 'ai.nreal.nebula.activity.splash.ota.OTAActivity'


def patch_connection_theme_manifest(manifest):
    return patch_activity_theme_manifest(manifest, CONNECTION_ACTIVITY, unlock_orientation=True)


def patch_connection_startup(original, build, java, env=None, *, restore=False, baseline=None):
    build = Path(build)
    build.mkdir(parents=True, exist_ok=True)
    source, reference, output = (build / name for name in ('input.dex', 'baseline.dex', 'output.dex'))
    source.write_bytes(original)
    reference.write_bytes(original if baseline is None else baseline)
    command = [str(Path(java) / 'bin/java'), '--class-path',
               str(ROOT / 'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar'),
               str(ROOT / 'tools/PatchConnectionStartupDex.java')]
    subprocess.run([*command, 'restore' if restore else 'patch', str(source), str(output), str(reference)], check=True, env=env)
    if not restore:
        restored = build / 'restored.dex'
        subprocess.run([*command, 'restore', str(output), str(restored), str(reference)], check=True, env=env)
        if restored.read_bytes() != original:
            raise ValueError('Connection recovery did not restore the exact original DEX')
    return output.read_bytes()
