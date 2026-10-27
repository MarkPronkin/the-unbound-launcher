#!/usr/bin/env python3
"""Run the built APK's actual startup callback in an isolated Android fixture."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())

FIXTURES = {
    'com/xreal/glasses/api/Startup.java': '''package com.xreal.glasses.api;
      public class Startup {
        public static int error,initCalls;public static boolean init;
        public static void nativeInitService(android.content.Context c){}public static void nativeSetServiceMode(int i){}
        public static void nativeInitSetForegroundService(boolean b){}public static void nativeInitSetLicenseData(String s){}
        public static void nativeStartService(){}public static boolean nativeGlassesInit(){initCalls++;return init;}
        public static void nativeGlassesDeInit(){}public static int nativeGetApiErrorCode(){return error;}
      }''',
    'com/xreal/glasses/api/Control.java': '''package com.xreal.glasses.api;
      public class Control {public static int state,calls;public static Control getInstance(){return new Control();}
        public int nativeGetGlassesStartupState(){calls++;return state;}}''',
    'com/xreal/glassesdisplayplugevent/GlassesInitSetting.java': '''package com.xreal.glassesdisplayplugevent;
      public class GlassesInitSetting {public static int type;public static GlassesInitSetting getInstance(){return new GlassesInitSetting();}
        public int checkGlassesType(){return type;}public boolean isHSeriesGlasses10_11(){return type==10||type==11;}}''',
    'com/xreal/logcontrol/LogControl.java': '''package com.xreal.logcontrol;
      public class LogControl {public static void d(Object... args){}}''',
    'com/xreal/glasses/servicesdk/model/CacheModel.java': '''package com.xreal.glasses.servicesdk.model;
      public class CacheModel {}''',
    'com/xreal/glasses/servicesdk/NRServiceControl.java': '''package com.xreal.glasses.servicesdk;
      public class NRServiceControl {
        private boolean isEthernetSeries,mDpInitStateGSeries;
        private android.content.Context mContext;private String mLicense;
        private com.xreal.glasses.servicesdk.model.CacheModel mCacheModel;
        public int calls,errorCode,reportedError;public boolean result,waiting;private OnStartSDKCallback callback;
        public NRServiceControl(android.content.Context c,boolean ethernet){mContext=c;isEthernetSeries=ethernet;}
        public interface OnStartSDKCallback {void onStartSDKResult(boolean ok);}
        public interface OnDpInitCallback {void onDpInitFinish();}
        private void checkError(String name){reportedError=com.xreal.glasses.api.Startup.nativeGetApiErrorCode();}
        private void notifyErrorCode(int code){errorCode=code;}
        private void waitDpInitStateGSeries(OnDpInitCallback cb){waiting=true;}
        private void sdkSuccessConfig(){callback.onStartSDKResult(true);}
        public void runStartup(OnStartSDKCallback cb,int mode,boolean foreground,boolean ping){throw new AssertionError("SDK body not installed");}
        public void prepare(OnStartSDKCallback cb){callback=cb;}
      }''',
    'com/xreal/glasses/servicesdk/NRServiceControl$$ExternalSyntheticLambda0.java': '''package com.xreal.glasses.servicesdk;
      public class NRServiceControl$$ExternalSyntheticLambda0 implements NRServiceControl.OnDpInitCallback {
        public NRServiceControl$$ExternalSyntheticLambda0(NRServiceControl s){}public void onDpInitFinish(){}}''',
    'ai/nreal/activitylife/NRXRApp.java': '''package ai.nreal.activitylife;
      public class NRXRApp {public static int displayId=-1;public static boolean isNrealDisplay(android.view.Display d,boolean log){return d.getDisplayId()==displayId;}}''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/xperia-startup-runtime')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        raise ValueError('Use an isolated emulator for the fixture')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / 'Library/Android/sdk'
    build = sdk / 'build-tools/36.0.0'
    platform = sdk / 'platforms/android-34/android.jar'
    env = dict(os.environ, JAVA_HOME=str(JAVA))

    def run(*command, capture=False):
        return subprocess.run(list(map(str, command)), check=True, text=True, env=env,
                              capture_output=capture, timeout=60)

    adb = [sdk / 'platform-tools/adb', '-s', args.serial]
    run(*adb, 'wait-for-device')
    sources = []
    for name, code in FIXTURES.items():
        path = output / 'src' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(code)
        sources.append(path)
    # The SDK normally stores its callback in startSdk before invoking this body.
    check_source = (ROOT / 'tools/GlassesStartupDeviceChecks.java').read_text()
    checks = output / 'src/GlassesStartupDeviceChecks.java'
    checks.write_text(check_source)
    sources += [checks, ROOT / 'port-client/src/dev/beampro/port/GlassesStartupCompat.java']
    # Compile the actual production display predicate without its unrelated Activity adapter.
    display_source = (ROOT / 'port-client/src/dev/beampro/port/DisplayStartupCompat.java').read_text()
    predicate = display_source[display_source.index('    static boolean xreal('):display_source.index('    static int stereoMode(')]
    display = output / 'src/DisplayStartupCompat.java'
    display.write_text('package dev.beampro.port;import android.view.Display;import android.util.Log;import java.lang.reflect.Method;'
                       'public class DisplayStartupCompat {static final String TAG="FixtureDisplay";\n' + predicate + '}')
    sources.append(display)
    classes = output / 'classes'
    classes.mkdir(exist_ok=True)
    run(JAVA / 'bin/javac', '--release', '8', '-cp', platform, '-d', classes, *sources)
    jar = output / 'fixture.jar'
    run(JAVA / 'bin/jar', 'cf', jar, '-C', classes, '.')
    dex = output / 'dex'
    dex.mkdir(exist_ok=True)
    run(build / 'd8', '--min-api', '31', '--lib', platform, '--output', dex, jar)
    with zipfile.ZipFile(args.apk) as archive:
        (output / 'shipped.dex').write_bytes(archive.read('classes2.dex'))
    run(JAVA / 'bin/javac', '-cp', ROOT / 'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar',
        '-d', classes, ROOT / 'tools/PatchGlassesStartupDex.java', ROOT / 'tools/StartupFixtureDex.java')
    run(JAVA / 'bin/java', '-cp', str(classes) + os.pathsep + str(ROOT / 'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar'),
        'StartupFixtureDex', output / 'shipped.dex', dex / 'classes.dex', output / 'fixture.dex')
    manifest = output / 'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.beampro.startupchecks">
      <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="32"/>
      <application android:label="Startup checks"/>
      <instrumentation android:name="dev.beampro.startupchecks.GlassesStartupDeviceChecks" android:targetPackage="dev.beampro.startupchecks"/>
      </manifest>''')
    unsigned = output / 'unsigned.apk'
    run(build / 'aapt2', 'link', '-I', platform, '--manifest', manifest, '-o', unsigned)
    with zipfile.ZipFile(unsigned, 'a') as archive:
        archive.writestr('classes.dex', (output / 'fixture.dex').read_bytes())
    aligned, signed = output / 'aligned.apk', output / 'fixture.apk'
    run(build / 'zipalign', '-f', '4', unsigned, aligned)
    run(build / 'apksigner', 'sign', '--ks', ROOT / 'artifacts/probe/local-debug.p12',
        '--ks-key-alias', 'port-probe', '--ks-pass', 'pass:android', '--out', signed, aligned)
    run(*adb, 'install', '-r', signed)
    result = run(*adb, 'shell', 'am', 'instrument', '-w',
                 'dev.beampro.startupchecks/dev.beampro.startupchecks.GlassesStartupDeviceChecks', capture=True)
    (output / 'result.txt').write_text(result.stdout + result.stderr)
    print(result.stdout)
    if 'PASS actual SDK startup DEX assertions:' not in result.stdout:
        raise AssertionError('Actual SDK callback runtime checks failed')


if __name__ == '__main__':
    main()
