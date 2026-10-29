#!/usr/bin/env python3
"""Run the APK's actual notification DEX on Android and supply a cross-app test sender."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.beampro.notificationchecks'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/launcher-notifications/runtime')
    args = parser.parse_args()
    build = args.output.resolve() / 'build'
    build.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / 'Library/Android/sdk'
    android = sdk / 'build-tools/36.0.0'
    platform = sdk / 'platforms/android-34/android.jar'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))

    def run(*command):
        result = subprocess.run(list(map(str, command)), capture_output=True, text=True, env=env, timeout=90)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout.strip()

    manifest = build / 'AndroidManifest.xml'
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        f'package="{PACKAGE}"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="33"/>'
        '<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>'
        '<application android:label="Notification checks"><activity android:name=".NotificationDeviceChecks$HostActivity" '
        'android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/>'
        '<category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application>'
        f'<instrumentation android:name=".NotificationDeviceChecks" android:targetPackage="{PACKAGE}"/></manifest>')
    run(android / 'aapt2', 'link', '-I', platform, '--manifest', manifest, '-o', build / 'base.apk')
    classes = build / 'classes'
    classes.mkdir(exist_ok=True)
    run(java / 'bin/javac', '--release', '8', '-classpath', platform, '-d', classes, ROOT / 'tools/NotificationDeviceChecks.java')
    run(java / 'bin/jar', 'cf', build / 'checks.jar', '-C', classes, '.')
    dex = build / 'dex'
    dex.mkdir(exist_ok=True)
    run(android / 'd8', '--min-api', '29', '--lib', platform, '--output', dex, build / 'checks.jar')
    with zipfile.ZipFile(build / 'base.apk') as base, zipfile.ZipFile(args.apk) as production, \
            zipfile.ZipFile(build / 'unsigned.apk', 'w') as target:
        for info in base.infolist(): target.writestr(info, base.read(info))
        target.write(dex / 'classes.dex', 'classes.dex')
        production_dex = production.read('classes2.dex')
        target.writestr('classes2.dex', production_dex, compress_type=zipfile.ZIP_DEFLATED)
    run(android / 'zipalign', '-f', '4', build / 'unsigned.apk', build / 'aligned.apk')
    fixture = build / 'checks.apk'
    run(android / 'apksigner', 'sign', '--ks', ROOT / 'artifacts/probe/local-debug.p12',
        '--ks-key-alias', 'port-probe', '--ks-pass', 'pass:android', '--out', fixture, build / 'aligned.apk')
    adb = [sdk / 'platform-tools/adb', '-s', args.serial]
    print(run(*adb, 'install', '--no-incremental', '-r', fixture), flush=True)
    run(*adb, 'shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
    result = run(*adb, 'shell', 'am', 'instrument', '-w', PACKAGE + '/.NotificationDeviceChecks')
    (args.output / 'runtime-checks.log').write_text(result + '\n')
    report = {'passed': 'PASS notification adapter checks: 16' in result and 'FAIL' not in result,
              'serial': args.serial, 'source_apk': str(args.apk.resolve()), 'fixture': str(fixture),
              'production_dex_sha256': hashlib.sha256(production_dex).hexdigest(),
              'method': 'APK NotifyHelper/NotifyInfo DEX running as an ordinary app; real PendingIntents'}
    (args.output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(result, flush=True)
    if not report['passed']: raise AssertionError('Notification adapter failed; see ' + str(args.output))


if __name__ == '__main__':
    main()
