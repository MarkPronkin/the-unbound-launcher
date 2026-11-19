"""Route photo and recording cues through the optional capture sound guards."""
import hashlib
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
METADATA_SHA256 = 'c8c1b60a0449ef5bc258c61d0f89e12c454259af4817047c59a64fe1c5f0c381'


def patch_recording_sound(metadata):
    """Redirect the JNI class constructed by NativeMediaActionSound.Play(int)."""
    if hashlib.sha256(metadata).hexdigest() != METADATA_SHA256:
        raise ValueError('Unexpected Unity metadata for the recording sound guard')
    stock = b'android.media.MediaActionSound'
    guarded = b'dev.beampro.port.CaptureSounds'
    # A fixed-width class name preserves the Unity string literal table and offsets.
    if metadata.count(stock) != 1 or len(stock) != len(guarded):
        raise ValueError('Expected a single fixed-width media action sound literal')
    return metadata.replace(stock, guarded)


def patch_photo_sound(original, build, java, env=None, *, restore=False, baseline=None):
    build = Path(build)
    build.mkdir(parents=True, exist_ok=True)
    source, reference, output = (build / name for name in ('input.dex', 'baseline.dex', 'output.dex'))
    source.write_bytes(original)
    reference.write_bytes(original if baseline is None else baseline)
    command = [str(Path(java) / 'bin/java'), '--class-path',
               str(ROOT / 'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar'),
               str(ROOT / 'tools/PatchPhotoSoundDex.java')]
    subprocess.run([*command, 'restore' if restore else 'patch', str(source), str(output), str(reference)], check=True, env=env)
    if not restore:
        restored = build / 'restored.dex'
        subprocess.run([*command, 'restore', str(output), str(restored), str(reference)], check=True, env=env)
        if restored.read_bytes() != original:
            raise ValueError('Photo sound guard did not restore the exact original DEX')
    return output.read_bytes()
