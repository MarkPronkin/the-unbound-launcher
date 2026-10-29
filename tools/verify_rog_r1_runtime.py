#!/usr/bin/env python3
"""Execute the built APK's actual R1 USB/EDID identity paths on an isolated emulator."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile

from test_display_startup import JAVA, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/rog-r1-runtime')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        raise ValueError('Use an isolated emulator for these simulated hardware identities')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / 'Library/Android/sdk'
    build = sdk / 'build-tools/36.0.0'
    platform = sdk / 'platforms/android-34/android.jar'
    env = dict(os.environ, JAVA_HOME=str(JAVA))

    def run(*command, capture=False):
        return subprocess.run(list(map(str, command)), check=True, text=True, env=env,
                              capture_output=capture, timeout=60)

    fixtures = {
        'com/xreal/logcontrol/LogControl.java': '''package com.xreal.logcontrol;public class LogControl {
            public static void d(Object... a){}public static void w(Object... a){}public static void i(Object... a){}}''',
        'com/xreal/glassesdisplayplugevent/GlassesInitSetting.java': '''package com.xreal.glassesdisplayplugevent;
            public class GlassesInitSetting {public static int type;
                public static GlassesInitSetting getInstance(){return new GlassesInitSetting();}
                public int checkGlassesType(){return type;}public android.content.Context getContext(){return null;}
                public boolean isEthernetSeriesGlasses(){throw new AssertionError("Actual SDK method not installed");}}''',
        'dev/beampro/adb/AdbBootstrap.java': '''package dev.beampro.adb;public class AdbBootstrap {
            public static final String AUTHORITY="fixture";
            public static boolean ready(android.content.Context c){return false;}
            public static boolean paired(android.content.Context c){return false;}
            public static void connect(android.content.Context c,int i,Object o){}}''',
        'dev/beampro/settings/TermsConsent.java': '''package dev.beampro.settings;public class TermsConsent {
            public static boolean hasAccepted(android.content.Context c){return false;}}''',
    }
    sources = [ROOT / 'tools/RogR1DeviceChecks.java', ROOT / 'embedded-adb/src/dev/beampro/adb/AutoGlassesReceiver.java']
    for name, code in fixtures.items():
        file = output / 'src' / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(code)
        sources.append(file)
    classes = output / 'classes'
    classes.mkdir(exist_ok=True)
    run(JAVA / 'bin/javac', '--release', '8', '-cp', platform, '-d', classes, *sources)
    jar = output / 'fixture.jar'
    run(JAVA / 'bin/jar', 'cf', jar, '-C', classes, '.')
    dex = output / 'dex'
    dex.mkdir(exist_ok=True)
    run(build / 'd8', '--min-api', '31', '--lib', platform, '--output', dex, jar)
    with zipfile.ZipFile(args.apk) as archive:
        (output / 'sdk.dex').write_bytes(archive.read('classes2.dex'))
        (output / 'client.dex').write_bytes(archive.read('classes4.dex'))
    run(JAVA / 'bin/java', '--class-path', ROOT / 'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar',
        ROOT / 'tools/RogR1FixtureDex.java', output / 'sdk.dex', output / 'client.dex', dex / 'classes.dex', output / 'fixture.dex')
    manifest = output / 'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.beampro.r1checks">
      <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="32"/>
      <application android:label="R1 identity checks"/>
      <instrumentation android:name="dev.beampro.r1checks.RogR1DeviceChecks" android:targetPackage="dev.beampro.r1checks"/>
      </manifest>''')
    unsigned = output / 'unsigned.apk'
    run(build / 'aapt2', 'link', '-I', platform, '--manifest', manifest, '-o', unsigned)
    with zipfile.ZipFile(unsigned, 'a') as archive:
        archive.writestr('classes.dex', (output / 'fixture.dex').read_bytes())
    aligned, signed = output / 'aligned.apk', output / 'fixture.apk'
    run(build / 'zipalign', '-f', '4', unsigned, aligned)
    run(build / 'apksigner', 'sign', '--ks', ROOT / 'artifacts/probe/local-debug.p12',
        '--ks-key-alias', 'port-probe', '--ks-pass', 'pass:android', '--out', signed, aligned)
    adb = [sdk / 'platform-tools/adb', '-s', args.serial]
    run(*adb, 'install', '-r', signed)
    result = run(*adb, 'shell', 'am', 'instrument', '-w',
                 'dev.beampro.r1checks/dev.beampro.r1checks.RogR1DeviceChecks', capture=True)
    (output / 'result.txt').write_text(result.stdout + result.stderr)
    print(result.stdout)
    if 'PASS shipped R1 SDK DEX assertions:' not in result.stdout:
        raise AssertionError('Shipped R1 SDK runtime checks failed')


if __name__ == '__main__':
    main()
