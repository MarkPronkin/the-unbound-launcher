#!/usr/bin/env python3
"""Check the real SDK getter and Android EGL using a private native window."""
import argparse
import os
from pathlib import Path
import struct
import subprocess
import zipfile

from build_embedded_adb import BASE
from display_mode_compat import patch_display_mode
from egl_colorspace_compat import LIBRARY, TARGET, patch_egl_colorspace, verify_egl_colorspace
from verify_display_mode_runtime import ENTRY, ENTRY_SIZE

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--physical-device', action='store_true', help='Allow the background-only fixture on a physical device')
    parser.add_argument('--apk', type=Path, help='Validate the exact production library from a signed APK')
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/oppo-egl-runtime')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-') and not args.physical_device:
        raise ValueError('Use an emulator or explicitly select --physical-device')
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    sdk = Path.home()/'Library/Android/sdk'
    build = sdk/'build-tools/36.0.0'; platform = sdk/'platforms/android-34/android.jar'
    java = Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip())
    env = dict(os.environ,JAVA_HOME=str(java))

    def run(*command,capture=False):
        return subprocess.run(list(map(str,command)),check=True,text=True,env=env,
                              capture_output=capture,timeout=60)

    adb = [sdk/'platform-tools/adb','-s',args.serial]
    run(*adb,'wait-for-device')
    # A test-only JNI entry branches directly to the library's real getter.
    # These probe bytes are excluded from the production library and APK.
    probe = struct.pack('<I',0x14000000 | (((TARGET-ENTRY)//4)&0x3FFFFFF))
    with zipfile.ZipFile(BASE) as archive:
        original = patch_display_mode(archive.read(LIBRARY))
        usb = archive.read('lib/arm64-v8a/libnr_libusb.so')
    libraries = {'lib/arm64-v8a/libnr_libusb.so':usb}
    fixed = patch_egl_colorspace(original)
    if args.apk:
        with zipfile.ZipFile(args.apk) as archive: packaged = archive.read(LIBRARY)
        verify_egl_colorspace(packaged)
        if packaged != fixed: raise ValueError('Packaged SDK library differs from tested native patch chain')
        fixed = packaged
    for variant,value in (('original',original),('fixed',fixed)):
        copy = bytearray(value)
        copy[ENTRY:ENTRY+ENTRY_SIZE] = probe + bytes.fromhex('1f2003d5')*((ENTRY_SIZE-len(probe))//4)
        libraries['lib/arm64-v8a/libegl_'+variant+'.so'] = bytes(copy)
    source = output/'src/com/xreal/sdk/display/GlassesDisplay.java';source.parent.mkdir(parents=True,exist_ok=True)
    source.write_text('package com.xreal.sdk.display;public class GlassesDisplay {public static native int nativeRunOnGlassesDisplay();}')
    classes=output/'classes';classes.mkdir(exist_ok=True)
    run(java/'bin/javac','--release','8','-cp',platform,'-d',classes,source,ROOT/'tools/EglColorspaceDeviceChecks.java')
    jar=output/'checks.jar';run(java/'bin/jar','cf',jar,'-C',classes,'.')
    dex=output/'dex';dex.mkdir(exist_ok=True)
    run(build/'d8','--min-api','29','--lib',platform,'--output',dex,jar)
    manifest=output/'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.beampro.eglchecks">
      <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="32"/>
      <application android:label="Native EGL checks" android:extractNativeLibs="true"/>
      <instrumentation android:name="dev.beampro.eglchecks.EglColorspaceDeviceChecks" android:targetPackage="dev.beampro.eglchecks"/>
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
    try:
        for variant in ('original','fixed'):
            result=run(*adb,'shell','am','instrument','-w','-e','variant',variant,
                       'dev.beampro.eglchecks/dev.beampro.eglchecks.EglColorspaceDeviceChecks',capture=True)
            (output/(variant+'-result.txt')).write_text(result.stdout+result.stderr)
            print(result.stdout)
            if 'PASS '+variant+' actual SDK getter' not in result.stdout:
                raise AssertionError(variant+' Android EGL regression failed')
    finally:
        run(*adb,'uninstall','dev.beampro.eglchecks')


if __name__=='__main__':main()
