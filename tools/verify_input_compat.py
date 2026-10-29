#!/usr/bin/env python3
"""Run an APK's real keyboard listener and pointer helper in an isolated emulator."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.beampro.inputcompatchecks'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--serial', default='emulator-5580')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--expect-bug', action='store_true')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('This fixture only installs on an isolated emulator')
    output = args.output.resolve()
    build = output / 'build'
    build.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / 'Library/Android/sdk'
    android_tools = sdk / 'build-tools/36.0.0'
    platform = sdk / 'platforms/android-34/android.jar'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))
    adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]

    def run(*command):
        result = subprocess.run(list(map(str, command)), capture_output=True, text=True, env=env, timeout=60)
        if result.returncode: raise RuntimeError(result.stdout + result.stderr)
        return result.stdout.strip()

    manifest = build / 'AndroidManifest.xml'
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        f'package="{PACKAGE}"><uses-sdk android:minSdkVersion="30" android:targetSdkVersion="33"/>'
        '<application android:label="Phone input checks" android:theme="@android:style/Theme.Material.Light.NoActionBar">'
        '<activity android:name=".InputCompatDeviceChecks$HostActivity" android:exported="false"/>'
        '<service android:name="com.xreal.evapro.JobService" android:exported="false" android:process=":service"/>'
        '<provider android:name="dev.beampro.adb.LocalBridgeProvider" '
        f'android:authorities="{PACKAGE}.flags" android:exported="false" android:process=":flags"/>'
        '</application><instrumentation android:name=".InputCompatDeviceChecks" '
        f'android:targetPackage="{PACKAGE}"/></manifest>')
    run(android_tools / 'aapt2', 'link', '-I', platform, '--manifest', manifest, '-o', build / 'base.apk')
    settings = build / 'SettingsCompat.java'
    # Redirect only the fixture's provider authority, preserving production adapter logic.
    settings.write_text((ROOT / 'port-client/src/dev/beampro/port/SettingsCompat.java').read_text()
                        .replace('dev.beampro.portprobe.bridge', PACKAGE + '.flags'))
    classes = build / 'classes'
    classes.mkdir(exist_ok=True)
    run(java / 'bin/javac', '--release', '8', '-classpath', platform, '-d', classes, settings,
        ROOT / 'port-client/src/dev/beampro/port/KeyboardCompat.java',
        ROOT / 'embedded-adb/src/dev/beampro/adb/LocalBridgeProvider.java', ROOT / 'tools/InputCompatDeviceChecks.java')
    run(java / 'bin/jar', 'cf', build / 'fixture.jar', '-C', classes, '.')
    dex = build / 'dex'
    dex.mkdir(exist_ok=True)
    run(android_tools / 'd8', '--min-api', '30', '--lib', platform, '--output', dex, build / 'fixture.jar')
    with zipfile.ZipFile(build / 'base.apk') as base, zipfile.ZipFile(args.apk) as production, \
            zipfile.ZipFile(build / 'unsigned.apk', 'w') as target:
        for info in base.infolist(): target.writestr(info, base.read(info))
        target.write(dex / 'classes.dex', 'classes.dex')
        target.writestr('classes2.dex', production.read('classes.dex'), compress_type=zipfile.ZIP_DEFLATED)
        target.writestr('classes3.dex', production.read('classes2.dex'), compress_type=zipfile.ZIP_DEFLATED)
    run(android_tools / 'zipalign', '-f', '4', build / 'unsigned.apk', build / 'aligned.apk')
    fixture = build / 'checks.apk'
    run(android_tools / 'apksigner', 'sign', '--ks', ROOT / 'artifacts/probe/local-debug.p12',
        '--ks-key-alias', 'port-probe', '--ks-pass', 'pass:android', '--out', fixture, build / 'aligned.apk')
    try:
        print(run(*adb, 'install', '--no-incremental', '-r', fixture), flush=True)
        result = run(*adb, 'shell', 'am', 'instrument', '-w', '-e', 'expect_bug', str(args.expect_bug).lower(),
                     PACKAGE + '/.InputCompatDeviceChecks')
        (output / 'runtime-checks.log').write_text(result + '\n')
        match = re.search(r'PASS phone input checks: (\d+);', result)
        passed = bool(match) and 'FAIL' not in result
        with args.apk.open('rb') as stream: digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        report = {'passed': passed, 'checks': int(match[1]) if match else 0, 'expect_bug': args.expect_bug,
                  'apk_sha256': digest, 'serial': args.serial,
                  'method': 'Unmodified APK input listener, InputHelper and JobService DEX; production adapters and separate-process provider',
                  'real_ime_test': not args.expect_bug, 'write_secure_settings_granted': False,
                  'physical_glasses_test': False}
        (output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
        print(result, flush=True)
        if not passed: raise AssertionError('Phone input regression failed; see ' + str(output))
    finally:
        print(run(*adb, 'uninstall', PACKAGE), flush=True)


if __name__ == '__main__': main()
