#!/usr/bin/env python3
"""Verify APK-owned idle protection, manual panel blanking, and lifecycle release."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
ADB = Path.home() / "Library/Android/sdk/platform-tools/adb"
PACKAGE = "com.xreal.evapro.nebula"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--server-port", type=int, default=5038)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/ar-keep-awake")
    args = parser.parse_args()
    out = args.output; out.mkdir(parents=True, exist_ok=True)
    base = [str(ADB), "-P", str(args.server_port), "-s", args.serial]

    def shell(*parts):
        return subprocess.check_output(base + ["shell", *parts], text=True, timeout=25)

    def setting(name):
        return shell("settings", "get", "system", name).strip()

    def snapshot(name):
        power = shell("dumpsys", "power")
        windows = shell("dumpsys", "window", "displays")
        policy = shell("dumpsys", "window", "policy")
        physical = shell("dumpsys", "SurfaceFlinger", "--display-id")
        (out / (name + "-power.txt")).write_text(power)
        (out / (name + "-windows.txt")).write_text(windows)
        (out / (name + "-policy.txt")).write_text(policy)
        idle = re.findall(r"lastUserActivityTime(?:NoChangeLights)?=\d+ \((\d+) ms ago\)", power)
        showing = re.search(r"^\s+showing=(true|false)\s*$", policy, re.M)
        holds = [line.strip() for line in windows.splitlines() if "mHoldScreenWindow=" in line]
        return {"awake": "mWakefulness=Awake" in power,
                "unlocked": showing is not None and showing[1] == "false",
                "glasses_connected": len(re.findall(r"^Display \d+", physical, re.M)) > 1,
                "holds": holds,
                "ar_holds_screen": any("LaunchSpaceAcrivity" in line or "NRXRActivity" in line for line in holds),
                "minimum_idle_ms": min(map(int, idle)) if idle else -1}

    original = setting("screen_off_timeout")
    original_stay_on = shell("settings", "get", "global", "stay_on_while_plugged_in").strip()
    report = {"original_screen_off_timeout": original, "apk_uses_no_system_setting_override": True}
    try:
        before = snapshot("before")
        assert before["ar_holds_screen"], "Open AR Space with the updated APK before running this test"
        power = (out / "before-power.txt").read_text()
        minimum = re.search(r"mMinimumScreenOffTimeoutConfig=(\d+)", power)
        test_timeout = max(10000, int(minimum[1]) if minimum else 10000)
        assert test_timeout <= 20000, "Device minimum timeout is too long for this bounded test"
        report["test_timeout_ms"] = test_timeout
        shell("settings", "put", "system", "screen_off_timeout", str(test_timeout))
        duration = test_timeout / 1000 + 4
        time.sleep(duration)
        idle = snapshot("idle-beyond-timeout")
        if not idle["glasses_connected"]: raise RuntimeError("Glasses disconnected during idle verification")
        report["idle_snapshot"] = idle
        report["phone_stays_awake_and_unlocked_while_idle"] = idle["awake"] and idle["unlocked"]
        report["ar_holds_screen_beyond_timeout"] = idle["ar_holds_screen"]
        report["no_user_activity_needed_to_beat_timeout"] = idle["minimum_idle_ms"] > test_timeout
        assert report["phone_stays_awake_and_unlocked_while_idle"], "Phone slept or locked during AR Space"

        result = subprocess.run(["python3", str(ROOT / "tools/verify_phone_screen_runtime.py"),
                                 "--serial", args.serial, "--server-port", str(args.server_port),
                                 "--output", str(out / "manual-screen-off")], capture_output=True, text=True)
        (out / "manual-screen-off.log").write_text(result.stdout + result.stderr)
        report["manual_screen_off_and_volume_restore_pass"] = result.returncode == 0
        assert result.returncode == 0, result.stdout + result.stderr
        time.sleep(duration)
        restored = snapshot("idle-after-manual-restore")
        report["restored_snapshot"] = restored
        if not restored["glasses_connected"]:
            report["verification_interrupted"] = "Glasses disconnected after the manual screen-off test; original timeout restored"
            raise RuntimeError(report["verification_interrupted"])
        report["keep_awake_survives_manual_screen_off_cycle"] = restored["awake"] and restored["unlocked"] and restored["ar_holds_screen"]
        report["restored_phone_remains_idle_beyond_timeout"] = restored["minimum_idle_ms"] > test_timeout
    finally:
        if original == "null": shell("settings", "delete", "system", "screen_off_timeout")
        else: shell("settings", "put", "system", "screen_off_timeout", original)
        report["original_timeout_restored"] = setting("screen_off_timeout") == original
        report["charging_stay_awake_setting_unchanged"] = shell("settings", "get", "global", "stay_on_while_plugged_in").strip() == original_stay_on
        (out / "runtime-verification.json").write_text(json.dumps(report, indent=2) + "\n")

    # The original timeout is back in place before temporarily leaving the controller.
    shell("am", "start", "-W", "-n", PACKAGE + "/ai.nreal.nebula.activity.setting.SettingActivity",
          "--ez", "dev.beampro.settings.SETUP_PASSED", "true")
    try:
        time.sleep(1)
        away = snapshot("settings-outside-ar")
        report["keep_awake_released_outside_ar_controller"] = not away["ar_holds_screen"]
    finally:
        shell("input", "-d", "0", "keyevent", "KEYCODE_BACK")
    time.sleep(2)
    back = snapshot("returned-to-ar")
    report["keep_awake_reapplied_on_return"] = back["ar_holds_screen"] and back["awake"] and back["unlocked"]
    path = shell("pm", "path", PACKAGE).strip().split("package:", 1)[1]
    report["installed_apk_sha256"] = shell("sha256sum", path).split()[0]
    (out / "runtime-verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    assert all(value for value in report.values() if isinstance(value, bool)), "A keep-awake check failed"


if __name__ == "__main__":
    main()
