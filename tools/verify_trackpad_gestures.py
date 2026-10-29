#!/usr/bin/env python3
"""Verify production gesture watchdog settings and input/lifecycle on a physical Oplus phone."""
import argparse
import json
import os
from pathlib import Path
import queue
import re
import shlex
import subprocess
import threading
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.beampro.gesturechecks'
KEYS = [('system', 'oplus_customize_smart_apperceive_screen_capture'),
        ('system', 'oplus_customize_screenshot_enable_area_screenshot'),
        ('secure', 'double_finger_split_screen_enable'),
        ('secure', 'three_finger_flash_memory_enable'),
        ('global', 'touchpad_gesture_threefinger_fling_to_screenshot')]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--server-port', type=int, default=5039)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--helper', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/three-finger-gestures/device')
    args = parser.parse_args()
    if not args.apk and not args.helper: parser.error('Provide --apk or --helper')
    out = args.output.resolve(); build = out / 'build'; build.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / 'Library/Android/sdk'; android = sdk / 'platforms/android-34/android.jar'
    android_tools = sdk / 'build-tools/36.0.0'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))
    adb = [str(sdk / 'platform-tools/adb'), '-P', str(args.server_port), '-s', args.serial]

    def run(*command, timeout=60):
        result = subprocess.run(list(map(str, command)), capture_output=True, text=True, env=env, timeout=timeout)
        if result.returncode: raise RuntimeError(result.stdout + result.stderr)
        return result.stdout.strip()

    def shell(command): return run(*adb, 'shell', command)
    def settings():
        return {table + ':' + key: shell('settings get ' + table + ' ' + key) for table, key in KEYS}

    manifest = build / 'AndroidManifest.xml'
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        f'package="{PACKAGE}"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="33"/>'
        '<application android:name=".Checks$App" android:theme="@android:style/Theme.Material.NoActionBar" android:debuggable="true" '
        'android:label="Trackpad gesture checks">'
        '<activity android:name="ai.nreal.nebula.space.LaunchSpaceAcrivity" android:exported="true"/>'
        f'<provider android:name=".Checks$Bridge" android:authorities="{PACKAGE}.bridge" android:exported="false"/>'
        '</application><instrumentation android:name=".Checks" '
        f'android:targetPackage="{PACKAGE}"/></manifest>')
    run(android_tools / 'aapt2', 'link', '-I', android, '--manifest', manifest, '-o', build / 'base.apk')
    compat = build / 'TrackpadGestureCompat.java'
    compat.write_text((ROOT / 'port-client/src/dev/beampro/port/TrackpadGestureCompat.java').read_text()
                      .replace('dev.beampro.portprobe.bridge', PACKAGE + '.bridge'))
    classes = build / 'classes'; classes.mkdir(exist_ok=True)
    run(java / 'bin/javac', '--release', '8', '-cp', android, '-d', classes, compat,
        *sorted((ROOT / 'tools/gesture-checks/src').rglob('*.java')))
    run(java / 'bin/jar', 'cf', build / 'checks.jar', '-C', classes, '.')
    dex = build / 'dex'; dex.mkdir(exist_ok=True)
    run(android_tools / 'd8', '--min-api', '29', '--lib', android, '--output', dex, build / 'checks.jar')
    with zipfile.ZipFile(build / 'base.apk') as source, zipfile.ZipFile(build / 'unsigned.apk','w') as target:
        for info in source.infolist(): target.writestr(info,source.read(info))
        target.write(dex / 'classes.dex','classes.dex')
    run(android_tools / 'zipalign', '-f', '4', build / 'unsigned.apk', build / 'aligned.apk')
    fixture = build / 'checks.apk'
    run(android_tools / 'apksigner','sign','--ks',ROOT / 'artifacts/probe/local-debug.p12',
        '--ks-key-alias','port-probe','--ks-pass','pass:android','--out',fixture,build / 'aligned.apk')
    print(run(*adb,'install','--no-incremental','-r',fixture),flush=True)
    if args.helper: (build / 'helper.jar').write_bytes(args.helper.read_bytes())
    else:
        with zipfile.ZipFile(args.apk) as archive: (build / 'helper.jar').write_bytes(archive.read('assets/unbound-shell.jar'))
    remote = '/data/local/tmp/unbound-gesture-checks.jar'
    run(*adb,'push',build / 'helper.jar',remote)
    journal = '/data/local/tmp/unbound-gesture-checks.properties'
    before = settings()
    (out / 'settings-before.json').write_text(json.dumps(before,indent=2)+'\n')
    report = {'phone':args.serial,'before':before,'checks':{}}
    processes = []

    def instrument(phase):
        result = run(*adb,'shell','am','instrument','-w','-e','phase',phase,PACKAGE+'/.Checks',timeout=60)
        (out / (phase+'.log')).write_text(result+'\n')
        print(result,flush=True)
        if 'PASS ' + phase not in result or 'FAIL' in result: raise AssertionError('Gesture fixture failed: '+phase)
        return {key:int(value) for key,value in re.findall(r'(cancels|max_pointers|three_finger_moves|assertions)=(\d+)',result)}

    def start():
        command = 'CLASSPATH='+shlex.quote(remote)+' app_process / '+shlex.quote('dev.beampro.bridge.TrackpadGestures$Watchdog')+' '+journal+' 0'
        process = subprocess.Popen([*adb,'shell','-T',command],stdin=subprocess.PIPE,stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT,text=True,bufsize=1)
        processes.append(process)
        lines = queue.Queue()
        def drain():
            for line in process.stdout:
                lines.put(line.strip())
                with (out / 'watchdog.log').open('a') as log: log.write(line)
        threading.Thread(target=drain,daemon=True).start()
        def await_line(expected,timeout=18):
            deadline = time.monotonic()+timeout
            while time.monotonic()<deadline:
                line = lines.get(timeout=max(.01,deadline-time.monotonic()))
                if line == expected: return
                if line.startswith('ERROR'): raise RuntimeError(line)
            raise TimeoutError(expected)
        def send(value): process.stdin.write(value+'\n');process.stdin.flush()
        await_line('READY'); send('START'); await_line('ACTIVE')
        return process,send,await_line

    try:
        report['baseline_input'] = instrument('baseline')
        process,send,wait = start()
        report['during'] = settings()
        report['checks']['all_five_gates_disabled'] = all(value=='0' for value in report['during'].values())
        # Keep the independent lease alive while Android runs the input fixture.
        renewing = threading.Event()
        def renew():
            while not renewing.wait(2): send('PING')
        thread = threading.Thread(target=renew,daemon=True);thread.start()
        try: report['protected_input'] = instrument('protected')
        finally: renewing.set();thread.join(timeout=3)
        send('STOP');wait('RESTORED');process.wait(timeout=5)
        report['checks']['exact_settings_restored'] = settings()==before
        process,send,wait = start()
        process.stdin.close();wait('RESTORED');process.wait(timeout=5)
        report['checks']['helper_pipe_death_restores'] = settings()==before
        process,send,wait = start()
        wait('RESTORED',timeout=22);process.wait(timeout=5)
        report['checks']['stalled_heartbeat_restores'] = settings()==before
        report['after'] = settings()
        report['passed'] = all(report['checks'].values())
        if not report['passed']: raise AssertionError(report['checks'])
    finally:
        for process in processes:
            if process.poll() is None:
                try: process.stdin.close()
                except (OSError,ValueError): pass
                try: process.wait(timeout=20)
                except subprocess.TimeoutExpired: pass
        # Ensure a failing fixture cannot leave test settings behind.
        for table,key in KEYS:
            value=before[table+':'+key]
            shell('settings '+('delete '+table+' '+key if value=='null' else 'put '+table+' '+key+' '+shlex.quote(value)))
        run(*adb,'uninstall',PACKAGE)
        shell('rm -f '+remote+' '+journal+' '+journal+'.lock')
        (out / 'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2),flush=True)


if __name__=='__main__': main()
