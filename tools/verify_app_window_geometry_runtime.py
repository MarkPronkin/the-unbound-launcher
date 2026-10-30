#!/usr/bin/env python3
"""Test the actual helper DEX against normal and fixed-rotation Android displays."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--fixed-rotation', action='store_true',
                        help='Also test a presentation display on an emulator already configured with persist.demo.remoterotation=portrait')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/app-window-geometry-runtime')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        raise ValueError('Use an isolated emulator for the display fixture')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / 'Library/Android/sdk'
    java = Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))
    platform = sdk / 'platforms/android-34/android.jar'

    def run(*command, capture=False, check=True):
        return subprocess.run(list(map(str, command)), check=check, env=env, text=True,
                              capture_output=capture, timeout=60)

    classes, dex = output / 'classes', output / 'dex'
    classes.mkdir(exist_ok=True); dex.mkdir(exist_ok=True)
    run(java / 'bin/javac','--release','8','-cp',platform,'-d',classes,
        ROOT / 'bridge/src/dev/beampro/bridge/AppWindowGeometry.java',
        ROOT / 'bridge/src/dev/beampro/bridge/ShellCommand.java',
        ROOT / 'tools/AppWindowGeometryDeviceChecks.java')
    run(java / 'bin/jar','cf',output / 'classes.jar','-C',classes,'.')
    run(sdk / 'build-tools/36.0.0/d8','--min-api','29','--lib',platform,'--output',dex,output / 'classes.jar')
    with zipfile.ZipFile(ROOT / 'artifacts/beam-pro-system/myglasses-dex.zip') as archive:
        (output / 'baseline.dex').write_bytes(archive.read('classes.dex'))
    run(java / 'bin/java','--class-path',ROOT / 'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar',
        ROOT / 'tools/PatchLaunchDex.java','server',output / 'baseline.dex',output / 'server.dex')
    jar = output / 'fixture.jar'
    with zipfile.ZipFile(jar,'w',compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(dex / 'classes.dex','classes.dex')
        archive.write(output / 'server.dex','classes2.dex')
    adb = [sdk / 'platform-tools/adb','-s',args.serial]
    remote = '/data/local/tmp/unbound-app-window-geometry-fixture.jar'
    if args.fixed_rotation:
        value = run(*adb,'shell','getprop','persist.demo.remoterotation',capture=True).stdout.strip()
        if value != 'portrait':
            raise ValueError('The fixed-rotation fixture requires an emulator already configured with persist.demo.remoterotation=portrait')
    try:
        run(*adb,'push',jar,remote)
        result = run(*adb,'shell','env','CLASSPATH=' + remote,'app_process','/',
                     'dev.beampro.bridge.AppWindowGeometryDeviceChecks',
                     'fixed' if args.fixed_rotation else 'normal',capture=True,check=False)
        (output / 'result.txt').write_text(result.stdout + result.stderr)
        print(result.stdout + result.stderr)
        if result.returncode != 0 or 'PASS actual app window geometry DEX assertions:' not in result.stdout:
            raise AssertionError('App window geometry runtime checks failed')
    finally:
        run(*adb,'shell','rm','-f',remote)


if __name__ == '__main__':
    main()
