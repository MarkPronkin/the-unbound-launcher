"""Verify Wi-Fi-off helper survival using an isolated emulator's external USB ADB starter."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.beampro.adbtest"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--build", type=Path, required=True, help="Output of build_embedded_adb.py --test-only")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/adb-mobile-design/runtime")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("This verifier changes only an isolated emulator, never a physical phone.")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    adb = [str(Path.home() / "Library/Android/sdk/platform-tools/adb"), "-s", args.serial]

    def run(*command, timeout=30):
        return subprocess.run(adb + list(command), capture_output=True, text=True, check=True, timeout=timeout).stdout

    original = {key: run("shell", "settings", "get", "global", key).strip()
                for key in ("adb_enabled", "wifi_on")}
    report = {"passed": False, "physical_phone_tested": False, "mobile_data_hardware_tested": False,
              "bootstrap_transport": "external emulator ADB shell", "real_on_device_tls_pairing_and_bootstrap": False}
    jar = "/data/local/tmp/unbound-mobile-helper-test.jar"
    log = "/data/local/tmp/unbound-mobile-helper-test.log"
    try:
        run("install", "--no-incremental", "-r", str(args.build.resolve() / "unbound-adb-device-test.apk"))
        run("shell", "am", "force-stop", PACKAGE)
        run("shell", "settings", "put", "global", "adb_enabled", "1")
        run("shell", "svc", "wifi", "enable")
        run("push", str(args.build.resolve() / "build/unbound-shell.jar"), jar)
        # The production shell helper verifies UID 2000 itself. No root/system app is used.
        run("shell", "CLASSPATH=" + shlex.quote(jar)
            + " /system/bin/nohup /system/bin/setsid /system/bin/app_process / dev.beampro.bridge.LaunchServer --embedded"
            + " > " + shlex.quote(log) + " 2>&1 < /dev/null &")
        result = run("shell", "am", "instrument", "-w", "-e", "mobile", "true", "-e", "requireTls", "false",
                     PACKAGE + "/dev.beampro.adb.AdbDeviceChecks", timeout=60)
        (output / "instrumentation.log").write_text(result)
        if "helper_survives_wifi_shutdown=true" not in result or "passed=true" not in result:
            raise RuntimeError("Mobile lifecycle checks failed: " + result)
        report["passed"] = True
        report["helper_survives_wifi_shutdown"] = True
        print("PASS USB-started shell helper survives Wi-Fi shutdown; physical mobile data and TLS bootstrap remain untested", flush=True)
    finally:
        try:
            run("shell", "am", "force-stop", PACKAGE)
            run("shell", "rm", "-f", jar, log)
            run("shell", "svc", "wifi", "enable" if original["wifi_on"] == "1" else "disable")
            value = original["adb_enabled"]
            run("shell", "settings", "delete" if value == "null" else "put", "global", "adb_enabled",
                *([] if value == "null" else [value]))
        finally:
            (output / "verification.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
