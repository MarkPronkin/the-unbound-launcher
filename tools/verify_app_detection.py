#!/usr/bin/env python3
"""Verify real Android categories and production settings UI using only an isolated emulator fixture."""
import argparse
import os
from pathlib import Path
import subprocess
import time
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.beampro.settings.preview"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/experimental-app-detection")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This verifier installs fixtures only on an emulator.")
    output = args.output.resolve()
    sdk = Path.home() / "Library/Android/sdk"
    java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))

    def command(*command):
        result = subprocess.run(list(map(str, command)), env=env, text=True, capture_output=True, timeout=120)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    def adb(*command_args):
        return command(sdk / "platform-tools/adb", "-s", args.serial, *command_args)

    build = output / "category-fixture"
    classes = build / "classes"
    dex = build / "dex"
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(exist_ok=True)
    platform = sdk / "platforms/android-34/android.jar"
    command(java / "bin/javac", "--release", "8", "-cp", platform, "-d", classes,
            ROOT / "bridge/src/dev/beampro/bridge/AppClassification.java", ROOT / "bridge/src/dev/beampro/bridge/ProviderCallCompat.java",
            ROOT / "tools/AppClassificationDeviceChecks.java")
    command(java / "bin/jar", "cf", build / "fixture.jar", "-C", classes, ".")
    command(sdk / "build-tools/36.0.0/d8", "--min-api", "29", "--lib", platform, "--output", dex, build / "fixture.jar")
    with ZipFile(build / "fixture-dex.jar", "w") as archive:
        archive.write(dex / "classes.dex", "classes.dex")
    adb("install", "-r", "-d", output / "settings-preview.apk")
    adb("push", build / "fixture-dex.jar", "/data/local/tmp/unbound-category-check.jar")
    run_fixture = "CLASSPATH=/data/local/tmp/unbound-category-check.jar app_process / dev.beampro.bridge.AppClassificationDeviceChecks"
    checks = adb("shell", run_fixture + " check")
    (output / "android-category-checks.log").write_text(checks)
    if "PASS Android app classification checks" not in checks:
        raise AssertionError(checks)
    print(checks.strip())

    def instrumentation(*options):
        result = adb("shell", "am", "instrument", "-w", *options, PACKAGE + "/dev.beampro.settings.AppDetectionChecks")
        if "PASS app detection" not in result or "FAIL" in result:
            raise AssertionError(result)
        return result

    missing = instrumentation("-e", "no_helper", "true")
    (output / "missing-helper-ui-checks.log").write_text(missing)
    print(missing.strip())
    with (output / "category-server.log").open("w") as log:
        server = subprocess.Popen([str(sdk / "platform-tools/adb"), "-s", args.serial, "shell", run_fixture + " serve"], stdout=log, stderr=subprocess.STDOUT)
        try:
            time.sleep(1)
            result = instrumentation()
            (output / "category-ui-checks.log").write_text(result)
            print(result.strip())
        finally:
            adb("shell", "pkill -f '^app_process / dev.beampro.bridge.AppClassificationDeviceChecks serve$' || true")
            server.terminate()
            server.wait(timeout=10)
    # The in-app updater becomes the recorded installer. This path must also
    # work without the shell helper and without changing install provenance.
    try:
        adb("install", "-r", "-i", PACKAGE, output / "settings-preview.apk")
        self_installer = instrumentation("-e", "self_installer", "true")
        (output / "self-installer-ui-checks.log").write_text(self_installer)
        print("Self-installer: " + self_installer.strip())
    finally:
        adb("install", "-r", "-i", "com.android.shell", output / "settings-preview.apk")


if __name__ == "__main__":
    main()
