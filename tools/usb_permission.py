"""Patch the SDK USB request while auditing every restored DEX class."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def patch_usb_permission(original, build, java, env=None, *, restore=False, baseline=None):
    return _patch(original, build, java, env, restore, baseline, 'PatchUsbPermissionDex.java')


def patch_controller_usb_permission(original, build, java, env=None, *, restore=False, baseline=None):
    return _patch(original, build, java, env, restore, baseline, 'PatchControllerUsbDex.java')


def _patch(original, build, java, env, restore, baseline, tool):
    build = Path(build)
    build.mkdir(parents=True, exist_ok=True)
    source = build / 'usb-input.dex'
    reference = build / 'usb-baseline.dex'
    output = build / 'usb-output.dex'
    source.write_bytes(original)
    reference.write_bytes(original if baseline is None else baseline)
    command = [str(Path(java) / 'bin/java'), '--class-path',
               str(ROOT / 'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar'),
               str(ROOT / 'tools' / tool)]
    subprocess.run([*command, 'restore' if restore else 'patch', str(source), str(output), str(reference)], check=True, env=env)
    result = output.read_bytes()
    if not restore:
        restored = build / 'usb-restored.dex'
        subprocess.run([*command, 'restore', str(output), str(restored), str(reference)], check=True, env=env)
        if restored.read_bytes() != original:
            raise ValueError('USB hook did not restore the exact baseline')
    return result
