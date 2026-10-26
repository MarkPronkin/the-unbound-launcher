"""Validate 16:9 / 16:10 native layouts on an isolated emulator and capture their actual screens."""
import argparse
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.beampro.settings.preview"
ADB_PACKAGE = "dev.beampro.adbtest"
CASES = (
    (640, 360, 1), (720, 450, 1), (960, 540, 1), (960, 600, 1),
    (1280, 720, 1), (1280, 800, 1), (960, 600, 2), (640, 360, 2),
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/landscape-layout")
    parser.add_argument("--case", action="append", help="Only these WIDTHxHEIGHT-font-SCALE cases")
    parser.add_argument("--no-capture", action="store_true")
    parser.add_argument("--resume", action="store_true", help="Keep completed checks from the same build when resuming an interrupted run")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This verifier only changes display configuration on an emulator.")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    adb = [str(Path.home() / "Library/Android/sdk/platform-tools/adb"), "-s", args.serial]

    def run(*command, binary=False):
        return subprocess.run(adb + list(command), capture_output=True, check=True, timeout=90, text=not binary).stdout

    def override(kind):
        match = re.search(r"Override (?:size|density): (\S+)", run("shell", "wm", kind))
        return match.group(1) if match else "reset"

    def window(width, height, font=1):
        for package in (PACKAGE, ADB_PACKAGE):
            run("shell", "am", "force-stop", package)
        run("shell", "settings", "put", "system", "font_scale", str(font))
        run("shell", "wm", "density", "320")
        run("shell", "wm", "size", f"{width * 2}x{height * 2}")

    report = {"emulator": args.serial, "checks": {}, "screenshots": [], "passed": False}
    report_file = output / "landscape-verification.json"
    if (args.case or args.resume) and report_file.exists():
        report = json.loads(report_file.read_text()); report["passed"] = False

    def check(name, label, package=PACKAGE, **arguments):
        if args.resume and label in report["checks"] and not label.startswith("capture-"):
            return
        command = ["shell", "am", "instrument", "-w"]
        for key, value in arguments.items():
            command.extend(("-e", key, str(value).lower()))
        component = package + ("/." if package == PACKAGE else "/dev.beampro.adb.") + name
        value = run(*command, component)
        (output / (label + ".log")).write_text(value)
        if "passed" not in value.lower() or any(word in value.lower() for word in ("failed", "crashed")):
            (output / (label + "-logcat.log")).write_text(run("logcat", "-d", "-t", "400", "-s", "AndroidRuntime:E"))
            raise RuntimeError(label + ": " + value)
        count = re.search(r"passed[^:]*:\s*(\d+)", value)
        report["checks"][label] = int(count.group(1)) if count else value.strip()
        print(label + ": " + value.strip(), flush=True)

    def capture(name, package=PACKAGE):
        # Instrumentation saves the actual window before finish() tears its process down.
        (output / (name + ".png")).write_bytes(run("exec-out", "run-as", package, "cat", "files/landscape-capture.png", binary=True))
        if name + ".png" not in report["screenshots"]:
            report["screenshots"].append(name + ".png")

    old_size, old_density = override("size"), override("density")
    old_settings = {key: run("shell", "settings", "get", "system", key).strip()
                    for key in ("font_scale", "user_rotation", "accelerometer_rotation")}
    run("install", "--no-incremental", "-r", str(output / "settings-preview.apk"))
    run("install", "--no-incremental", "-r", str(output / "adb/unbound-adb-device-test.apk"))
    try:
        run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        run("shell", "settings", "put", "system", "user_rotation", "0")
        if not args.case:
            window(360, 800)
            for name in ("SettingsUiChecks", "OledUiChecks", "GlassesArtworkChecks", "AppMenuChecks",
                         "LauncherPreviewChecks", "BulkVisibilityChecks", "AboutPageChecks", "DisclaimerChecks"):
                check(name, "portrait-" + name)
            check("FoldableUiChecks", "fold-unfold-draft", expanded=False, transition=True)
            window(640, 360)
            check("AdbSetupChecks", "landscape-adb-setup", package=ADB_PACKAGE)
        for width, height, font in CASES:
            label = f"{width}x{height}-font-{font}"
            if args.case and label not in args.case:
                continue
            window(width, height, font)
            expanded = width >= 600 * max(1, font / 1.3)
            check("LandscapeUiChecks", label, expanded=expanded)
            check("FoldableUiChecks", label + "-draft", expanded=expanded)
            check("AdbSetupChecks", label + "-adb", package=ADB_PACKAGE, phase="landscape")
        if not args.no_capture:
            for width, height, ratio in ((960, 540, "16-9"), (960, 600, "16-10"), (640, 360, "16-9-small")):
                window(width, height)
                for screen in ("settings", "menu", "permissions", "disclaimer"):
                    check("LandscapeUiChecks", "capture-" + screen + "-" + ratio, capture=screen)
                    capture(screen + "-" + ratio)
                check("AdbSetupChecks", "capture-adb-" + ratio, package=ADB_PACKAGE, phase="capture")
                capture("adb-" + ratio, ADB_PACKAGE)
        report["passed"] = True
    finally:
        for package in (PACKAGE, ADB_PACKAGE):
            run("shell", "am", "force-stop", package)
        run("shell", "wm", "size", old_size)
        run("shell", "wm", "density", old_density)
        for key, value in old_settings.items():
            run("shell", "settings", "delete" if value == "null" else "put", "system", key, *([] if value == "null" else [value]))
        report_file.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
