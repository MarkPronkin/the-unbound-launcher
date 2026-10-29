#!/usr/bin/env python3
"""Render the APK's real app-copy fragments through a SurfaceTexture without installing it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

from app_color import APP_SHADER, OES_SHADERS

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'artifacts/tools/python'))
import UnityPy
from UnityPy.helpers import CompressionHelper
from UnityPy.streams import EndianBinaryReader
from UnityPy.export.ShaderConverter import ShaderProgram


def programs(raw):
    obj = UnityPy.load(raw).objects[0]
    tree = obj.read_typetree()
    blob = CompressionHelper.decompress_lz4(bytes(tree['compressedBlob']), tree['decompressedLengths'][0][0])
    return ShaderProgram(EndianBinaryReader(blob, endian='<'), obj.version).m_SubPrograms


def fragment(program):
    version, rest = bytes(program.m_ProgramCode).decode().split('\n', 1)
    return version + '\n#define FRAGMENT 1\n' + rest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--serial', default='emulator-5580')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/app-color-runtime')
    parser.add_argument('--allow-physical', action='store_true', help='Explicitly allow the isolated render fixture on a physical phone; no APK installation')
    args = parser.parse_args()
    physical=not bool(re.fullmatch(r'emulator-\d+', args.serial))
    if physical and not args.allow_physical:
        parser.error('Physical-phone diagnostics require --allow-physical; the APK is not installed')
    output = args.output.resolve()
    build = output / 'build'
    build.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / 'Library/Android/sdk'
    android = sdk / 'platforms/android-34/android.jar'
    android_tools = sdk / 'build-tools/36.0.0'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))

    def run(*command):
        result = subprocess.run(list(map(str, command)), capture_output=True, text=True, env=env, timeout=120)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout.strip()

    paths = []
    with zipfile.ZipFile(args.apk) as apk:
        with zipfile.ZipFile(build / 'runtime.jar', 'w') as runtime:
            # Load the exact AppColorCompat DEX installed by this candidate.
            runtime.writestr('classes.dex', apk.read('classes4.dex'))
        control = None
        double_decode = None
        for name in (APP_SHADER, *OES_SHADERS):
            count = 0
            for index, program in enumerate(programs(apk.read(name))):
                if int(program.m_ProgramType) != 4:
                    continue
                if name == APP_SHADER and 'GAMMA_TO_LINEAR' not in program.m_Keywords:
                    if control is None:
                        control = fragment(program)
                    continue
                path = build / f'{Path(name).name}-{index}.frag'
                source = fragment(program)
                path.write_text(source)
                if name == APP_SHADER and double_decode is None:
                    needle = 'GammaToLinearSpace(result)'
                    if source.count(needle) != 1:
                        raise ValueError('Unexpected copy fragment for double-decode control')
                    double_decode = source.replace(needle, 'GammaToLinearSpace(GammaToLinearSpace(result))')
                paths.append(path)
                count += 1
            if count != (24 if name == APP_SHADER else 6):
                raise ValueError('Unexpected fallback program count in ' + name)
        if control is None or double_decode is None:
            raise ValueError('No hardware pass-through control found')
        path = build / 'double-decode-control.frag'
        path.write_text(double_decode)
        paths.append(path)
        path = build / 'missing-decode-control.frag'
        path.write_text(control)
        paths.append(path)
    classes = build / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    run(java / 'bin/javac', '--release', '8', '-cp', android, '-d', classes,
        ROOT / 'tools/AppColorDeviceChecks.java', ROOT / 'port-client/src/dev/beampro/port/AppColorCompat.java')
    # Only the test class goes in the fixture; the helper is loaded from the APK.
    run(java / 'bin/jar', 'cf', build / 'checks.jar', '-C', classes, 'dev/beampro/port/AppColorDeviceChecks.class')
    dex = build / 'dex'
    dex.mkdir(exist_ok=True)
    run(android_tools / 'd8', '--min-api', '33', '--lib', android, '--output', dex, build / 'checks.jar')
    with zipfile.ZipFile(build / 'device-checks.jar', 'w') as jar:
        jar.write(dex / 'classes.dex', 'classes.dex')
    adb = [sdk / 'platform-tools/adb', '-s', args.serial]
    remote = '/data/local/tmp/unbound-app-color-checks'
    run(*adb, 'shell', 'mkdir', '-p', remote)
    run(*adb, 'push', build / 'runtime.jar', build / 'device-checks.jar', *paths, remote + '/')
    command = (f'CLASSPATH={remote}/runtime.jar:{remote}/device-checks.jar '
               'app_process / dev.beampro.port.AppColorDeviceChecks '
               + ' '.join(remote + '/' + path.name for path in paths))
    result = subprocess.run([*map(str, adb), 'shell', command], capture_output=True, text=True, timeout=120)
    log = result.stdout + result.stderr
    (output / 'runtime-checks.log').write_text(log)
    samples = [int(value) for value in re.findall(r'max_rgb_error=(\d+)', log)]
    checked = re.search(r'PASS app color RGB checks: (\d+)', log)
    success = result.returncode == 0 and checked is not None and len(samples) == 38
    with args.apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    report = {
        'success': success, 'rgb_component_checks': int(checked[1]) if checked else 0,
        'software_fragments': len(samples) - 2 if len(samples) >= 2 else 0,
        'max_rgb_error': max(samples[:-2]) if len(samples) > 2 else None,
        'double_decode_control_max_error': samples[-2] if len(samples) >= 2 else None,
        'missing_decode_control_max_error': samples[-1] if samples else None,
        'apk_sha256': digest,
        'method': 'Actual APK copy fragments and AppColorCompat DEX; Canvas RGBA SurfaceTexture to sRGB GLES framebuffer',
        'physical_phone_installation': False, 'phone_driver_and_glasses_validation': 'pending',
        'executed_on_physical_phone': physical,
    }
    (output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(log, end='')
    if not success:
        raise AssertionError('App color render regression failed; see ' + str(output))


if __name__ == '__main__':
    main()
