#!/usr/bin/env python3
"""Exercise the continuous connection cover and real cross-process readiness on an emulator."""
import argparse
import os
from pathlib import Path
import subprocess
import shutil
import zipfile

from hardware_buffer_texture import ndk_clang
from phone_pointer import build_library

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.beampro.connectiontest"
MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.beampro.connectiontest">
<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="33"/>
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
<application android:name="dev.beampro.port.ConnectionHandoffChecks$App" android:theme="@android:style/Theme.Material.NoActionBar" android:hardwareAccelerated="true" android:debuggable="true">
<activity android:name="dev.beampro.port.ConnectionHandoffChecks$Entry" android:exported="true"/>
<activity android:name="ai.nreal.nebula.space.LaunchSpaceAcrivity" android:exported="false" android:process=":space"/>
<provider android:name="dev.beampro.adb.LocalBridgeProvider" android:authorities="dev.beampro.portprobe.bridge" android:exported="true" android:process=":bridge"/>
<provider android:name="dev.beampro.port.ConnectionHandoffChecks$State" android:authorities="dev.beampro.connectiontest.state" android:exported="false"/>
</application>
<instrumentation android:name="dev.beampro.port.ConnectionHandoffChecks" android:targetPackage="dev.beampro.connectiontest"/>
</manifest>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/connection-handoff/runtime")
    parser.add_argument("--baseline-apk", type=Path, help="Run the selected signed APK's actual handoff implementation")
    parser.add_argument("--expect-stall", action="store_true", help="Require the old handoff to reproduce the regression")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"): parser.error("Run this fixture only on an isolated emulator")
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / "Library/Android/sdk"; bt = sdk / "build-tools/36.0.0"; android = sdk / "platforms/android-34/android.jar"
    java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))
    def run(*command):
        result = subprocess.run(list(map(str, command)), text=True, capture_output=True, env=env, timeout=180)
        if result.returncode: raise RuntimeError(result.stdout + result.stderr)
        return result.stdout
    sources = sorted((ROOT / "tools/connection-fixture/src").rglob("*.java"))
    sources += [ROOT / "embedded-adb/src/dev/beampro/adb/LocalBridgeProvider.java"]
    sources += [ROOT / "port-client/src/dev/beampro/port" / name for name in (
        "ConnectionAnimation.java", "ConnectionHandoff.java", "PresentationReadiness.java", "PhonePointerCompat.java")]
    classes = out / "classes"
    if classes.exists(): shutil.rmtree(classes)
    classes.mkdir()
    run(java / "bin/javac", "--release", "8", "-cp", android, "-d", classes, *sources)
    run(java / "bin/jar", "cf", out / "classes.jar", "-C", classes, ".")
    dex = out / "dex"; dex.mkdir(exist_ok=True)
    run(bt / "d8", "--min-api", "29", "--lib", android, "--output", dex, out / "classes.jar")
    manifest = out / "AndroidManifest.xml"; manifest.write_text(MANIFEST)
    run(bt / "aapt2", "link", "-I", android, "--manifest", manifest, "-o", out / "base.apk")
    production = out / "libUnboundPhonePointer.so"
    payload = dex / "classes.dex"
    if args.baseline_apk:
        with zipfile.ZipFile(args.baseline_apk) as baseline: production.write_bytes(baseline.read("lib/arm64-v8a/libUnboundPhonePointer.so"))
        payload = out / "transplanted.dex"
        run(java / "bin/java", "--class-path", ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar",
            ROOT / "tools/HandoffFixtureDex.java", args.baseline_apk, dex / "classes.dex", payload)
    else: build_library(production)
    runtime = out / "libil2cpp.so"
    run(ndk_clang(), "-shared", "-fPIC", "-O2", "-std=c11", "-Wall", "-Wextra", "-Werror", "-Wl,-z,max-page-size=16384",
        "-o", runtime, ROOT / "tools/connection-fixture/runtime.c")
    with zipfile.ZipFile(out / "base.apk") as source, zipfile.ZipFile(out / "unsigned.apk", "w") as target:
        for info in source.infolist(): target.writestr(info, source.read(info))
        target.write(payload, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
        for library in (production, runtime): target.write(library, "lib/arm64-v8a/" + library.name, compress_type=zipfile.ZIP_DEFLATED)
    run(bt / "zipalign", "-f", "-P", "16", "4", out / "unsigned.apk", out / "aligned.apk")
    apk = out / "connection-handoff-test.apk"
    run(bt / "apksigner", "sign", "--ks", ROOT / "artifacts/probe/local-debug.p12", "--ks-key-alias", "port-probe",
        "--ks-pass", "pass:android", "--out", apk, out / "aligned.apk")
    adb = [sdk / "platform-tools/adb", "-s", args.serial]
    print(run(*adb, "install", "-r", apk), flush=True)
    cases = [(True, False, False)] if args.expect_stall else [(True, False, False), (False, False, True), (True, True, True)]
    for overlay, software, native_host in cases:
        if software:
            manifest.write_text(MANIFEST.replace('android:name="ai.nreal.nebula.space.LaunchSpaceAcrivity"',
                                                'android:name="ai.nreal.nebula.space.LaunchSpaceAcrivity" android:hardwareAccelerated="false"'))
            run(bt / "aapt2", "link", "-I", android, "--manifest", manifest, "-o", out / "software-base.apk")
            with zipfile.ZipFile(out / "software-base.apk") as base, zipfile.ZipFile(apk) as source, zipfile.ZipFile(out / "software-unsigned.apk", "w") as target:
                for info in source.infolist():
                    if info.filename.upper().startswith("META-INF/"): continue
                    target.writestr(info, base.read("AndroidManifest.xml") if info.filename == "AndroidManifest.xml" else source.read(info))
            run(bt / "zipalign", "-f", "-P", "16", "4", out / "software-unsigned.apk", out / "software-aligned.apk")
            run(bt / "apksigner", "sign", "--ks", ROOT / "artifacts/probe/local-debug.p12", "--ks-key-alias", "port-probe",
                "--ks-pass", "pass:android", "--out", out / "software-test.apk", out / "software-aligned.apk")
            print(run(*adb, "install", "-r", out / "software-test.apk"), flush=True)
        run(*adb, "shell", "am", "force-stop", PACKAGE)
        run(*adb, "shell", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow" if overlay else "deny")
        # Instrumentation arguments are strings. The entry converts this one explicitly.
        result = run(*adb, "shell", "am", "instrument", "-w", "-e", "overlay", str(overlay).lower(),
                     "-e", "software", str(software).lower(), "-e", "nativeHost", str(native_host).lower(),
                     PACKAGE + "/dev.beampro.port.ConnectionHandoffChecks")
        (out / ("software.log" if software else "overlay.log" if overlay else "fallback.log")).write_text(result)
        print(result, flush=True)
        if args.expect_stall:
            if "FAIL connection handoff" not in result or "laser anchor inactive" not in result: raise RuntimeError("The selected baseline did not reproduce the readiness stall")
            print("PASS regression reproduces the signed baseline's readiness stall", flush=True)
            return
        if "PASS connection handoff Android checks:" not in result: raise RuntimeError("Connection handoff fixture failed")
    attempt = subprocess.run(list(map(str, [*adb, "shell", "content", "call", "--uri", "content://dev.beampro.portprobe.bridge", "--method", "connection-handoff-get"])), text=True, capture_output=True, timeout=20)
    denied = attempt.stdout + attempt.stderr
    if "Connection handoff belongs to this app only" not in denied: raise RuntimeError("Handoff accessible from another UID: " + denied)
    (out / "uid-boundary.log").write_text(denied)
    print("PASS handoff IPC rejects the shell UID", flush=True)


if __name__ == "__main__": main()
