#!/usr/bin/env python3
"""Verify the APK's actual app-surface policy DEX in an isolated Android app."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.beampro.surfacechecks'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--serial', default='emulator-5580')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('The isolated fixture only installs on an emulator')
    output = args.output.resolve()
    build = output / 'build'
    build.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / 'Library/Android/sdk'
    platform = sdk / 'platforms/android-34/android.jar'
    android_tools = sdk / 'build-tools/36.0.0'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))
    adb = [sdk / 'platform-tools/adb', '-s', args.serial]

    def run(*command):
        result = subprocess.run(list(map(str, command)), capture_output=True, text=True, env=env, timeout=60)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout.strip()

    if run(*adb, 'shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise RuntimeError('Refusing a non-emulator Android target')
    manifest = build / 'AndroidManifest.xml'
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        f'package="{PACKAGE}"><uses-sdk android:minSdkVersion="30" android:targetSdkVersion="33"/>'
        '<application android:label="App surface checks"/>'
        '<instrumentation android:name="dev.beampro.port.AppSurfaceDeviceChecks" '
        f'android:targetPackage="{PACKAGE}"/></manifest>')
    run(android_tools / 'aapt2', 'link', '-I', platform, '--manifest', manifest, '-o', build / 'base.apk')
    classes = build / 'classes'
    classes.mkdir(exist_ok=True)
    # Compile policy sources for symbols only. The fixture loads the unmodified
    # classes4.dex from the candidate, including its D8-generated lambda classes.
    run(java / 'bin/javac', '--release', '8', '-classpath', platform, '-d', classes,
        ROOT / 'port-client/src/dev/beampro/port/AppColorCompat.java',
        ROOT / 'port-client/src/dev/beampro/port/AppSurfacePolicy.java', ROOT / 'tools/AppSurfaceDeviceChecks.java')
    with zipfile.ZipFile(build / 'fixture.jar', 'w') as jar:
        for path in (classes / 'dev/beampro/port').glob('AppSurfaceDeviceChecks*.class'):
            jar.write(path, path.relative_to(classes).as_posix())
    dex = build / 'dex'
    dex.mkdir(exist_ok=True)
    run(android_tools / 'd8', '--min-api', '30', '--lib', platform, '--output', dex, build / 'fixture.jar')
    with zipfile.ZipFile(build / 'base.apk') as base, zipfile.ZipFile(args.apk) as production, \
            zipfile.ZipFile(build / 'unsigned.apk', 'w') as target:
        for info in base.infolist():
            target.writestr(info, base.read(info))
        target.write(dex / 'classes.dex', 'classes.dex')
        target.writestr('classes2.dex', production.read('classes4.dex'), compress_type=zipfile.ZIP_DEFLATED)
    run(android_tools / 'zipalign', '-f', '4', build / 'unsigned.apk', build / 'aligned.apk')
    fixture = build / 'checks.apk'
    run(android_tools / 'apksigner', 'sign', '--ks', ROOT / 'artifacts/probe/local-debug.p12',
        '--ks-key-alias', 'port-probe', '--ks-pass', 'pass:android', '--out', fixture, build / 'aligned.apk')
    try:
        print(run(*adb, 'install', '--no-incremental', '-r', fixture), flush=True)
        log = run(*adb, 'shell', 'am', 'instrument', '-w', PACKAGE + '/dev.beampro.port.AppSurfaceDeviceChecks')
        (output / 'runtime-checks.log').write_text(log + '\n')
        match = re.search(r'PASS app surface Android checks: (\d+)', log)
        passed = bool(match) and 'error=' not in log and 'Process crashed' not in log
        with args.apk.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        report = {'passed': passed, 'checks': int(match[1]) if match else 0, 'apk_sha256': digest,
                  'serial': args.serial, 'method': 'Unmodified candidate policy DEX, app UID, Android EGL/MediaDrm/AtomicFile',
                  'physical_phone_installation': False, 'glasses_validation': 'pending'}
        (output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
        print(log, flush=True)
        if not passed:
            raise AssertionError('App surface Android regression failed')
    finally:
        print(run(*adb, 'uninstall', PACKAGE), flush=True)


if __name__ == '__main__':
    main()
