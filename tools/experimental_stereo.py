"""Audited opt-in mono-to-SBS filters for the Java SDK and Unity home selector."""
import hashlib
import json
from pathlib import Path
import subprocess

from trackpad_taps import branch
from xreal_telemetry import file_offset

ROOT = Path(__file__).resolve().parents[1]


def native_spec():
    return json.loads(Path(__file__).with_name('experimental_stereo_native.json').read_text())


def patch_stereo_native(original, *, restore=False):
    spec = native_spec()
    if not restore and hashlib.sha256(original).hexdigest() != spec['baseline_sha256']:
        raise ValueError('Unexpected native library for experimental stereo')
    blob = bytearray(original)
    if blob[file_offset(blob, spec['wrapper'] - 4):file_offset(blob, spec['wrapper'])] != bytes.fromhex('c0035fd6'):
        raise ValueError('Experimental stereo requires the disabled analytics RET')
    targets = [(spec['return'], bytes.fromhex('c0035fd6'), branch(spec['return'], spec['wrapper'])),
               (spec['wrapper'], bytes.fromhex(spec['original']), bytes.fromhex(spec['replacement']))]
    for address, before, after in targets:
        at = file_offset(blob, address)
        expected, replacement = (after, before) if restore else (before, after)
        if blob[at:at + len(expected)] != expected:
            raise ValueError('Experimental stereo native instructions differ')
        blob[at:at + len(expected)] = replacement
    if restore and hashlib.sha256(blob).hexdigest() != spec['baseline_sha256']:
        raise ValueError('Unrelated native changes in experimental stereo')
    return bytes(blob)


def patch_stereo_dex(original, build, java, env=None, *, restore=False, baseline=None):
    build = Path(build)
    build.mkdir(parents=True, exist_ok=True)
    source, reference, output = (build / name for name in ('input.dex', 'baseline.dex', 'output.dex'))
    source.write_bytes(original)
    reference.write_bytes(original if baseline is None else baseline)
    command = [str(Path(java) / 'bin/java'), '--class-path',
               str(ROOT / 'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar'),
               str(ROOT / 'tools/PatchExperimentalStereoDex.java')]
    subprocess.run([*command, 'restore' if restore else 'patch', str(source), str(output), str(reference)], check=True, env=env)
    if not restore:
        restored = build / 'restored.dex'
        subprocess.run([*command, 'restore', str(output), str(restored), str(reference)], check=True, env=env)
        if restored.read_bytes() != original:
            raise ValueError('Experimental stereo did not restore the exact original DEX')
    return output.read_bytes()
