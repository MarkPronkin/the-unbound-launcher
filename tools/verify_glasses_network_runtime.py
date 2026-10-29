#!/usr/bin/env python3
"""Check the shipped network adapter and real native socket marks on an isolated Android emulator."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

from hardware_buffer_texture import ndk_clang
from verify_embedded_adb import classes

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/glasses-network-runtime')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        raise ValueError('Use an isolated emulator for the fixture')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / 'Library/Android/sdk'
    build = sdk / 'build-tools/36.0.0'
    platform = sdk / 'platforms/android-34/android.jar'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))

    def run(*command, capture=False):
        return subprocess.run(list(map(str, command)), check=True, text=True, env=env, capture_output=capture, timeout=60)

    adb = [sdk / 'platform-tools/adb', '-s', args.serial]
    with zipfile.ZipFile(args.apk) as archive:
        matching = [name for name in archive.namelist() if name.endswith('.dex')
                    and 'Ldev/beampro/port/GlassesNetworkCompat;' in classes(archive.read(name))]
        if len(matching) != 1:
            raise ValueError('Expected one shipped network adapter')
        production = archive.read(matching[0])
        (output / 'production.dex').write_bytes(production)
    client = args.apk.parent / 'build/client.jar'
    if not client.is_file():
        raise ValueError('The matching build/client.jar is required as the fixture compile classpath')
    settings = output / 'src/com/xreal/glassesdisplayplugevent/GlassesInitSetting.java'
    settings.parent.mkdir(parents=True, exist_ok=True)
    settings.write_text('''package com.xreal.glassesdisplayplugevent;
      public class GlassesInitSetting {public static android.content.Context context;public static boolean ethernet;
      public static GlassesInitSetting getInstance(){return new GlassesInitSetting();}
      public android.content.Context getContext(){return context;}public boolean isEthernetSeriesGlasses(){return ethernet;}}''')
    compiled = output / 'classes'
    compiled.mkdir(exist_ok=True)
    run(java / 'bin/javac', '--release', '8', '-cp', str(platform) + os.pathsep + str(client), '-d', compiled,
        settings, ROOT / 'tools/GlassesNetworkDeviceChecks.java')
    jar = output / 'fixture.jar'
    run(java / 'bin/jar', 'cf', jar, '-C', compiled, '.')
    dex = output / 'dex'
    dex.mkdir(exist_ok=True)
    run(build / 'd8', '--min-api', '31', '--lib', platform, '--classpath', client, '--output', dex, jar)
    native = output / 'libNetworkChecks.so'
    run(ndk_clang(), '-shared', '-fPIC', '-O2', '-std=c11', '-Wall', '-Wextra', '-Werror',
        '-Wl,-z,max-page-size=16384', '-o', native, ROOT / 'tools/GlassesNetworkDeviceChecks.c')
    manifest = output / 'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.beampro.networkchecks">
      <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="33"/>
      <uses-permission android:name="android.permission.INTERNET"/>
      <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
      <application android:label="Network checks" android:extractNativeLibs="true"/>
      <instrumentation android:name="dev.beampro.port.GlassesNetworkDeviceChecks" android:targetPackage="dev.beampro.networkchecks"/>
      </manifest>''')
    unsigned, aligned, signed = (output / name for name in ('unsigned.apk', 'aligned.apk', 'fixture.apk'))
    run(build / 'aapt2', 'link', '-I', platform, '--manifest', manifest, '-o', unsigned)
    with zipfile.ZipFile(unsigned, 'a') as archive:
        archive.writestr('classes.dex', (dex / 'classes.dex').read_bytes())
        archive.writestr('classes2.dex', production)
        archive.writestr('lib/arm64-v8a/libNetworkChecks.so', native.read_bytes())
    run(build / 'zipalign', '-f', '-P', '16', '4', unsigned, aligned)
    run(build / 'apksigner', 'sign', '--ks', ROOT / 'artifacts/probe/local-debug.p12',
        '--ks-key-alias', 'port-probe', '--ks-pass', 'pass:android', '--out', signed, aligned)
    run(*adb, 'install', '-r', signed)
    result = run(*adb, 'shell', 'am', 'instrument', '-w',
                 'dev.beampro.networkchecks/dev.beampro.port.GlassesNetworkDeviceChecks', capture=True)
    (output / 'result.txt').write_text(result.stdout + result.stderr)
    print(result.stdout)
    if 'PASS Android glasses control-network assertions:' not in result.stdout:
        raise AssertionError('Android network/socket checks failed')
    (output / 'verification.json').write_text(json.dumps({
        'apk_sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        'production_dex_sha256': hashlib.sha256(production).hexdigest(),
        'shipped_adapter_executed_under_art': True, 'native_socket_network_mark_verified': True,
        'native_socket_binding_survives_process_restoration': True,
        'physical_glasses_validation': 'pending', 'passed': True,
    }, indent=2) + '\n')


if __name__ == '__main__':
    main()
