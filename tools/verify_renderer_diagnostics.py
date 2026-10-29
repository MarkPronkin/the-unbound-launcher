#!/usr/bin/env python3
"""Run the production renderer collector in an isolated ARM64 emulator fixture."""
import argparse
from pathlib import Path
import shutil
import subprocess
import zipfile
from hardware_buffer_texture import ROOT, ndk_clang
from renderer_diagnostics import build_library


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/renderer-diagnostics/runtime")
    parser.add_argument("--apk", type=Path, help="Exercise the diagnostic library from the signed launcher APK")
    args = parser.parse_args(); work = args.output.resolve(); work.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / "Library/Android/sdk"; bt = sdk / "build-tools/36.0.0"; platform = sdk / "platforms/android-34/android.jar"
    java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip()) / "bin"
    def run(*cmd, **options):
        return subprocess.run([str(c) for c in cmd], check=True, **options)
    native = work / "lib/arm64-v8a"; native.mkdir(parents=True, exist_ok=True)
    if args.apk:
        with zipfile.ZipFile(args.apk) as z:
            (native / "libUnboundRendererDiagnostics.so").write_bytes(z.read("lib/arm64-v8a/libUnboundRendererDiagnostics.so"))
    else: build_library(native / "libUnboundRendererDiagnostics.so")
    run(ndk_clang(), "-shared", "-fPIC", "-O1", "-std=c11", "-Wall", "-Wextra", "-Werror", "-DANDROID_FIXTURE", "-fvisibility=hidden",
        "-Wl,-soname,libil2cpp.so", "-Wl,-z,max-page-size=16384", "-ldl", ROOT / "tools/RendererReadbackTest.c", "-o", native / "libil2cpp.so")
    classes = work / "classes"; classes.mkdir(exist_ok=True)
    source = ROOT / "tools/RendererRuntimeChecks.java"
    # javac requires the public class's filename.
    target = work / "RuntimeChecks.java"; shutil.copy2(source, target)
    run(java / "javac", "--release", "8", "-cp", platform, "-d", classes, target,
        *[ROOT / "settings-ui/src/dev/beampro/settings" / name for name in ("RendererDiagnostics.java", "RendererTrace.java", "ExperimentalStereo.java")])
    jar = work / "classes.jar"; run(java / "jar", "cf", jar, "-C", classes, ".")
    dex = work / "dex"; dex.mkdir(exist_ok=True)
    run(bt / "d8", "--lib", platform, "--min-api", "29", "--output", dex, jar)
    manifest = work / "AndroidManifest.xml"
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.beampro.renderfixture">
      <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="34"/>
      <application android:debuggable="true" android:extractNativeLibs="true" android:theme="@android:style/Theme.Material.NoActionBar">
        <activity android:name=".RuntimeChecks$HostActivity" android:exported="false"/>
      </application><instrumentation android:name=".RuntimeChecks" android:targetPackage="dev.beampro.renderfixture"/>
    </manifest>''')
    unsigned = work / "unsigned.apk"
    run(bt / "aapt2", "link", "-I", platform, "--manifest", manifest, "-o", unsigned)
    with zipfile.ZipFile(unsigned, "a") as z:
        z.write(dex / "classes.dex", "classes.dex")
        for so in native.glob("*.so"): z.write(so, "lib/arm64-v8a/" + so.name)
    apk = work / "fixture.apk"; run(bt / "zipalign", "-f", "-p", "4", unsigned, apk)
    run(bt / "apksigner", "sign", "--ks", ROOT / "artifacts/probe/local-debug.p12", "--ks-key-alias", "port-probe", "--ks-pass", "pass:android", "--key-pass", "pass:android", apk)
    adb = [sdk / "platform-tools/adb", "-s", args.serial]
    if not args.serial.startswith("emulator-"): raise ValueError("This fixture is restricted to an emulator")
    try:
        run(*adb, "install", "-r", apk)
        result = run(*adb, "shell", "am", "instrument", "-w", "dev.beampro.renderfixture/.RuntimeChecks", capture_output=True, text=True, timeout=60)
        (work / "result.txt").write_text(result.stdout + result.stderr)
        print(result.stdout)
        if "PASS 8 Android renderer collection assertions" not in result.stdout: raise AssertionError("Renderer runtime fixture failed")
        samples = run(*adb, "shell", "run-as", "dev.beampro.renderfixture", "cat", "files/renderer-fixture-samples.txt", capture_output=True, text=True)
        (work / "renderer-fixture-samples.txt").write_text(samples.stdout)
    finally:
        run(*adb, "uninstall", "dev.beampro.renderfixture")


if __name__ == "__main__": main()
