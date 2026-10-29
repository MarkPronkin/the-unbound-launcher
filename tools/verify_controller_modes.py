#!/usr/bin/env python3
"""Exercise an APK's real JobService with the production private-flag provider on an isolated emulator."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.beampro.controllermodechecks'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--serial', default='emulator-5580')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/controller-mode-switch/emulator')
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
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout.strip()

    manifest = build / 'AndroidManifest.xml'
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        f'package="{PACKAGE}"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="33"/>'
        '<application android:label="Controller mode checks">'
        '<activity android:name=".ControllerModeDeviceChecks$HostActivity" android:exported="false"/>'
        '<service android:name="com.xreal.evapro.JobService" android:exported="false" android:process=":service"/>'
        '<provider android:name="dev.beampro.adb.LocalBridgeProvider" '
        f'android:authorities="{PACKAGE}.flags" android:exported="false" android:process=":flags"/>'
        '</application><instrumentation android:name=".ControllerModeDeviceChecks" '
        f'android:targetPackage="{PACKAGE}"/></manifest>')
    run(android_tools / 'aapt2', 'link', '-I', platform, '--manifest', manifest, '-o', build / 'base.apk')
    settings = build / 'SettingsCompat.java'
    settings.write_text((ROOT / 'port-client/src/dev/beampro/port/SettingsCompat.java').read_text()
                        .replace('dev.beampro.portprobe.bridge', PACKAGE + '.flags'))
    classes = build / 'classes'
    classes.mkdir(exist_ok=True)
    run(java / 'bin/javac', '--release', '8', '-classpath', platform, '-d', classes, settings,
        ROOT / 'embedded-adb/src/dev/beampro/adb/LocalBridgeProvider.java', ROOT / 'tools/ControllerModeDeviceChecks.java')
    run(java / 'bin/jar', 'cf', build / 'checks.jar', '-C', classes, '.')
    dex = build / 'dex'
    dex.mkdir(exist_ok=True)
    run(android_tools / 'd8', '--min-api', '29', '--lib', platform, '--output', dex, build / 'checks.jar')
    with zipfile.ZipFile(build / 'base.apk') as base, zipfile.ZipFile(args.apk) as production, \
            zipfile.ZipFile(build / 'unsigned.apk', 'w') as target:
        for info in base.infolist(): target.writestr(info, base.read(info))
        target.write(dex / 'classes.dex', 'classes.dex')
        target.writestr('classes2.dex', production.read('classes2.dex'), compress_type=zipfile.ZIP_DEFLATED)
    run(android_tools / 'zipalign', '-f', '4', build / 'unsigned.apk', build / 'aligned.apk')
    fixture = build / 'checks.apk'
    run(android_tools / 'apksigner', 'sign', '--ks', ROOT / 'artifacts/probe/local-debug.p12',
        '--ks-key-alias', 'port-probe', '--ks-pass', 'pass:android', '--out', fixture, build / 'aligned.apk')
    print(run(*adb, 'install', '--no-incremental', '-r', fixture), flush=True)
    run(*adb, 'logcat', '-b', 'crash', '-c')
    result = run(*adb, 'shell', 'am', 'instrument', '-w', PACKAGE + '/.ControllerModeDeviceChecks')
    (output / 'runtime-checks.log').write_text(result + '\n')
    crashes = run(*adb, 'logcat', '-b', 'crash', '-d', '-v', 'threadtime')
    (output / 'crash.log').write_text(crashes + '\n')
    match = re.search(r'PASS controller mode service checks: (\d+); transitions: (\d+)', result)
    passed = bool(match) and 'FAIL' not in result and f'Process: {PACKAGE}' not in crashes
    with args.apk.open('rb') as stream: digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    report = {'passed': passed, 'checks': int(match[1]) if match else 0,
              'transitions': int(match[2]) if match else 0, 'apk_sha256': digest,
              'method': 'APK JobService DEX; production SettingsCompat and separate-process LocalBridgeProvider',
              'write_secure_settings_granted': False, 'global_setting_unchanged': passed,
              'physical_glasses_test': False}
    (output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(result, flush=True)
    if not passed: raise AssertionError('Controller mode regression failed; see ' + str(output))


if __name__ == '__main__':
    main()
