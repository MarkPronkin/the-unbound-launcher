"""Verify adaptive phone layouts and capture real Android screens on an isolated emulator."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.beampro.settings.preview"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/foldable-layout")
    parser.add_argument("--screenshots-only", action="store_true")
    parser.add_argument("--case", action="append", help="Run only these cases, keeping earlier results")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This verifier changes display configuration only on an emulator.")
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [str(Path.home() / "Library/Android/sdk/platform-tools/adb"), "-s", args.serial]

    def run(*command, binary=False):
        value = subprocess.run(adb + list(command), capture_output=True, check=True, timeout=90, text=not binary)
        return value.stdout

    def override(kind):
        match = re.search(r"Override (?:size|density): (\S+)", run("shell", "wm", kind))
        return match.group(1) if match else "reset"

    def window(width, height, font=1):
        run("shell", "am", "force-stop", PACKAGE)
        run("shell", "settings", "put", "system", "font_scale", str(font))
        run("shell", "wm", "density", "320")
        run("shell", "wm", "size", f"{width * 2}x{height * 2}")

    report = {"emulator": args.serial, "checks": {}, "screenshots": []}
    previous_report = args.output / "foldable-verification.json"
    if (args.case or args.screenshots_only) and previous_report.exists():
        report = json.loads(previous_report.read_text()); report.pop("passed", None); report["screenshots"] = []

    def check(name, label, **arguments):
        command = ["shell", "am", "instrument", "-w"]
        for key, value in arguments.items():
            command += ["-e", key, str(value).lower()]
        value = run(*command, PACKAGE + "/." + name)
        (args.output / (label + ".log")).write_text(value)
        if "passed" not in value.lower() or "failed" in value.lower() or "crashed" in value.lower():
            raise RuntimeError(label + ": " + value)
        count = re.search(r"passed[^:]*:\s*(\d+)", value)
        report["checks"][label] = int(count.group(1)) if count else value.strip()
        print(label + ": " + value.strip(), flush=True)

    def hierarchy():
        run("shell", "uiautomator", "dump", "/sdcard/unbound-foldable.xml")
        return run("shell", "cat", "/sdcard/unbound-foldable.xml")

    def capture(name):
        xml = hierarchy()
        (args.output / (name + ".xml")).write_text(xml)
        (args.output / (name + ".png")).write_bytes(run("exec-out", "screencap", "-p", binary=True))
        report["screenshots"].append(name + ".png")
        return ET.fromstring(xml)

    old_size, old_density = override("size"), override("density")
    old_font = run("shell", "settings", "get", "system", "font_scale").strip()
    run("install", "-r", str(args.output / "settings-preview.apk"))
    try:
        if not args.screenshots_only:
            if not args.case:
                window(360, 800)
                for name in ("SettingsUiChecks", "OledUiChecks", "GlassesArtworkChecks", "AppMenuChecks", "LauncherPreviewChecks", "BulkVisibilityChecks"):
                    check(name, name)
            for width, height, font, wide in (
                (360, 800, 1, False), (599, 800, 1, False), (600, 800, 1, True),
                (720, 800, 1, True), (840, 720, 1, True), (1200, 900, 1, True),
                (840, 400, 1, True), (720, 800, 2, False),
            ):
                label = f"window-{width}x{height}-font-{font}"
                if not args.case or label in args.case:
                    window(width, height, font)
                    check("FoldableUiChecks", label, expanded=wide)
            if not args.case or "fold-unfold-draft" in args.case:
                window(360, 800)
                check("FoldableUiChecks", "fold-unfold-draft", expanded=False, transition=True)
        for width, name in ((720, "unfolded"), (360, "cover")):
            window(width, 800)
            run("shell", "am", "start", "-W", "-n", PACKAGE + "/.SettingsPreviewActivity", "--ez", "connected", "true")
            tree = capture("settings-" + name)
            target = next((node for node in tree.iter("node") if node.attrib.get("text") == "App menu"), None)
            if target is None:
                # Compact Settings keeps the original single scrolling page.
                run("shell", "input", "swipe", str(width), "1300", str(width), "650", "250")
                tree = ET.fromstring(hierarchy())
                target = next(node for node in tree.iter("node") if node.attrib.get("text") == "App menu")
            left, top, right, bottom = map(int, re.findall(r"\d+", target.attrib["bounds"]))
            run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
            hierarchy()  # Let the activity transition finish before recording its drawn frame.
            capture("app-menu-" + name)
        report["passed"] = True
    finally:
        run("shell", "am", "force-stop", PACKAGE)
        run("shell", "wm", "size", old_size)
        run("shell", "wm", "density", old_density)
        run("shell", "settings", "delete" if old_font == "null" else "put", "system", "font_scale", *([] if old_font == "null" else [old_font]))
        (args.output / "foldable-verification.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
