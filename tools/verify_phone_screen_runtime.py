#!/usr/bin/env python3
"""Exercise the installed phone-screen button with real displays; leave the panel on."""
import argparse
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = Path.home() / "Library/Android/sdk/platform-tools/adb"
PACKAGE = "dev.markpronkin.unbound"


def surface_state(data):
    states = {}
    for block in data[data.index("\nDisplays ("):].split("\nDisplay "):
        match = re.match(r"(\d+)\n", block)
        power = re.search(r"\bpowerMode=(\w+)", block)
        if match and power:
            states[match[1]] = power[1]
    # Presentation surfaces can have an empty title. Use the external display's
    # layer stack so phone frames cannot be mistaken for continued glasses output.
    external = re.search(r"\nDisplay (\d+)\n    port=\d+\n    connectionType=External", data)
    stack = re.search(r"\nDisplay " + external[1] + r" \(physical,[\s\S]*?layerFilter=\{layerStack=(\d+)", data) if external else None
    frames = []
    if stack and "\nComposition list (top to bottom)\n" in data:
        composition = data.split("\nComposition list (top to bottom)\n", 1)[1].split("\nInput list", 1)[0]
        layers = re.search(r"(?:^|\n)LayerStack=" + stack[1] + r"\n(.*?)(?=\nLayerStack=|\Z)", composition, re.S)
        if layers:
            frames = re.findall(r"Layer \[\d+\] [^\n]*SurfaceView\[[^\n]*\]\(BLAST\)[^\n]*"
                                r"\n    visible reason=[^\n]*\bframe=(\d+)", layers[1])
    else:
        # Older framework dumps include the renderer's package in its surface title.
        frames = re.findall(r"Layer \[\d+\] [^\n]*SurfaceView\[(?:" + re.escape(PACKAGE)
                            + r"|com\.xreal\.evapro\.nebula)\]\(BLAST\)[^\n]*"
                            r"\n    visible reason=[^\n]*\bframe=(\d+)", data)
    return states, max(map(int, frames), default=-1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--server-port", type=int, default=5038)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/phone-screen-off")
    args = parser.parse_args()
    out = args.output; out.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(out).free < 32 * 1024 * 1024:
        raise RuntimeError("Free at least 32 MiB of local disk space before running the screen-off check")
    base = [str(ADB), "-P", str(args.server_port), "-s", args.serial]

    def adb(*parts, binary=False):
        return subprocess.check_output(base + list(parts), text=not binary, timeout=25)

    def shell(*parts):
        return adb("shell", *parts)

    def surface(name=None):
        data = shell("dumpsys", "SurfaceFlinger")
        if name: (out / (name + ".txt")).write_text(data)
        states, frame = surface_state(data)
        return data, states, frame

    def button_bounds():
        windows = shell("dumpsys", "window", "windows")
        for block in re.split(r"\n  Window #", windows):
            if not any(title in block.split("\n", 1)[0] for title in
                       ("Unbound controller screen-off touch target", "Unbound phone screen button")): continue
            frame = re.search(r"\bframe=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", block)
            if frame: return tuple(map(int, frame.groups()))
        (out / "missing-button-windows.txt").write_text(windows)
        raise AssertionError("Open the phone trackpad in AR Space first; screen-off control is unavailable")

    def tap(bounds):
        a, b, c, d = bounds
        shell("input", "-d", "0", "tap", str((a + c) // 2), str((b + d) // 2))

    def screenshot(name, physical):
        data = adb("exec-out", "screencap", "-p", "-d", physical, binary=True)
        (out / (name + ".png")).write_bytes(data[data.index(b"\x89PNG\r\n\x1a\n"):])

    def confirmation(action):
        for attempt in range(4):
            response = shell("uiautomator", "dump", "/sdcard/unbound-screen-test.xml")
            if "dumped to" not in response: continue
            xml = shell("cat", "/sdcard/unbound-screen-test.xml")
            (out / "screen-off-dialog.xml").write_text(xml)
            node = next((n for n in ET.fromstring(xml).iter("node") if n.get("text", "").lower() == action.lower()), None)
            if node is not None:
                tap(tuple(map(int, re.findall(r"\d+", node.get("bounds"))))); return
        raise AssertionError("Screen-off confirmation button not found")

    data, before, frame_before = surface("ui-before")
    phone = re.search(r"\nDisplay (\d+)\n    port=\d+\n    connectionType=Internal", data).group(1)
    external = re.search(r"\nDisplay (\d+)\n    port=\d+\n    connectionType=External", data).group(1)
    result = {"phone_physical_id": phone, "glasses_physical_id": external, "before": before}
    log_start = shell("date '+%m-%d %H:%M:%S.000'").strip()
    try:
        screenshot("trackpad-button", phone)
        tap(button_bounds())
        time.sleep(.4)
        screenshot("confirmation", phone)
        confirmation("Cancel")
        result["cancel_leaves_phone_on"] = surface()[1][phone] == "On"
        tap(button_bounds()); time.sleep(.3); confirmation("Turn off screen")
        for _ in range(12):
            time.sleep(.3)
            if surface()[1][phone] == "Off": break
        _, during, frame_start = surface("ui-panel-off-start")
        result["phone_physically_off"] = during[phone] == "Off"
        result["glasses_stay_on"] = during[external] == "On"
        assert result["phone_physically_off"], "Phone panel did not turn off"
        # Deliberate taps and drags must not reach the spatial controller or wake the phone.
        shell("input", "-d", "0", "tap", "600", "1000")
        shell("input", "-d", "0", "tap", "300", "1800")
        shell("input", "-d", "0", "swipe", "400", "1200", "850", "1600", "300")
        time.sleep(12)  # Longer than the watchdog lease; app heartbeats must keep it alive.
        _, later, frame_later = surface("ui-panel-off-after-touches")
        result["touches_do_not_wake_phone"] = later[phone] == "Off"
        result["heartbeat_survives_beyond_watchdog_timeout"] = later[phone] == "Off"
        result["glasses_remain_on_after_touches"] = later[external] == "On"
        result["glasses_frame_numbers"] = [frame_start, frame_later]
        result["glasses_frames_advance_while_phone_off"] = frame_start >= 0 and frame_later > frame_start + 100
        activities = shell("dumpsys", "activity", "activities")
        blocks = re.split(r"\n    \* Hist", activities)
        controller = next((b for b in blocks if PACKAGE + "/ai.nreal.nebula.space.LaunchSpaceAcrivity" in b[:250]), "")
        result["ar_controller_stays_resumed"] = "state=RESUMED" in controller
        screenshot("glasses-while-phone-off", external)
        shell("input", "-d", "0", "keyevent", "KEYCODE_VOLUME_UP")
        for _ in range(12):
            time.sleep(.3)
            if surface()[1][phone] == "On": break
        _, restored, _ = surface("ui-volume-restored")
        result["volume_up_restores_phone"] = restored[phone] == "On"
        result["glasses_stay_on_after_restore"] = restored[external] == "On"
        tap(button_bounds()); time.sleep(.3); confirmation("Turn off screen")
        for _ in range(12):
            time.sleep(.3)
            if surface()[1][phone] == "Off": break
        result["second_screen_off_succeeds"] = surface()[1][phone] == "Off"
        shell("input", "-d", "0", "keyevent", "KEYCODE_VOLUME_DOWN")
        for _ in range(12):
            time.sleep(.3)
            if surface()[1][phone] == "On": break
        result["volume_down_restores_phone"] = surface()[1][phone] == "On"
        time.sleep(.5)
        logs = shell("logcat -d -T " + shlex.quote(log_start) + " -s PortPhoneScreen PortPhonePointer AndroidRuntime")
        (out / "ui-runtime-logcat.txt").write_text(logs)
        counts = re.findall(r"blockedTouchEvents=(\d+)", logs)
        result["blocked_touch_event_count"] = max(map(int, counts), default=0)
        result["touch_events_intercepted"] = result["blocked_touch_event_count"] >= 6
        result["no_runtime_fatal_error"] = "FATAL EXCEPTION" not in logs
        result["no_binder_interface_error"] = "Binder invocation to an incorrect interface" not in logs
        suspended = re.findall(r"Motion pointer suspended: flags=(\d+) saved=(\d+)", logs)
        restored_pointer = re.findall(r"Motion pointer restored: flags=(\d+) saved=(\d+)", logs)
        result["pointer_was_active_before_screen_off"] = bool(suspended) and int(suspended[0][1]) & 3 == 3
        result["pointer_saved_and_restored_flags"] = restored_pointer
        result["pointer_pose_raycast_and_visuals_disabled"] = len(suspended) >= 2 and all(int(flags) == 0 for flags, _ in suspended)
        result["pointer_previous_state_restored_after_both_cycles"] = len(restored_pointer) >= 2 and all(flags == saved for flags, saved in restored_pointer)
        screenshot("trackpad-restored", phone)
        path = shell("pm", "path", PACKAGE).strip().split("package:", 1)[1]
        result["installed_apk_sha256"] = shell("sha256sum", path).split()[0]
        result["physical_wearer_confirmation"] = "not established by automated checks"
        (out / "runtime-verification.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2))
        assert all(value for value in result.values() if isinstance(value, bool)), "A runtime check failed"
    finally:
        try:
            if surface()[1].get(phone) == "Off":
                shell("input", "-d", "0", "keyevent", "KEYCODE_VOLUME_UP")
                time.sleep(1)
                if surface()[1].get(phone) == "Off":
                    shell("am", "force-stop", PACKAGE)  # Owner Binder death restores via watchdog.
        finally:
            # Restore the real phone before attempting another evidence write;
            # a full local disk must never skip panel restoration.
            (out / "runtime-verification.json").write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
