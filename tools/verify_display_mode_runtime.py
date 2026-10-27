#!/usr/bin/env python3
"""Reproduce and fix the full SDK library's API-34 lookup on an older emulator."""
import argparse
import os
from pathlib import Path
import struct
import subprocess
import zipfile

from build_embedded_adb import BASE
from display_mode_compat import LIBRARY, patch_display_mode, INITIALIZER

ROOT = Path(__file__).resolve().parents[1]
ENTRY = 0x181FE5C
ENTRY_SIZE = 0x78


def object_text(path):
    raw=path.read_bytes();table=struct.unpack_from('<Q',raw,40)[0]
    size,count,ni=struct.unpack_from('<HHH',raw,58)
    sections=[struct.unpack_from('<IIQQQQIIQQ',raw,table+i*size) for i in range(count)]
    names=sections[ni];strings=raw[names[4]:names[4]+names[5]]
    for section in sections:
        if section[1] in (4,9) and section[5]:raise ValueError('Unresolved probe relocation')
        if strings[section[0]:strings.index(0,section[0])]==b'.text':
            return raw[section[4]:section[4]+section[5]]
    raise ValueError('Probe .text missing')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/display-mode-runtime-2914')
    args=parser.parse_args()
    if not args.serial.startswith('emulator-'):raise ValueError('Use an isolated emulator')
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
    sdk=Path.home()/'Library/Android/sdk';build=sdk/'build-tools/36.0.0';platform=sdk/'platforms/android-34/android.jar'
    java=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip())
    env=dict(os.environ,JAVA_HOME=str(java))

    def run(*command,capture=False):
        return subprocess.run(list(map(str,command)),check=True,text=True,env=env,
                              capture_output=capture,timeout=60)

    adb=[sdk/'platform-tools/adb','-s',args.serial]
    run(*adb,'wait-for-device')
    run('clang','-target','aarch64-linux-android29','-c',ROOT/'tools/display_mode_probe.S','-o',output/'probe.o')
    probe=object_text(output/'probe.o')
    branch=struct.unpack_from('<I',probe,12)[0]
    delta=branch&0x3FFFFFF
    if delta&0x2000000:delta-=0x4000000
    if ENTRY+12+delta*4!=INITIALIZER:raise ValueError('Probe must call the real SDK initializer')
    if len(probe)>ENTRY_SIZE:raise ValueError('Probe exceeds the isolated JNI entry')
    with zipfile.ZipFile(BASE) as archive:
        original=archive.read(LIBRARY)
        usb=archive.read('lib/arm64-v8a/libnr_libusb.so')
    fixed=patch_display_mode(original)
    (output/'production-libnr_api.so').write_bytes(fixed)
    libraries={'lib/arm64-v8a/libnr_libusb.so':usb}
    for variant,value in (('original',original),('fixed',fixed)):
        copy=bytearray(value)
        copy[ENTRY:ENTRY+ENTRY_SIZE]=probe+bytes.fromhex('1f2003d5')*((ENTRY_SIZE-len(probe))//4)
        libraries['lib/arm64-v8a/libmode_'+variant+'.so']=bytes(copy)
    source=output/'src/com/xreal/sdk/display/GlassesDisplay.java';source.parent.mkdir(parents=True,exist_ok=True)
    source.write_text('package com.xreal.sdk.display;public class GlassesDisplay {public static native int nativeRunOnGlassesDisplay();}')
    classes=output/'classes';classes.mkdir(exist_ok=True)
    run(java/'bin/javac','--release','8','-cp',platform,'-d',classes,source,ROOT/'tools/DisplayModeDeviceChecks.java')
    jar=output/'checks.jar';run(java/'bin/jar','cf',jar,'-C',classes,'.')
    dex=output/'dex';dex.mkdir(exist_ok=True)
    run(build/'d8','--min-api','29','--lib',platform,'--output',dex,jar)
    manifest=output/'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.beampro.modechecks">
      <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="32"/>
      <application android:label="Native mode checks" android:extractNativeLibs="true"/>
      <instrumentation android:name="dev.beampro.modechecks.DisplayModeDeviceChecks" android:targetPackage="dev.beampro.modechecks"/>
      </manifest>''')
    unsigned=output/'unsigned.apk';run(build/'aapt2','link','-I',platform,'--manifest',manifest,'-o',unsigned)
    with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('classes.dex',(dex/'classes.dex').read_bytes())
        for name,value in libraries.items():archive.writestr(name,value)
    aligned,signed=output/'aligned.apk',output/'checks.apk'
    run(build/'zipalign','-f','4',unsigned,aligned)
    run(build/'apksigner','sign','--ks',ROOT/'artifacts/probe/local-debug.p12','--ks-key-alias','port-probe',
        '--ks-pass','pass:android','--out',signed,aligned)
    run(*adb,'install','-r',signed)
    control=run(*adb,'shell','am','instrument','-w','-e','variant','original',
                'dev.beampro.modechecks/dev.beampro.modechecks.DisplayModeDeviceChecks',capture=True)
    logs=run(*adb,'logcat','-d','-s','libc++abi:E','DEBUG:F',capture=True).stdout
    (output/'original-result.txt').write_text(control.stdout+control.stderr)
    (output/'original-crash.txt').write_text(logs)
    if 'getSupportedHdrTypes' not in logs or 'Process crashed' not in control.stdout:
        raise AssertionError('Original native mode initializer did not reproduce the reported crash')
    print('PASS original SDK library reproduces getSupportedHdrTypes native abort on older Android')
    result=run(*adb,'shell','am','instrument','-w','-e','variant','fixed',
               'dev.beampro.modechecks/dev.beampro.modechecks.DisplayModeDeviceChecks',capture=True)
    (output/'fixed-result.txt').write_text(result.stdout+result.stderr)
    print(result.stdout)
    if 'PASS full native Display.Mode initializer' not in result.stdout:
        raise AssertionError('Patched native mode initializer failed on Android')


if __name__=='__main__':main()
