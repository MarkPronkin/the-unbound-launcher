#!/usr/bin/env python3
"""Verify notification access and pairing using the signed launcher on an emulator."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import zipfile

from package_identity import PACKAGE, ROOT

CHECKS = 'dev.beampro.notificationchecks'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/launcher-notifications/runtime')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('Select an isolated emulator for the launcher install and pairing check')
    build = args.output.resolve() / 'build'
    build.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / 'Library/Android/sdk'
    tools = sdk / 'build-tools/36.0.0'
    platform = sdk / 'platforms/android-35/android.jar'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))

    def run(*command):
        return subprocess.check_output(list(map(str, command)), text=True, env=env, timeout=240).strip()

    manifest = build / 'AndroidManifest.xml'
    manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{CHECKS}">'
                        '<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="35"/><application/>'
                        f'<instrumentation android:name=".NotificationDeviceChecks" android:targetPackage="{PACKAGE}"/></manifest>')
    run(tools / 'aapt2', 'link', '-I', platform, '--manifest', manifest, '-o', build / 'base.apk')
    classes, dex = build / 'classes', build / 'dex'
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)
    run(java / 'bin/javac', '--release', '8', '-classpath', platform, '-d', classes, ROOT / 'tools/NotificationDeviceChecks.java')
    jar = build / 'checks.jar'
    run(java / 'bin/jar', 'cf', jar, '-C', classes, '.')
    run(tools / 'd8', '--min-api', '29', '--lib', platform, '--output', dex, jar)
    with zipfile.ZipFile(build / 'base.apk') as base, zipfile.ZipFile(build / 'unsigned.apk', 'w') as target:
        for info in base.infolist():
            target.writestr(info, base.read(info))
        target.write(dex / 'classes.dex', 'classes.dex')
    aligned, fixture = build / 'aligned.apk', build / 'checks.apk'
    run(tools / 'zipalign', '-f', '4', build / 'unsigned.apk', aligned)
    run(tools / 'apksigner', 'sign', '--ks', ROOT / 'artifacts/probe/local-debug.p12',
        '--ks-key-alias', 'port-probe', '--ks-pass', 'pass:android', '--out', fixture, aligned)
    adb = [sdk / 'platform-tools/adb', '-s', args.serial]
    print(run(*adb, 'install', '--streaming', '-r', args.apk), flush=True)
    print(run(*adb, 'install', '--no-incremental', '-r', fixture), flush=True)
    if int(run(*adb, 'shell', 'getprop', 'ro.build.version.sdk')) >= 33:
        run(*adb, 'shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
    result = run(*adb, 'shell', 'am', 'instrument', '-w', CHECKS + '/.NotificationDeviceChecks')
    (args.output / 'runtime-checks.log').write_text(result + '\n')
    report = {'passed': 'PASS notification checks: zero listeners' in result and 'FAIL' not in result,
              'serial': args.serial, 'source_apk': str(args.apk.resolve()), 'fixture': str(fixture)}
    (args.output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(result, flush=True)
    if not report['passed']:
        raise AssertionError('Notification checks failed; see ' + str(args.output))


if __name__ == '__main__':
    main()
