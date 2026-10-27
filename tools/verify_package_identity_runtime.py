#!/usr/bin/env python3
"""Install and exercise the renamed production launcher in an Android emulator."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import zipfile

from app_version import load_version
from package_identity import ROOT, PACKAGE, LEGACY_PACKAGE


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default='emulator-5554')
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/unbound-launcher-own-package')
    args=parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('This check only modifies an isolated emulator')
    output=args.output.resolve()
    build=output/'device-checks';build.mkdir(parents=True,exist_ok=True)
    sdk=Path.home()/'Library/Android/sdk';tools=sdk/'build-tools/36.0.0'
    platform=sdk/'platforms/android-34/android.jar'
    java=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip())
    env=dict(os.environ,JAVA_HOME=str(java))
    adb=[str(sdk/'platform-tools/adb'),'-s',args.serial]

    def run(*command):
        result=subprocess.run(list(map(str,command)),text=True,capture_output=True,env=env)
        if result.returncode:
            raise RuntimeError('Command failed: '+str(command[0])+'\n'+result.stdout+result.stderr)
        return result.stdout.strip()

    def sign(source,destination):
        aligned=source.with_suffix('.aligned.apk')
        run(tools/'zipalign','-f','-P','16','4',source,aligned)
        run(tools/'apksigner','sign','--ks',ROOT/'artifacts/probe/local-debug.p12',
            '--ks-key-alias','port-probe','--ks-pass','pass:android','--out',destination,aligned)

    version=load_version()
    manifest=build/'AndroidManifest.xml'
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        'package="dev.beampro.identitychecks"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="33"/>'
        '<application android:label="Unbound identity checks"/>'
        f'<instrumentation android:name="dev.beampro.identitychecks.PackageIdentityDeviceChecks" android:targetPackage="{PACKAGE}"/>'
        '</manifest>')
    run(tools/'aapt2','link','-I',platform,'--manifest',manifest,'-o',build/'base.apk')
    classes=build/'classes';classes.mkdir(exist_ok=True)
    run(java/'bin/javac','--release','8','-classpath',platform,'-d',classes,ROOT/'tools/PackageIdentityDeviceChecks.java')
    run(java/'bin/jar','cf',build/'checks.jar','-C',classes,'.')
    dex=build/'dex';dex.mkdir(exist_ok=True)
    run(tools/'d8','--min-api','29','--lib',platform,'--output',dex,build/'checks.jar')
    with zipfile.ZipFile(build/'base.apk') as source,zipfile.ZipFile(build/'unsigned.apk','w') as target:
        for info in source.infolist():target.writestr(info,source.read(info))
        target.write(dex/'classes.dex','classes.dex')
    sign(build/'unsigned.apk',build/'checks.apk')
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        f'package="{PACKAGE}" android:versionCode="{version["version_code"]+1}" android:versionName="0.1.1 alpha">'
        '<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="33"/><application/></manifest>')
    run(tools/'aapt2','link','-I',platform,'--manifest',manifest,'-o',build/'update-unsigned.apk')
    sign(build/'update-unsigned.apk',build/'valid-update.apk')
    apk=output/'the-unbound-launcher.apk'
    lookup=subprocess.run([*adb,'shell','pm','path',PACKAGE],text=True,capture_output=True)
    installed=lookup.stdout.strip().removeprefix('package:')
    with apk.open('rb') as stream:
        digest=hashlib.file_digest(stream,'sha256').hexdigest()
    if installed and run(*adb,'shell','sha256sum',installed).split()[0]==digest:
        print('Exact production APK already installed; SHA-256 matches',flush=True)
    else:
        print(run(*adb,'install','--no-incremental','-r',apk),flush=True)
    print(run(*adb,'install','--no-incremental','-r',build/'checks.apk'),flush=True)
    legacy=run(*adb,'shell','pm','path',LEGACY_PACKAGE).removeprefix('package:')
    if not legacy:raise AssertionError('Install the old launcher to verify coexistence and update rejection')
    run(*adb,'push',build/'valid-update.apk','/data/local/tmp/unbound-identity-update.apk')
    with zipfile.ZipFile(output/'the-unbound-launcher.apk') as archive:
        (build/'helper.jar').write_bytes(archive.read('assets/unbound-shell.jar'))
    directory='/data/local/tmp/'+PACKAGE
    run(*adb,'shell','mkdir','-p',directory)
    run(*adb,'push',build/'helper.jar',directory+'/identity-helper.jar')
    command=f'CLASSPATH={directory}/identity-helper.jar nohup app_process / dev.beampro.bridge.LaunchServer --embedded > {directory}/identity-helper.log 2>&1 < /dev/null & echo $!'
    # Android restarts the target app when instrumentation starts. Start the
    # helper afterwards, so that restart cannot kill its provider connection.
    instrument=subprocess.Popen([*adb,'shell','am','instrument','-w','-e','valid','/data/local/tmp/unbound-identity-update.apk',
        '-e','legacy',legacy,'dev.beampro.identitychecks/dev.beampro.identitychecks.PackageIdentityDeviceChecks'],
        stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    recorded=[];helper_pid=None
    try:
        for line in instrument.stdout:
            recorded.append(line)
            if 'identity_stage=waiting_for_helper' in line:
                helper_pid=int(run(*adb,'shell',command))
                break
        remainder=instrument.communicate(timeout=60)[0]
        result=''.join(recorded)+remainder
    finally:
        if helper_pid is not None:
            subprocess.run([*adb,'shell','kill',str(helper_pid)],capture_output=True)
    (output/'package-runtime-checks.log').write_text(result+'\n')
    print(result,flush=True)
    if 'PASS production package identity checks:' not in result or 'FAIL' in result:
        raise AssertionError(result)
    packages={name:run(*adb,'shell','pm','path',name) for name in (LEGACY_PACKAGE,PACKAGE)}
    startup=run(*adb,'shell','am','start','-W','-n',PACKAGE+'/ai.nreal.nebula.activity.splash.SplashActivity')
    (output/'package-launch.log').write_text(startup+'\n')
    if 'Status: ok' not in startup:
        raise AssertionError(startup)
    checks=int(re.search(r'PASS production package identity checks: (\d+)',result).group(1))
    (output/'package-runtime-checks.json').write_text(json.dumps({'passed':True,'package':PACKAGE,
        'checks':checks,'normal_launch_passed':True,
        'coexisting_packages':packages,'physical_glasses_validation':'pending'},indent=2)+'\n')


if __name__=='__main__':main()
