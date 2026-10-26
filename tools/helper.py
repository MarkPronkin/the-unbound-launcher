#!/usr/bin/env python3
"""Start/status/stop the local port helper under ADB shell, without root or open network ports."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
ADB = Path.home() / "Library/Android/sdk/platform-tools/adb"
REMOTE_JAR = "/data/local/tmp/beampro-shell-probe.jar"
REMOTE_LOG = "/data/local/tmp/beampro-launch.log"
REMOTE_PID = "/data/local/tmp/beampro-launch.pid"
CLASS = "dev.beampro.bridge.LaunchServer"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "status", "stop", "logs"])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--server-port", type=int, help="ADB server port, if using a separate server (for example 5038)")
    parser.add_argument("--restart", action="store_true")
    args = parser.parse_args()
    prefix = [str(ADB)]
    if args.server_port is not None:
        if not 1 <= args.server_port <= 65535:
            parser.error("--server-port must be between 1 and 65535")
        prefix.extend(["-P", str(args.server_port)])
    prefix.extend(["-s", args.serial])

    def adb(*parts, check=True):
        return subprocess.run(prefix + list(parts), capture_output=True, text=True, check=check, timeout=30)

    def read(path):
        return adb("shell", "cat", path, check=False).stdout

    def live_pid():
        value = read(REMOTE_PID).strip()
        if not re.fullmatch(r"\d+", value):
            matches = re.findall(r"BEAM_LAUNCH_SERVER_READY pid=(\d+) uid=2000", read(REMOTE_LOG))
            value = matches[-1] if matches else ""
        if not value:
            return None
        command = read("/proc/" + value + "/cmdline")
        return int(value) if CLASS in command else None

    pid = live_pid()
    if args.action == "logs":
        print(read(REMOTE_LOG))
        return
    if args.action == "status":
        print(json.dumps({"running": pid is not None, "pid": pid, "root": False}))
        return
    if args.action == "stop" or (pid and args.restart):
        if pid:
            adb("shell", "kill", str(pid))
            for _ in range(20):
                if CLASS not in read("/proc/" + str(pid) + "/cmdline"):
                    break
                time.sleep(0.1)
        adb("shell", "rm", "-f", REMOTE_PID)
        if args.action == "stop":
            print(json.dumps({"running": False, "stopped_pid": pid}))
            return
        pid = None
    if pid:
        print(json.dumps({"running": True, "pid": pid, "reused": True, "root": False}))
        return
    jar = ROOT / "artifacts/shell-probe/shell-probe.jar"
    if not jar.is_file():
        raise SystemExit("Build the helper with tools/build_shell_probe.py first")
    adb("push", str(jar), REMOTE_JAR)
    adb("shell", "CLASSPATH=" + REMOTE_JAR + " nohup app_process / " + CLASS
        + " >" + REMOTE_LOG + " 2>&1 </dev/null &")
    for _ in range(30):
        log = read(REMOTE_LOG)
        matches = re.findall(r"BEAM_LAUNCH_SERVER_READY pid=(\d+) uid=2000", log)
        if matches:
            pid = int(matches[-1])
            if CLASS not in read("/proc/" + str(pid) + "/cmdline"):
                raise SystemExit("Helper exited after startup:\n" + log)
            adb("shell", "printf '%s' '" + str(pid) + "' >" + REMOTE_PID)
            print(json.dumps({"running": True, "pid": pid, "root": False, "log": REMOTE_LOG}))
            return
        time.sleep(0.2)
    raise SystemExit("Helper did not become ready:\n" + read(REMOTE_LOG))


if __name__ == "__main__":
    main()
