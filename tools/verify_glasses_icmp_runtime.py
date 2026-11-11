#!/usr/bin/env python3
"""Run the shipped ICMP fallback in an isolated emulator, including real lost replies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

from verify_embedded_adb import classes

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/glasses-icmp-runtime')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        raise ValueError('This root-only network fixture requires an isolated emulator')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / 'Library/Android/sdk'
    build = sdk / 'build-tools/36.0.0'
    platform = sdk / 'platforms/android-34/android.jar'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))

    def run(*command):
        return subprocess.run(list(map(str, command)), check=True, text=True, env=env,
                              capture_output=True, timeout=60)

    adb = [sdk / 'platform-tools/adb', '-s', args.serial]
    if run(*adb, 'shell', 'id', '-u').stdout.strip() != '0':
        raise ValueError('Start adbd as root on the isolated emulator first')
    client = args.apk.parent / 'build/client.jar'
    with zipfile.ZipFile(args.apk) as archive:
        matching = [name for name in archive.namelist() if name.endswith('.dex')
                    and 'Ldev/beampro/port/GlassesIcmpProbe;' in classes(archive.read(name))]
        if len(matching) != 1:
            raise ValueError('Expected exactly one shipped ICMP fallback')
        production = archive.read(matching[0])
    (output / 'production.dex').write_bytes(production)
    selector = output / 'src/dev/beampro/port/GlassesNetworkCompat.java'
    selector.parent.mkdir(parents=True, exist_ok=True)
    # Real USB/Ethernet selection is covered separately. Select the emulator's
    # real Network here so the shipped socket code can execute without glasses.
    selector.write_text('''package dev.beampro.port;public final class GlassesNetworkCompat {
        static android.net.Network chosen;static boolean missing;
        static android.net.Network controlNetwork(android.net.ConnectivityManager cm){return missing?null:chosen;}}''')
    compiled = output / 'classes'
    compiled.mkdir(exist_ok=True)
    run(java / 'bin/javac', '--release', '8', '-cp', str(platform) + os.pathsep + str(client), '-d', compiled,
        selector, ROOT / 'tools/GlassesIcmpDeviceChecks.java')
    jar = output / 'fixture.jar'
    run(java / 'bin/jar', 'cf', jar, '-C', compiled, '.')
    dex = output / 'dex'
    dex.mkdir(exist_ok=True)
    run(build / 'd8', '--min-api', '29', '--lib', platform, '--classpath', client, '--output', dex, jar)
    manifest = output / 'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.beampro.icmpchecks">
      <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="33"/>
      <uses-permission android:name="android.permission.INTERNET"/>
      <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
      <application android:label="ICMP checks"/>
      <instrumentation android:name="dev.beampro.port.GlassesIcmpDeviceChecks" android:targetPackage="dev.beampro.icmpchecks"/>
      </manifest>''')
    unsigned, aligned, signed = (output / name for name in ('unsigned.apk', 'aligned.apk', 'fixture.apk'))
    run(build / 'aapt2', 'link', '-I', platform, '--manifest', manifest, '-o', unsigned)
    with zipfile.ZipFile(unsigned, 'a') as archive:
        archive.writestr('classes.dex', (dex / 'classes.dex').read_bytes())
        archive.writestr('classes2.dex', production)
    run(build / 'zipalign', '-f', '-P', '16', '4', unsigned, aligned)
    run(build / 'apksigner', 'sign', '--ks', ROOT / 'artifacts/probe/local-debug.p12',
        '--ks-key-alias', 'port-probe', '--ks-pass', 'pass:android', '--out', signed, aligned)
    run(*adb, 'install', '-r', signed)
    rules = ['OUTPUT', '-p', 'icmp', '--icmp-type', 'echo-reply', '-s', '169.254.2.1',
             '-d', '169.254.2.10', '-j', 'DROP']
    added = []
    dropped = False
    try:
        for address in ('169.254.2.10/32', '169.254.2.1/32'):
            run(*adb, 'shell', 'ip', 'address', 'add', address, 'dev', 'lo')
            added.append(address)
        for timeout in (False, True):
            if timeout:
                run(*adb, 'shell', 'iptables', '-I', *rules)
                dropped = True
            result = run(*adb, 'shell', 'am', 'instrument', '-w', '-e', 'timeout', str(timeout).lower(),
                         'dev.beampro.icmpchecks/dev.beampro.port.GlassesIcmpDeviceChecks')
            text = result.stdout + result.stderr
            (output / ('timeout.txt' if timeout else 'success.txt')).write_text(text)
            print(text)
            if 'PASS Android glasses ICMP assertions:' not in text:
                raise AssertionError('Real Android ICMP fallback failed')
    finally:
        if dropped:
            run(*adb, 'shell', 'iptables', '-D', *rules)
        for address in reversed(added):
            run(*adb, 'shell', 'ip', 'address', 'del', address, 'dev', 'lo')
    (output / 'verification.json').write_text(json.dumps({
        'apk_sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        'production_dex_sha256': hashlib.sha256(production).hexdigest(),
        'shipped_icmp_executed_as_app_uid': True,
        'real_echo_and_lost_reply_timeout_passed': True,
        'selector_fixture': 'active emulator Network; production USB/Ethernet selector tested separately',
        'physical_samsung_validation': 'pending', 'passed': True,
    }, indent=2) + '\n')


if __name__ == '__main__':
    main()
