#!/usr/bin/env python3
"""Exercise the real patched SDK selector, including the G-series p0 reuse, on an isolated emulator."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import zipfile

from build_embedded_adb import BASE
from experimental_stereo import patch_stereo_dex
from test_display_startup import JAVA, ROOT

PACKAGE = 'dev.beampro.stereotest'
SOURCES = {
    'ai/nreal/nebula/NebulaApplication.java': '''package ai.nreal.nebula;
public class NebulaApplication extends android.app.Application {}''',
    'ai/nreal/nebula/manager/XREALManager.java': '''package ai.nreal.nebula.manager;
public class XREALManager {
    public ai.nreal.nebula.NebulaApplication mContext;
    public int mGlassesType;
    public com.xreal.glasses.servicesdk.NRServiceControl mNRServiceControl=new com.xreal.glasses.servicesdk.NRServiceControl();
    public int getFinalDisplayConfig(){throw new AssertionError("SDK selector transplant missing");}
}''',
    'com/xreal/glasses/servicesdk/NRServiceControl.java': '''package com.xreal.glasses.servicesdk;
public class NRServiceControl {public boolean isEthernetSeries;}''',
    'com/xreal/glassesdisplayplugevent/GlassesInitSetting.java': '''package com.xreal.glassesdisplayplugevent;
public class GlassesInitSetting {
    public static boolean ethernet;static final GlassesInitSetting instance=new GlassesInitSetting();
    public static GlassesInitSetting getInstance(){return instance;}
    public boolean isGSeriesGlasses(){return ethernet;}
}''',
    'ai/nreal/nebula/util/helper/DbHelpUtil.java': '''package ai.nreal.nebula.util.helper;
public class DbHelpUtil {
    public static boolean home3d,home6d;
    public static Boolean getBoolean(android.content.Context c,String key,boolean fallback){
        if(key.equals("display3DofHome"))return home3d;
        if(key.equals("display6DofHome"))return home6d;
        throw new AssertionError("Unknown boolean: "+key);
    }
    public static Integer getInt(android.content.Context c,String key,int fallback){return fallback;}
}''',
    'ai/nreal/nebula/util/sp/SPUtils.java': '''package ai.nreal.nebula.util.sp;
public class SPUtils {
    public static String mrPackage="";static final SPUtils instance=new SPUtils();
    public static SPUtils getInstance(){return instance;}
    public String getString(String key){return mrPackage;}
}''',
    'com/xreal/logcontrol/LogControl.java': '''package com.xreal.logcontrol;
public class LogControl {public static void d(Object... values){}}''',
    'dev/beampro/stereotest/SelectorProbe.java': '''package dev.beampro.stereotest;
public class SelectorProbe {
    public static Object expected;public static int calls,original;
    public static int selectConfig(int mode,Object manager){
        if(manager!=expected)throw new AssertionError("Stereo hook lost XREALManager receiver: "+
            (manager==null?"null":manager.getClass().getName()));
        calls++;original=mode;
        return dev.beampro.settings.ExperimentalStereo.selectConfig(mode,manager);
    }
}''',
    'dev/beampro/stereotest/SelectorChecks.java': '''package dev.beampro.stereotest;
import android.os.Bundle;
import ai.nreal.nebula.manager.XREALManager;
import ai.nreal.nebula.util.helper.DbHelpUtil;
import ai.nreal.nebula.util.sp.SPUtils;
import com.xreal.glassesdisplayplugevent.GlassesInitSetting;
import dev.beampro.settings.ExperimentalStereo;
public class SelectorChecks extends android.app.Instrumentation {
    int passed;boolean enabled;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    void check(boolean value,String why){if(!value)throw new AssertionError(why);passed++;}
    void vector(int type,boolean ethernet,boolean home3d,boolean home6d,String mrPackage,int original)throws Exception {
        XREALManager manager=new XREALManager();
        manager.mContext=(ai.nreal.nebula.NebulaApplication)getTargetContext().getApplicationContext();
        manager.mGlassesType=type;manager.mNRServiceControl.isEthernetSeries=ethernet;
        GlassesInitSetting.ethernet=ethernet;DbHelpUtil.home3d=home3d;DbHelpUtil.home6d=home6d;SPUtils.mrPackage=mrPackage;
        SelectorProbe.expected=manager;SelectorProbe.calls=0;
        boolean mono=ethernet?original>=1&&original<=4:original==1||original==5||original==10||original==11;
        int expected=enabled&&mono?(ethernet?5:3):original;
        check(manager.getFinalDisplayConfig()==expected,"Production policy result, model="+type+", enabled="+enabled);
        check(SelectorProbe.calls==1&&SelectorProbe.original==original,"Original SDK decision and exactly one hook");
    }
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            for(boolean optIn:new boolean[]{false,true}) {
                enabled=optIn;ExperimentalStereo.save(getTargetContext(),enabled);
                // Both take the real SDK branch that overwrites p0 with a log string.
                vector(6,true,false,true,"",5);vector(20,true,false,true,"",5);
                vector(2,false,false,true,"",10);vector(2,false,true,true,"",4);
                vector(5,false,false,true,"",4);vector(5,false,true,false,"",10);
                vector(2,false,false,false,"fixture.mr",4);vector(5,false,false,false,"fixture.mr",4);
            }
            result.putString("result","PASS actual SDK stereo selector assertions: "+passed);finish(-1,result);
        } catch(Throwable failure) {
            result.putString("result","FAIL "+android.util.Log.getStackTraceString(failure));finish(0,result);
        } finally {
            try{ExperimentalStereo.save(getTargetContext(),false);}catch(Exception ignored){}
        }
    }
}''',
}

MANIFEST = f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PACKAGE}">
<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="33"/>
<application android:name="ai.nreal.nebula.NebulaApplication" android:debuggable="true"/>
<instrumentation android:name="{PACKAGE}.SelectorChecks" android:targetPackage="{PACKAGE}"/>
</manifest>'''


def build(output, sdk_apk=None):
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / 'Library/Android/sdk'
    bt, android = sdk / 'build-tools/36.0.0', sdk / 'platforms/android-34/android.jar'
    env = dict(os.environ, JAVA_HOME=str(JAVA))

    def run(*args):
        subprocess.run(list(map(str, args)), check=True, env=env)

    sources = []
    for name, code in SOURCES.items():
        source = output / 'src' / name
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(code)
        sources.append(source)
    sources.append(ROOT / 'settings-ui/src/dev/beampro/settings/ExperimentalStereo.java')
    classes = output / 'classes'; classes.mkdir(exist_ok=True)
    run(JAVA / 'bin/javac', '--release', '8', '-cp', android, '-d', classes, *sources)
    jar = output / 'fixture.jar'; run(JAVA / 'bin/jar', 'cf', jar, '-C', classes, '.')
    dex = output / 'dex'; dex.mkdir(exist_ok=True)
    run(bt / 'd8', '--min-api', '29', '--lib', android, '--output', dex, jar)
    with zipfile.ZipFile(sdk_apk or BASE) as archive:
        original = archive.read('classes.dex')
    shipped = output / 'sdk-tested.dex'
    shipped.write_bytes(original if sdk_apk else patch_stereo_dex(original, output / 'sdk-patch', JAVA))
    instrumented = output / 'instrumented.dex'
    run(JAVA / 'bin/java', '--class-path', ROOT / 'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar',
        ROOT / 'tools/ExperimentalStereoFixtureDex.java', shipped, dex / 'classes.dex', instrumented)
    manifest = output / 'AndroidManifest.xml'; manifest.write_text(MANIFEST)
    base = output / 'base.apk'; run(bt / 'aapt2', 'link', '-I', android, '--manifest', manifest, '-o', base)
    unsigned = output / 'unsigned.apk'
    with zipfile.ZipFile(base) as source, zipfile.ZipFile(unsigned, 'w') as target:
        for info in source.infolist():
            target.writestr(info, source.read(info))
        target.write(instrumented, 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
    aligned = output / 'aligned.apk'; run(bt / 'zipalign', '-f', '4', unsigned, aligned)
    apk = output / 'selector-test.apk'
    run(bt / 'apksigner', 'sign', '--ks', ROOT / 'artifacts/probe/local-debug.p12', '--ks-key-alias', 'port-probe',
        '--ks-pass', 'pass:android', '--out', apk, aligned)
    return apk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5580')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/pixel10-onepro-012/selector-runtime')
    parser.add_argument('--sdk-apk', type=Path, help='Use an already patched APK to reproduce a released regression')
    parser.add_argument('--build-only', action='store_true')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('Only use an isolated emulator')
    output = args.output.resolve()
    apk = build(output, args.sdk_apk)
    if args.build_only:
        return
    adb = [str(Path.home() / 'Library/Android/sdk/platform-tools/adb'), '-s', args.serial]
    try:
        subprocess.run([*adb, 'install', '--no-incremental', '-r', str(apk)], check=True, timeout=60)
        result = subprocess.run([*adb, 'shell', 'am', 'instrument', '-w', f'{PACKAGE}/{PACKAGE}.SelectorChecks'],
                                capture_output=True, text=True, timeout=60)
        log = result.stdout + result.stderr
        (output / 'android-checks.log').write_text(log)
        passed = result.returncode == 0 and 'PASS actual SDK stereo selector assertions: 32' in log and 'FAIL' not in log
        (output / 'result.json').write_text(json.dumps({'passed': passed, 'sdk_apk': str(args.sdk_apk) if args.sdk_apk else None,
                                                       'emulator': args.serial, 'assertions': 32 if passed else None}, indent=2) + '\n')
        print(log.strip())
        if not passed:
            raise AssertionError('Actual SDK stereo selector regression checks failed')
    finally:
        subprocess.run([*adb, 'uninstall', PACKAGE], capture_output=True, timeout=30)


if __name__ == '__main__':
    main()
