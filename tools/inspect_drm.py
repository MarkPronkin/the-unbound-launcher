#!/usr/bin/env python3
"""Build and run the isolated shell DRM/EGL capability query; never requests content licenses."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--server-port', type=int, default=5037)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.server_port <= 65535:
        parser.error('--server-port must be between 1 and 65535')
    sdk = Path.home() / 'Library/Android/sdk'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
    build = ROOT / 'artifacts/drm-investigation/probe-build'
    classes, dex = build / 'classes', build / 'dex'
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(parents=True, exist_ok=True)
    platform = sdk / 'platforms/android-34/android.jar'
    subprocess.run([str(java / 'bin/javac'), '--release', '8', '-classpath', str(platform),
                    '-d', str(classes), str(ROOT / 'tools/drm/DrmCapabilities.java')], check=True)
    subprocess.run([str(java / 'bin/jar'), 'cf', str(build / 'classes.jar'), '-C', str(classes), '.'], check=True)
    subprocess.run([str(sdk / 'build-tools/36.0.0/d8'), '--min-api', '29', '--lib', str(platform),
                    '--output', str(dex), str(build / 'classes.jar')], check=True,
                   env={**os.environ, 'JAVA_HOME': str(java)})
    jar = build / 'capabilities.jar'
    with zipfile.ZipFile(jar, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(dex.glob('*.dex')):
            archive.write(path, path.name)
    adb = [str(sdk / 'platform-tools/adb'), '-P', str(args.server_port), '-s', args.serial]
    remote = '/data/local/tmp/beampro-drm-capabilities.jar'
    subprocess.run([*adb, 'push', str(jar), remote], check=True, timeout=30)
    result = subprocess.run([*adb, 'shell', 'env', 'CLASSPATH=' + remote, 'app_process', '/',
                             'dev.beampro.drm.DrmCapabilities'], capture_output=True, text=True, timeout=45)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.with_suffix('.log').write_text(result.stdout + '\nSTDERR:\n' + result.stderr)
    result.check_returncode()
    marker = 'BEAM_DRM_CAPABILITIES '
    records = [line[len(marker):] for line in result.stdout.splitlines() if line.startswith(marker)]
    if len(records) != 1:
        raise RuntimeError('Capability query did not return exactly one JSON record; inspect log')
    report = json.loads(records[0])
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
