#!/usr/bin/env python3
"""Validate control diagnostics across real Android Binder/pipes on an isolated emulator."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.beampro.controldiag"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/control-diagnostics-runtime")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("Run the installable fixture only on an isolated emulator")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / "Library/Android/sdk"
    java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
    build = sdk / "build-tools/36.0.0"
    platform = sdk / "platforms/android-34/android.jar"
    env = dict(os.environ, JAVA_HOME=str(java))

    def run(*command, timeout=90):
        result = subprocess.run(list(map(str, command)), env=env, capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"Command failed: {command}\n{result.stdout}\n{result.stderr}")
        return result.stdout + result.stderr

    classes = output / "classes"
    classes.mkdir(exist_ok=True)
    sources = [ROOT / "settings-ui/src/dev/beampro/settings" / name for name in
               ("DisplayDiagnostics.java", "NetworkDiagnostics.java", "DebugDisplay.java", "ControlTrace.java")]
    sources += [ROOT / "bridge/src/dev/beampro/bridge/DisplayDumpPipe.java", ROOT / "tools/ControlDiagnosticsRuntimeChecks.java"]
    run(java / "bin/javac", "--release", "8", "-cp", platform, "-d", classes, *sources)
    jar = output / "classes.jar"
    run(java / "bin/jar", "cf", jar, "-C", classes, ".")
    dex = output / "dex"
    dex.mkdir(exist_ok=True)
    run(build / "d8", "--min-api", "29", "--lib", platform, "--output", dex, jar)
    # This read-only app_process artifact is also suitable for a non-installing shell probe.
    shell = output / "shell-checks.jar"
    with zipfile.ZipFile(shell, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(dex / "classes.dex", "classes.dex")
    manifest = output / "AndroidManifest.xml"
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PACKAGE}">
      <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="33"/>
      <application android:theme="@android:style/Theme.Material.NoActionBar">
        <provider android:name="dev.beampro.settings.ControlDiagnosticsRuntimeChecks$FixtureProvider"
          android:authorities="{PACKAGE}.unbound.bridge" android:exported="false" android:process=":helper"/>
      </application>
      <instrumentation android:name="dev.beampro.settings.ControlDiagnosticsRuntimeChecks" android:targetPackage="{PACKAGE}"/>
    </manifest>''')
    run(build / "aapt2", "link", "-I", platform, "--manifest", manifest, "-o", output / "base.apk")
    with zipfile.ZipFile(output / "base.apk") as original, zipfile.ZipFile(output / "unsigned.apk", "w") as target:
        for item in original.infolist():
            target.writestr(item, original.read(item))
        target.write(dex / "classes.dex", "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
    run(build / "zipalign", "-f", "4", output / "unsigned.apk", output / "aligned.apk")
    apk = output / "control-checks.apk"
    run(build / "apksigner", "sign", "--ks", ROOT / "artifacts/probe/local-debug.p12", "--ks-key-alias", "port-probe",
        "--ks-pass", "pass:android", "--out", apk, output / "aligned.apk")
    adb = [sdk / "platform-tools/adb", "-s", args.serial]
    run(*adb, "install", "-r", apk)
    try:
        result = run(*adb, "shell", "am", "instrument", "-w", PACKAGE + "/dev.beampro.settings.ControlDiagnosticsRuntimeChecks")
        (output / "runtime.txt").write_text(result)
        print(result)
        if "PASS control diagnostic Android assertions:" not in result or "FAIL after" in result:
            raise AssertionError("Control diagnostic runtime fixture failed")
    finally:
        run(*adb, "uninstall", PACKAGE)
    print(shell)


if __name__ == "__main__":
    main()
