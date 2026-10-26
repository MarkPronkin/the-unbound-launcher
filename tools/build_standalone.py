#!/usr/bin/env python3
"""Build the ordinary-UID standalone experiment, independently of the ADB-assisted port."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/standalone')
    args = parser.parse_args()
    output = args.output.resolve()
    build = output / 'build'
    if build.exists():
        shutil.rmtree(build)
    (build / 'classes').mkdir(parents=True)
    (build / 'dex').mkdir()
    sdk = Path.home() / 'Library/Android/sdk'
    platform = sdk / 'platforms/android-34/android.jar'
    tools = sdk / 'build-tools/36.0.0'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))

    def run(*command):
        subprocess.run([str(part) for part in command], env=env, check=True)

    run(java / 'bin/javac', '--release', '8', '-classpath', platform, '-d', build / 'classes',
        *sorted((ROOT / 'standalone/src').rglob('*.java')))
    run(java / 'bin/jar', 'cf', build / 'classes.jar', '-C', build / 'classes', '.')
    run(tools / 'd8', '--min-api', '30', '--lib', platform, '--output', build / 'dex', build / 'classes.jar')
    run(tools / 'aapt2', 'compile', '--dir', ROOT / 'standalone/res', '-o', build / 'resources.zip')
    run(tools / 'aapt2', 'link', '-I', platform, '--manifest', ROOT / 'standalone/AndroidManifest.xml',
        '-o', build / 'base.apk', build / 'resources.zip')
    with zipfile.ZipFile(build / 'base.apk') as source, zipfile.ZipFile(build / 'unsigned.apk', 'w') as target:
        for item in source.infolist():
            target.writestr(item, source.read(item))
        target.write(build / 'dex/classes.dex', 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
    # Different signing certificate from the port: this APK cannot consume its helper Binder.
    keystore = output / 'standalone-debug.p12'
    if not keystore.exists():
        run(java / 'bin/keytool', '-genkeypair', '-noprompt', '-keystore', keystore,
            '-storetype', 'PKCS12', '-storepass', 'android', '-keypass', 'android', '-alias', 'standalone',
            '-dname', 'CN=Unbound Standalone Experiment', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650')
    run(tools / 'zipalign', '-f', '-P', '16', '4', build / 'unsigned.apk', build / 'aligned.apk')
    apk = output / 'unbound-standalone.apk'
    run(tools / 'apksigner', 'sign', '--ks', keystore, '--ks-key-alias', 'standalone', '--ks-pass', 'pass:android',
        '--out', apk, build / 'aligned.apk')
    run(tools / 'apksigner', 'verify', '--verbose', apk)
    run(tools / 'zipalign', '-c', '-P', '16', '4', apk)
    report = {'apk': str(apk), 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
              'package': 'dev.beampro.standalone', 'compile_sdk': 34, 'target_sdk': 34, 'min_sdk': 30,
              'is_standalone_experiment': True, 'ar_space_integrated': False,
              'helper_required': False, 'companion_required': False, 'privileged_permissions_requested': False}
    (output / 'build.json').write_text(json.dumps(report, indent=2) + '\n')
    print(apk)


if __name__ == '__main__':
    main()
