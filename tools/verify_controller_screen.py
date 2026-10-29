#!/usr/bin/env python3
"""Render real controller resources and test native screen-off input on an emulator only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.beampro.controllerpreview"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--apk", type=Path, default=ROOT / "artifacts/unbound-launcher-controller-screen-off/the-unbound-launcher.apk")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/controller-screen-off")
    parser.add_argument("--skip-target-install", action="store_true", help="Reuse the same resource fixture already installed on the emulator")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Controller previews and checks must run on an isolated emulator, never a physical phone.")
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    build = out / "preview-build"; build.mkdir(exist_ok=True)
    sdk = Path.home() / "Library/Android/sdk"
    android = sdk / "platforms/android-34/android.jar"
    android_tools = sdk / "build-tools/36.0.0"
    java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))

    def run(*command):
        result = subprocess.run(list(map(str, command)), capture_output=True, text=True, env=env, timeout=240)
        if result.returncode:
            raise RuntimeError(f"{command[0]} failed ({result.returncode}):\n{result.stdout}\n{result.stderr}")
        return result.stdout

    adb = [sdk / "platform-tools/adb", "-s", args.serial]
    sources = sorted((ROOT / "tools/controller-preview/src").rglob("*.java"))
    sources.extend(ROOT / "port-client/src/dev/beampro/port" / name for name in
                   ("PhoneScreenControl.java", "PhoneScreenCompat.java", "PhonePointerCompat.java"))
    classes = build / "classes"
    if classes.exists(): shutil.rmtree(classes)
    classes.mkdir()
    run(java / "bin/javac", "--release", "8", "-cp", android, "-d", classes, *sources)
    run(java / "bin/jar", "cf", build / "classes.jar", "-C", classes, ".")
    dex = build / "dex"; dex.mkdir(exist_ok=True)
    run(android_tools / "d8", "--min-api", "30", "--lib", android, "--output", dex, build / "classes.jar")
    run(android_tools / "aapt2", "link", "-I", android, "--manifest", ROOT / "tools/controller-preview/AndroidManifest.xml", "-o", build / "base.apk")
    with zipfile.ZipFile(build / "base.apk") as source, zipfile.ZipFile(build / "unsigned.apk", "w") as target:
        for info in source.infolist(): target.writestr(info, source.read(info))
        target.write(dex / "classes.dex", "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
    run(android_tools / "zipalign", "-f", "4", build / "unsigned.apk", build / "aligned.apk")
    preview = out / "controller-preview.apk"
    run(android_tools / "apksigner", "sign", "--ks", ROOT / "artifacts/probe/local-debug.p12", "--ks-key-alias", "port-probe",
        "--ks-pass", "pass:android", "--out", preview, build / "aligned.apk")
    if not args.skip_target_install:
        # Keep the emulator fixture small and inert. These are the exact compiled
        # layout resources, drawables and AndroidX classes from the signed APK;
        # its Unity assets, libraries, receivers and application are never started.
        run(android_tools / "aapt2", "link", "-I", android, "--manifest", ROOT / "tools/controller-preview/ResourcesManifest.xml",
            "-o", build / "resource-base.apk")
        with zipfile.ZipFile(build / "resource-base.apk") as base, zipfile.ZipFile(args.apk) as source, zipfile.ZipFile(build / "resource-unsigned.apk", "w") as target:
            target.writestr("AndroidManifest.xml", base.read("AndroidManifest.xml"))
            for info in source.infolist():
                if info.filename == "resources.arsc" or info.filename.startswith("res/") or (info.filename.startswith("classes") and info.filename.endswith(".dex")):
                    target.writestr(info, source.read(info))
        run(android_tools / "zipalign", "-f", "4", build / "resource-unsigned.apk", build / "resource-aligned.apk")
        resources = out / "controller-resources.apk"
        run(android_tools / "apksigner", "sign", "--ks", ROOT / "artifacts/probe/local-debug.p12", "--ks-key-alias", "port-probe",
            "--ks-pass", "pass:android", "--out", resources, build / "resource-aligned.apk")
        print(run(*adb, "install", "--no-incremental", "-r", resources), flush=True)
    print(run(*adb, "install", "-r", preview), flush=True)
    run(*adb, "shell", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
    run(*adb, "shell", "rm", "-f", "/sdcard/Android/data/" + PACKAGE + "/files/controller/verification.json")
    result = run(*adb, "shell", "am", "instrument", "-w", PACKAGE + "/.ControllerScreenChecks")
    (out / "runtime-checks.log").write_text(result)
    print(result, flush=True)
    if "Controller screen checks passed:" not in result or "Process crashed" in result:
        raise RuntimeError("Controller instrumentation did not finish successfully; old reports cannot validate this run")
    run(*adb, "pull", "/sdcard/Android/data/" + PACKAGE + "/files/controller/.", out)
    report = json.loads((out / "verification.json").read_text())
    report["method"] = "Android emulator; signed APK layouts and drawables with production PhoneScreenControl and PhoneScreenCompat"
    with args.apk.open("rb") as stream: report["apk_sha256"] = hashlib.file_digest(stream, "sha256").hexdigest()
    report["physical_phone_installation"] = False
    (out / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    if not report["success"]: raise RuntimeError(report.get("error", result))
    print(f"Verified {report['passed']} checks; rendered all three modes in {out}")


if __name__ == "__main__":
    main()
