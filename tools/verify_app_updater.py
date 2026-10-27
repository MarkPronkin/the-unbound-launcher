#!/usr/bin/env python3
"""Offline updater checks and a real self-update of the isolated preview on an emulator."""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import time
from zipfile import ZipFile, ZIP_DEFLATED
import xml.etree.ElementTree as ET

from app_version import manifest_version, patch_manifest_version

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.beampro.settings.preview"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/app-updater")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This verifier installs fixtures only on an emulator.")
    output = args.output.resolve()
    sdk = Path.home() / "Library/Android/sdk"
    build_tools = sdk / "build-tools/36.0.0"
    java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))

    def command(*command):
        return subprocess.run([str(arg) for arg in command], env=env, check=True, capture_output=True, text=True, timeout=120).stdout

    def adb(*command_args):
        return command(sdk / "platform-tools/adb", "-s", args.serial, *command_args)

    preview = output / "settings-preview.apk"
    with ZipFile(preview) as archive:
        version = manifest_version(archive.read("AndroidManifest.xml"))
    future = {"version_name": "0.1.1 alpha", "version_code": version["version_code"] + 1}
    unsigned = output / "fixture-unsigned.apk"
    with ZipFile(preview) as source, ZipFile(unsigned, "w", compression=ZIP_DEFLATED) as target:
        for info in source.infolist():
            name = info.filename
            if name.upper().startswith("META-INF/"):
                continue
            data = source.read(name)
            if name == "AndroidManifest.xml":
                data = patch_manifest_version(data, future)
            target.writestr(copy.copy(info), data)
    key = output / "fixture-key.p12"
    if not key.exists():
        command(java / "bin/keytool", "-genkeypair", "-keystore", key, "-alias", "fixture", "-storepass", "android",
                "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30", "-dname", "CN=Updater Test Fixture", "-noprompt")

    def sign(source, destination, keystore, alias):
        aligned = destination.with_suffix(".aligned.apk")
        command(build_tools / "zipalign", "-f", "4", source, aligned)
        command(build_tools / "apksigner", "sign", "--ks", keystore, "--ks-key-alias", alias,
                "--ks-pass", "pass:android", "--out", destination, aligned)

    valid = output / "update-valid.apk"
    foreign = output / "update-wrong-signer.apk"
    mismatch = output / "update-wrong-package.apk"
    sign(unsigned, valid, ROOT / "artifacts/probe/local-debug.p12", "port-probe")
    sign(unsigned, foreign, key, "fixture")
    manifest = output / "fixture-manifest.xml"
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.beampro.updatertest.foreign" '
        'android:versionCode="99999" android:versionName="99.0.0"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="33"/>'
        '<application android:label="Updater test fixture"/></manifest>')
    command(build_tools / "aapt2", "link", "-I", sdk / "platforms/android-34/android.jar", "--manifest", manifest, "-o", output / "fixture-package.apk")
    sign(output / "fixture-package.apk", mismatch, ROOT / "artifacts/probe/local-debug.p12", "port-probe")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "force-stop", "com.google.android.packageinstaller")
    adb("shell", "input", "keyevent", "3")
    adb("install", "-r", "-d", preview)
    app_directory = adb("shell", "run-as", PACKAGE, "pwd").strip()
    for fixture in (valid, foreign, mismatch):
        adb("push", fixture, "/data/local/tmp/" + fixture.name)
        adb("shell", "run-as", PACKAGE, "cp", "/data/local/tmp/" + fixture.name, "cache/" + fixture.name)
    checks = adb("shell", "am", "instrument", "-w", "-e", "valid", app_directory + "/cache/" + valid.name,
        "-e", "foreign", app_directory + "/cache/" + foreign.name, "-e", "package", app_directory + "/cache/" + mismatch.name,
        PACKAGE + "/dev.beampro.settings.AppUpdateChecks")
    (output / "updater-checks.log").write_text(checks)
    if "App updater checks passed:" not in checks:
        raise RuntimeError(checks)
    print(checks.strip(), flush=True)
    for check in ("AboutPageChecks", "OledUiChecks"):
        result = adb("shell", "am", "instrument", "-w", PACKAGE + "/." + check)
        (output / (check + ".log")).write_text(result)
        if "passed" not in result.lower() or "failed" in result.lower():
            raise RuntimeError(result)
        print(result.strip(), flush=True)

    def hierarchy():
        adb("shell", "uiautomator", "dump", "/sdcard/updater-check.xml")
        value = adb("shell", "cat", "/sdcard/updater-check.xml")
        return value, ET.fromstring(value)

    def wait_text(label):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            value, tree = hierarchy()
            for node in tree.iter("node"):
                if label.lower() in node.get("text", "").lower():
                    return value, node
            time.sleep(.5)
        raise RuntimeError("Android never showed " + label)

    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files/app-updates")
    adb("shell", "run-as", PACKAGE, "cp", "/data/local/tmp/" + valid.name, "files/app-updates/update.apk")
    adb("shell", "appops", "set", PACKAGE, "REQUEST_INSTALL_PACKAGES", "deny")
    adb("shell", "am", "start", "-n", PACKAGE + "/.SettingsPreviewActivity", "--ez", "install_update", "true")
    value, _ = wait_text("Allow from this source")
    (output / "install-permission.xml").write_text(value)
    # The system permission screen belongs to Settings; grant only the isolated preview.
    adb("shell", "appops", "set", PACKAGE, "REQUEST_INSTALL_PACKAGES", "allow")
    adb("shell", "input", "keyevent", "4")
    value, install = wait_text("Do you want to update")
    # The actual confirmation button uses Android's standard ID on API 33.
    _, tree = hierarchy()
    button = next((n for n in tree.iter("node") if n.get("resource-id") == "android:id/button1" and n.get("text", "").lower() in ("install", "update")), None)
    if button is None:
        raise RuntimeError("Android's package confirmation was not shown: " + value)
    (output / "install-confirmation.xml").write_text(value)
    import re
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", button.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        package = adb("shell", "dumpsys", "package", PACKAGE)
        if f"versionCode={future['version_code']} " in package:
            break
        time.sleep(.5)
    else:
        raise RuntimeError("The preview update was not installed")
    report = {"offline_checks": "passed", "about_and_oled_checks": "passed", "permission_resume": "passed",
              "android_install_confirmation": "passed", "installed_version": future, "physical_device_validation": "pending"}
    (output / "updater-verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print("PASS Android self-update, permission return, and confirmation", flush=True)


if __name__ == "__main__":
    main()
