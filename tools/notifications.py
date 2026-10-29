"""Use Unity's native-app notification action without system-only intent inspection."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def patch_notifications(original, build, java, env=None, *, restore=False, baseline=None):
    build = Path(build)
    build.mkdir(parents=True, exist_ok=True)
    source, reference, output = (build / name for name in ('input.dex', 'baseline.dex', 'output.dex'))
    source.write_bytes(original)
    reference.write_bytes(original if baseline is None else baseline)
    command = [str(Path(java) / 'bin/java'), '--class-path',
               str(ROOT / 'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar'),
               str(ROOT / 'tools/PatchNotificationsDex.java')]
    subprocess.run([*command, 'restore' if restore else 'patch', str(source), str(output), str(reference)], check=True, env=env)
    if not restore:
        restored = build / 'restored.dex'
        subprocess.run([*command, 'restore', str(output), str(restored), str(reference)], check=True, env=env)
        if restored.read_bytes() != original:
            raise ValueError('Notification patch did not restore the exact original DEX')
    return output.read_bytes()
