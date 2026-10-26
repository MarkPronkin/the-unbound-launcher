"""Run emulator regressions and capture the implemented Signature OLED screens."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--server-port", default="5038")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/unbound-launcher-help")
    parser.add_argument("--screenshots-only", action="store_true", help="Capture screens after a completed instrumentation run")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This verifier installs and changes configuration only on an emulator.")
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [str(Path.home() / "Library/Android/sdk/platform-tools/adb"), "-P", args.server_port, "-s", args.serial]

    def run(*command, binary=False):
        result = subprocess.run(adb + list(command), check=True, capture_output=True, text=not binary, timeout=90)
        return result.stdout

    def check(name, label=None):
        text = run("shell", "am", "instrument", "-w", "dev.beampro.settings.preview/dev.beampro.settings.preview." + name)
        key = label or name
        (args.output / (key + ".log")).write_text(text)
        if "passed" not in text.lower() or "failed" in text.lower() or "FAILURE" in text:
            raise RuntimeError(key + ": " + text)
        count = re.search(r"passed[^:]*:\s*(\d+)", text)
        report["runtime_checks"][key] = int(count.group(1)) if count else text.strip()
        print(key + ": " + text.strip(), flush=True)

    def hierarchy():
        run("shell", "uiautomator", "dump", "/sdcard/unbound-signature-window.xml")
        return run("shell", "cat", "/sdcard/unbound-signature-window.xml")

    def bounds(node):
        return tuple(map(int, re.findall(r"\d+", node.attrib["bounds"])))

    def find_and_tap(label):
        for attempt in range(4):
            tree = ET.fromstring(hierarchy())
            node = next((node for node in tree.iter("node") if node.attrib.get("text") == label), None)
            if node is not None:
                left, top, right, bottom = bounds(node)
                run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
                return
            left, top, right, bottom = bounds(next(tree.iter("node")))
            x = str((left + right) // 2)
            run("shell", "input", "swipe", x, str(top + (bottom - top) * 4 // 5),
                x, str(top + (bottom - top) // 3), "250")
        raise RuntimeError("Could not find " + label)

    def capture(name):
        xml = hierarchy()
        (args.output / name.replace(".png", ".xml")).write_text(xml)
        (args.output / name).write_bytes(run("exec-out", "screencap", "-p", binary=True))
        report["screenshots"].append(name)
        return ET.fromstring(xml)

    report = {"device": args.serial, "runtime_checks": {}, "screenshots": []}
    if args.screenshots_only:
        report = json.loads((args.output / "runtime-verification.json").read_text())
        report["screenshots"] = []
        report.pop("passed", None)
    night = run("shell", "cmd", "uimode", "night").strip().split()[-1]
    font_scale = run("shell", "settings", "get", "system", "font_scale").strip()
    run("install", "-r", str(args.output / "settings-preview.apk"))
    try:
        if not args.screenshots_only:
            check("SettingsUiChecks")
            check("AboutPageChecks")
            check("GlassesArtworkChecks")
            for mode in ("no", "yes"):
                run("shell", "cmd", "uimode", "night", mode)
                check("OledUiChecks", "OledUiChecks-" + ("light" if mode == "no" else "dark"))
            for name in ("AppMenuChecks", "LauncherPreviewChecks", "LauncherIconChecks", "AppMenuMigrationChecks", "BulkVisibilityChecks", "DisclaimerChecks"):
                check(name)
        for connected, name in ((True, "settings-connected.png"), (False, "settings-disconnected.png")):
            run("shell", "am", "force-stop", "dev.beampro.settings.preview")
            run("shell", "am", "start", "-W", "-n", "dev.beampro.settings.preview/.SettingsPreviewActivity", "--ez", "connected", str(connected).lower())
            run("shell", "uiautomator", "dump", "/sdcard/unbound-signature-window.xml")
            screen_xml = run("shell", "cat", "/sdcard/unbound-signature-window.xml")
            if 'text="Settings"' not in screen_xml:
                raise RuntimeError("Settings must be drawn before capturing " + name)
            (args.output / name.replace(".png", ".xml")).write_text(screen_xml)
            (args.output / name).write_bytes(run("exec-out", "screencap", "-p", binary=True))
            report["screenshots"].append(name)
        find_and_tap("About")
        tree = capture("about.png")
        if not any(node.attrib.get("text") == "0.1.0 alpha" for node in tree.iter("node")):
            raise RuntimeError("About must display the app version")
        heading = next(node for node in tree.iter("node") if node.attrib.get("text") == "About")
        _, normal_top, _, normal_bottom = bounds(heading)
        run("shell", "settings", "put", "system", "font_scale", "1.5")
        tree = capture("about-large-text.png")
        heading = next(node for node in tree.iter("node") if node.attrib.get("text") == "About")
        _, large_top, _, large_bottom = bounds(heading)
        if float(font_scale if font_scale != "null" else "1") < 1.4 and large_bottom - large_top <= normal_bottom - normal_top:
            raise RuntimeError("About must resize its text after a system font-scale change")
        # The secondary page remains scrollable when accessibility text grows.
        for attempt in range(4):
            if any(node.attrib.get("text") == "Terms of service" for node in tree.iter("node")):
                break
            left, top, right, bottom = bounds(next(tree.iter("node")))
            x = str((left + right) // 2)
            run("shell", "input", "swipe", x, str(top + (bottom - top) * 4 // 5),
                x, str(top + (bottom - top) // 3), "250")
            tree = ET.fromstring(hierarchy())
        else:
            raise RuntimeError("Legal links must remain reachable with large text")
        capture("about-large-text-legal.png")
        report["about_large_text_legal_links_reachable"] = True
        report["passed"] = True
    finally:
        if font_scale == "null":
            run("shell", "settings", "delete", "system", "font_scale")
        else:
            run("shell", "settings", "put", "system", "font_scale", font_scale)
        if night in ("auto", "yes", "no", "custom"):
            run("shell", "cmd", "uimode", "night", night)
        (args.output / "runtime-verification.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
