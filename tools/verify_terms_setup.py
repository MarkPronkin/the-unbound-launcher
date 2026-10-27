"""Exercise inline agreement and capture its adaptive layout on an isolated emulator."""
import argparse
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.beampro.adbtest"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/terms-setup")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This verifier only changes the isolated emulator.")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    adb = [str(Path.home() / "Library/Android/sdk/platform-tools/adb"), "-s", args.serial]

    def run(*command, binary=False):
        return subprocess.run(adb + list(command), capture_output=True, check=True, timeout=90, text=not binary).stdout

    def override(kind):
        match = re.search(r"Override (?:size|density): (\S+)", run("shell", "wm", kind))
        return match.group(1) if match else "reset"

    report = {"checks": {}, "screenshots": [], "passed": False}

    def check(phase, label):
        value = run("shell", "am", "instrument", "-w", "-e", "phase", phase, PACKAGE + "/dev.beampro.adb.AdbSetupChecks")
        (output / (label + ".log")).write_text(value)
        match = re.search(r"ADB setup checks passed: (\d+)", value)
        if not match:
            raise RuntimeError(label + ": " + value)
        report["checks"][label] = int(match.group(1))
        print(label + ": " + match.group(0), flush=True)

    old_size, old_density = override("size"), override("density")
    old_settings = {key: run("shell", "settings", "get", "system", key).strip()
                    for key in ("font_scale", "user_rotation", "accelerometer_rotation")}
    run("install", "--no-incremental", "-r", str(output / "adb/unbound-adb-device-test.apk"))
    try:
        run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        run("shell", "settings", "put", "system", "user_rotation", "0")
        run("shell", "wm", "density", "320")
        run("shell", "wm", "size", "720x1600")
        run("shell", "settings", "put", "system", "font_scale", "1")
        check("before", "first-run-ordering")
        check("launcher", "launcher-origin")
        for width, height, font in ((360, 800, 1), (360, 640, 2), (640, 360, 1), (640, 360, 2), (960, 600, 1)):
            label = f"terms-{width}x{height}-font-{font}"
            run("shell", "wm", "size", f"{width * 2}x{height * 2}")
            run("shell", "settings", "put", "system", "font_scale", str(font))
            check("terms", label)
            check("capture-terms", "capture-" + label)
            (output / (label + ".png")).write_bytes(run("exec-out", "run-as", PACKAGE, "cat", "files/landscape-capture.png", binary=True))
            report["screenshots"].append(label + ".png")
        run("shell", "wm", "size", "720x1600")
        run("shell", "settings", "put", "system", "font_scale", "1")
        run("push", str(output / "adb/build/unbound-shell.jar"), "/data/local/tmp/unbound-terms-test.jar")
        run("shell", "CLASSPATH=/data/local/tmp/unbound-terms-test.jar nohup app_process / dev.beampro.bridge.LaunchServer --embedded > /data/local/tmp/unbound-terms-test.log 2>&1 < /dev/null &")
        check("ready", "live-helper-to-terms-to-launcher")
        report["passed"] = True
    finally:
        run("shell", "am", "force-stop", PACKAGE)
        run("shell", "wm", "size", old_size)
        run("shell", "wm", "density", old_density)
        for key, value in old_settings.items():
            run("shell", "settings", "delete" if value == "null" else "put", "system", key, *([] if value == "null" else [value]))
        (output / "verification.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
