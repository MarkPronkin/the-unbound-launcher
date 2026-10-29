#!/usr/bin/env python3
"""Run actual SDK lifecycle methods with a real Android Presentation in an isolated emulator."""
import argparse
import os
from pathlib import Path
import subprocess
import time
import zipfile
from build_embedded_adb import BASE
from presentation_only import patch_presentation
from test_display_startup import JAVA, ROOT

SOURCES = {
 'ai/nreal/activitylife/NRXRApp.java': '''package ai.nreal.activitylife;
import android.app.*;import android.content.*;import android.hardware.display.*;import android.view.*;
public class NRXRApp {
 static final NRXRApp instance=new NRXRApp();public static NRXRApp getInstance(){return instance;}
 public Activity mContext,mFakeActivity;public boolean mMultiResumeMode=true;
 public String mMainActivityName="ai.nreal.nebula.space.LaunchSpaceAcrivity";
 public FloatingManager mFloatingManager;public VirtualDisplay mVirtualDisplay;public int mDisplayId,externalLaunches,virtualMoves;
 public Object mXRDisplayListener;
 public static boolean isNrealDisplay(Display d,boolean log){return d!=null&&d.getName().equals("XREAL Presentation Fixture");}
 private VirtualDisplay getOrCreateVirtualDisplay(){virtualMoves++;throw new AssertionError("Unexpected desktop placeholder");}
 private void destroyVirtualDisplay(){if(mVirtualDisplay!=null)mVirtualDisplay.release();}
 private boolean startActivityOnDisplay(Display d,Intent i){externalLaunches++;return false;}
 public void startUnityActivityInternal(Intent i){throw new AssertionError("SDK transplant missing");}
 public void moveToVirtualDisplay(){throw new AssertionError("SDK transplant missing");}
 public boolean readyForDynamicSwitchDPImpl(){throw new AssertionError("SDK transplant missing");}
 public boolean readyForRestartSessionImpl(){throw new AssertionError("SDK transplant missing");}
 public static Activity getFakeActivity(){throw new AssertionError("SDK transplant missing");}
}''',
 'ai/nreal/activitylife/FloatingManager.java': '''package ai.nreal.activitylife;public class FloatingManager {
 public static int inits;public static FloatingManager getInstance(){return new FloatingManager();}
 public void init(android.content.Context c){inits++;}}''',
 'ai/nreal/nebula/manager/XREALManager.java': '''package ai.nreal.nebula.manager;public class XREALManager {
 static final XREALManager instance=new XREALManager();public static XREALManager getInstance(){return instance;}
 public Control mNRServiceControl=new Control();public static int target=3;
 // As in the renderer (:space) process on a Pixel: the SDK service never starts there.
 public int getFinalDisplayConfig(){throw new AssertionError("Renderer-process SDK target is unavailable");}
 public static class Control {public boolean isEthernetSeries;public int get2D3D(){throw new AssertionError("Renderer-process SDK service is not started");}}
}''',
 'ai/nreal/nebula/IUnityAndroidLifecycle.java': '''package ai.nreal.nebula;public interface IUnityAndroidLifecycle {
 void onStart();void onStop();void onResume();void onPause();}''',
 'ai/nreal/nebula/space/IFocusChangeCallback.java': '''package ai.nreal.nebula.space;public interface IFocusChangeCallback {void onFocusChanged(boolean value);}''',
 'com/xreal/evapro/EvaProHelper.java': '''package com.xreal.evapro;public class EvaProHelper {public static void initXrealLivePks(android.content.Context c){}}''',
 'ai/nreal/nebula/space/LaunchSpaceAcrivity.java': '''package ai.nreal.nebula.space;
import android.app.*;import android.os.*;import android.view.*;import android.widget.*;
import com.unity3d.player.UnityPlayer;import dev.beampro.presentationtest.PresentationDeviceChecks;
public class LaunchSpaceAcrivity extends Activity {
 public UnityPlayer mUnityPlayer;public boolean mMultiResumeMode,fakePause=false,paused=true,mFirstResume=true;
 public ai.nreal.nebula.IUnityAndroidLifecycle unityAndroidLifecycle;public IFocusChangeCallback focusChangeCallback;
 public View mImeMaskView;
 protected boolean wasLaunchedFromRecent(){return false;}
 public void onCreate(Bundle b){super.onCreate(b);mMultiResumeMode=getIntent().getBooleanExtra("multiResumeMode",true);
   mUnityPlayer=new UnityPlayer(this);mImeMaskView=new View(this);TextView text=new TextView(this);
   text.setText("Phone controller — direct output fixture");mUnityPlayer.addView(text);setContentView(mUnityPlayer);
   PresentationDeviceChecks.owner=this;new Handler(getMainLooper()).post(()->PresentationDeviceChecks.showOutput(this));}
 protected void onDestroy(){mUnityPlayer.destroy();super.onDestroy();}
 protected void onStart(){throw new AssertionError("SDK transplant missing");}
 protected void onResume(){throw new AssertionError("SDK transplant missing");}
 protected void onPause(){throw new AssertionError("SDK transplant missing");}
 protected void onStop(){throw new AssertionError("SDK transplant missing");}
 public void onWindowFocusChanged(boolean value){throw new AssertionError("SDK transplant missing");}
}''',
}

MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.beampro.presentationtest">
<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="33"/>
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<application android:name="dev.beampro.presentationtest.PresentationDeviceChecks$App" android:theme="@android:style/Theme.Material.Light.NoActionBar" android:debuggable="true">
<activity android:name="dev.beampro.presentationtest.PresentationDeviceChecks$Entry" android:exported="true" android:launchMode="singleTask" android:taskAffinity="fixture.entry"/>
<activity android:name="ai.nreal.nebula.space.LaunchSpaceAcrivity" android:exported="false" android:taskAffinity="fixture.unity" android:configChanges="orientation|screenSize"/>
<activity android:name="dev.beampro.presentationtest.PresentationDeviceChecks$Overlay" android:exported="false" android:theme="@android:style/Theme.Translucent.NoTitleBar"/>
<service android:name="dev.beampro.port.ArSessionService" android:exported="false" android:foregroundServiceType="connectedDevice"/>
</application>
<instrumentation android:name="dev.beampro.presentationtest.PresentationDeviceChecks" android:targetPackage="dev.beampro.presentationtest"/>
</manifest>'''


def build(output, surface_loss_negative_control=False):
    output.mkdir(parents=True, exist_ok=True)
    sdk=Path.home()/'Library/Android/sdk';bt=sdk/'build-tools/36.0.0';android=sdk/'platforms/android-34/android.jar'
    env=dict(os.environ,JAVA_HOME=str(JAVA))
    def run(*args):subprocess.run(list(map(str,args)),check=True,env=env)
    files=[]
    for name,value in SOURCES.items():
        p=output/'src'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value);files.append(p)
    files.append(ROOT/'tools/PresentationDeviceChecks.java')
    player=output/'src/com/unity3d/player/UnityPlayer.java'
    player.parent.mkdir(parents=True,exist_ok=True)
    player.write_text((ROOT/'tools/PresentationUnityPlayerFixture.java').read_text());files.append(player)
    files.extend(ROOT/'port-client/src/dev/beampro/port'/name for name in (
        'PresentationOnlyCompat.java','BackgroundUnitySurface.java','PresentationReadiness.java','DisplayStartupCompat.java',
        'StereoPresentationCompat.java','PresentationWindowDiagnostics.java','ArBackdropActivity.java','ArSessionService.java'))
    if surface_loss_negative_control:
        compat=ROOT/'port-client/src/dev/beampro/port/PresentationOnlyCompat.java'
        before=compat.read_text();needle='if(isActive(activity))active.retainRenderSurface();'
        if before.count(needle)!=1:raise AssertionError('Missing background surface hook')
        negative=output/'src/dev/beampro/port/PresentationOnlyCompat.java';negative.parent.mkdir(parents=True,exist_ok=True)
        negative.write_text(before.replace(needle,'/* Negative control: retain only the original pause/focus guards. */'))
        files[files.index(compat)]=negative
    classes=output/'classes';classes.mkdir(exist_ok=True)
    run(JAVA/'bin/javac','--release','8','-cp',android,'-d',classes,*files)
    jar=output/'fixture.jar';run(JAVA/'bin/jar','cf',jar,'-C',classes,'.')
    dex=output/'dex';dex.mkdir(exist_ok=True);run(bt/'d8','--min-api','29','--lib',android,'--output',dex,jar)
    with zipfile.ZipFile(BASE) as z: original=z.read('classes.dex')
    shipped=output/'sdk-tested.dex';shipped.write_bytes(patch_presentation(original,output/'sdk-patch',JAVA))
    instrumented=output/'instrumented.dex'
    run(JAVA/'bin/java','--class-path',ROOT/'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar',
        ROOT/'tools/PresentationFixtureDex.java',shipped,dex/'classes.dex',instrumented)
    manifest=output/'AndroidManifest.xml';manifest.write_text(MANIFEST)
    base=output/'base.apk';run(bt/'aapt2','link','-I',android,'--manifest',manifest,'-o',base)
    unsigned=output/'unsigned.apk'
    with zipfile.ZipFile(base) as source,zipfile.ZipFile(unsigned,'w') as target:
        for info in source.infolist():target.writestr(info,source.read(info))
        target.write(instrumented,'classes.dex',compress_type=zipfile.ZIP_DEFLATED)
    aligned=output/'aligned.apk';run(bt/'zipalign','-f','4',unsigned,aligned)
    apk=output/'presentation-test.apk'
    run(bt/'apksigner','sign','--ks',ROOT/'artifacts/probe/local-debug.p12','--ks-key-alias','port-probe','--ks-pass','pass:android','--out',apk,aligned)
    return apk


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/presentation-only-2919/runtime')
    parser.add_argument('--serial',default='emulator-5554');parser.add_argument('--build-only',action='store_true')
    parser.add_argument('--physical-device',action='store_true',
        help='Allow a non-emulator serial; the separate fixture package is uninstalled afterwards')
    parser.add_argument('--surface-loss-negative-control',action='store_true',
        help='Disable only the new surface handoff and require the real GLES frame check to fail')
    args=parser.parse_args()
    if not args.serial.startswith('emulator-') and not args.physical_device:
        parser.error('Only use an isolated emulator, or pass --physical-device')
    output=args.output.resolve();apk=build(output,args.surface_loss_negative_control)
    if args.build_only:return
    adb=[str(Path.home()/'Library/Android/sdk/platform-tools/adb'),'-s',args.serial]
    try:run_checks(adb,apk,output,args.surface_loss_negative_control)
    finally:
        if args.physical_device:subprocess.run([*adb,'uninstall','dev.beampro.presentationtest'],capture_output=True)


def run_checks(adb,apk,output,surface_loss_negative_control=False):
    deadline=time.monotonic()+60
    while True:
        boot=subprocess.run([*adb,'shell','getprop','sys.boot_completed'],capture_output=True,text=True,timeout=5)
        if boot.returncode==0 and boot.stdout.strip()=='1':break
        if time.monotonic()>=deadline:raise TimeoutError('Emulator did not finish booting')
        time.sleep(1)
    subprocess.run([*adb,'install','--no-incremental','-r',str(apk)],check=True)
    subprocess.run([*adb,'shell','appops','set','dev.beampro.presentationtest','SYSTEM_ALERT_WINDOW','allow'],check=True)
    subprocess.run([*adb,'shell','pm','grant','dev.beampro.presentationtest','android.permission.POST_NOTIFICATIONS'],check=True)
    result=subprocess.run([*adb,'shell','am','instrument','-w','dev.beampro.presentationtest/dev.beampro.presentationtest.PresentationDeviceChecks'],capture_output=True,text=True,timeout=90)
    (output/'android-checks.log').write_text(result.stdout+result.stderr)
    print(result.stdout+result.stderr)
    if surface_loss_negative_control:
        if 'FAIL direct Presentation after ' not in result.stdout or 'New GLES buffers keep rendering after the phone SurfaceView is destroyed' not in result.stdout:
            raise AssertionError('Negative control did not fail at the surface-loss regression check')
        print('PASS negative control: surface destruction stalls new GLES buffers with only the old pause/focus guards')
        return
    if result.returncode or 'PASS direct Presentation Android/SDK checks:' not in result.stdout or 'FAIL' in result.stdout:
        logs=subprocess.run([*adb,'logcat','-d','-t','1000'],capture_output=True,text=True)
        (output/'failure-logcat.txt').write_text(logs.stdout+logs.stderr)
        raise AssertionError('Presentation runtime checks failed')

if __name__=='__main__':main()
