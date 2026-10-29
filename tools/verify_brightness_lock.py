#!/usr/bin/env python3
"""Run an APK's actual brightness observer against real Settings changes in an isolated shell process."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--expect-bug', action='store_true')
    parser.add_argument('--allow-physical', action='store_true',
                        help='Permit a phone; the check temporarily changes its brightness settings')
    args = parser.parse_args()
    output = args.output.resolve()
    build = output / 'build'
    build.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / 'Library/Android/sdk'
    android_tools = sdk / 'build-tools/36.0.0'
    platform = sdk / 'platforms/android-34/android.jar'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))
    adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]

    def run(*command, timeout=120):
        result = subprocess.run(list(map(str, command)), capture_output=True, text=True, env=env, timeout=timeout)
        if result.returncode: raise RuntimeError(result.stdout + result.stderr)
        return result.stdout.strip()

    if run(*adb, 'shell', 'getprop', 'ro.kernel.qemu') != '1' and not args.allow_physical:
        raise SystemExit('Refusing to change brightness settings on a physical phone without --allow-physical')
    classes = build / 'classes'
    classes.mkdir(exist_ok=True)
    run(java / 'bin/javac', '--release', '8', '-classpath', platform, '-d', classes,
        ROOT / 'tools/BrightnessLockDeviceChecks.java')
    run(java / 'bin/jar', 'cf', build / 'checks.jar', '-C', classes, '.')
    dex = build / 'dex'
    dex.mkdir(exist_ok=True)
    run(android_tools / 'd8', '--min-api', '29', '--lib', platform, '--output', dex, build / 'checks.jar')
    fixture = build / 'fixture.jar'
    with zipfile.ZipFile(args.apk) as production, zipfile.ZipFile(fixture, 'w') as target:
        names = sorted(n for n in production.namelist() if re.fullmatch(r'classes\d*\.dex', n))
        target.write(dex / 'classes.dex', 'classes.dex')
        for index, name in enumerate(names, 2):
            target.writestr(f'classes{index}.dex', production.read(name), compress_type=zipfile.ZIP_DEFLATED)
    remote = '/data/local/tmp/unbound-brightness-lock-checks.jar'
    print(run(*adb, 'push', fixture, remote), flush=True)
    try:
        result = run(*adb, 'shell', 'CLASSPATH=' + shlex.quote(remote) +
                     ' app_process / dev.beampro.brightnesschecks.BrightnessLockDeviceChecks ' +
                     str(args.expect_bug).lower())
        (output / 'runtime.log').write_text(result + '\n')
        match = re.search(r'PASS Brightness lock checks: (\d+); expect_bug: (\w+);', result)
        report = {'passed': bool(match) and 'FAIL' not in result,
                  'checks': int(match[1]) if match else 0,
                  'expect_bug': args.expect_bug, 'serial': args.serial,
                  'apk_sha256': hashlib.file_digest(args.apk.open('rb'), 'sha256').hexdigest(),
                  'settings_restored': 'Restored screen_brightness_mode=' in result,
                  'method': 'Unmodified APK DEX in app_process; real Settings.System.screen_brightness '
                            'changes, stock observer dispatched on its own Handler; Proxy callback in place of Unity'}
        (output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
        print(result, flush=True)
        if not report['passed']: raise AssertionError('Brightness lock device regression failed')
    finally:
        run(*adb, 'shell', 'rm', '-f', remote)


if __name__ == '__main__': main()
