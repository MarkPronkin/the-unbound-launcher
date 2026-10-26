#!/usr/bin/env python3
"""Collect read-only Android/XREAL diagnostics; optionally pull application APKs."""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys


PROJECT = Path(__file__).resolve().parents[1]
PACKAGE_NAME = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+\Z")
XREAL_PACKAGE = re.compile(r"(?:^|\.)(?:xreal|nreal)(?:\.|$)|nebula|nrservice", re.I)
PROPERTIES = (
    "ro.product.manufacturer", "ro.product.brand", "ro.product.model",
    "ro.product.device", "ro.product.board", "ro.board.platform",
    "ro.build.fingerprint", "ro.build.version.release", "ro.build.version.sdk",
    "ro.build.version.security_patch", "ro.product.cpu.abilist",
    "ro.build.type", "ro.debuggable", "ro.boot.verifiedbootstate",
)


def find_adb():
    candidates = [shutil.which("adb")]
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 str(Path.home() / "Library/Android/sdk"), str(Path.home() / "Android/Sdk")):
        if root:
            candidates.append(str(Path(root) / "platform-tools/adb"))
    return next((str(p) for p in candidates if p and os.access(p, os.X_OK)), None)


def run(command, timeout=45):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
        return {"command": command, "returncode": result.returncode,
                "stdout": result.stdout, "stderr": result.stderr}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"command": command, "returncode": -1, "stdout": "", "stderr": str(error)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=find_adb(), help="Path to adb")
    parser.add_argument("--serial", required=True, help="Explicit adb device serial/address")
    parser.add_argument("--label", required=True, help="e.g. beam-pro or oneplus-15")
    parser.add_argument("--package", action="append", default=[], help="Additional package to inspect")
    parser.add_argument("--pull-apks", action="store_true", help="Copy selected APKs, including splits")
    parser.add_argument("--output", type=Path, default=PROJECT / "reports/devices")
    args = parser.parse_args()
    if not args.adb:
        parser.error("adb not found; use --adb")
    for package in args.package:
        if not PACKAGE_NAME.fullmatch(package):
            parser.error("Invalid Android package name: " + package)
    adb = [args.adb, "-s", args.serial]
    state = run(adb + ["get-state"])
    if state["returncode"] or state["stdout"].strip() != "device":
        print("Device is not available/authorized: " + state["stderr"], file=sys.stderr)
        return 1
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    label = re.sub(r"[^a-zA-Z0-9_-]", "-", args.label)
    output = args.output / (label + "-" + stamp)
    output.mkdir(parents=True, exist_ok=False)
    records = []

    def shell(command, filename=None, retain_stdout=True):
        result = run(adb + ["shell", command])
        records.append(result if retain_stdout else dict(result, stdout="Omitted: selected packages retained in xreal-packages.json"))
        if filename:
            (output / filename).write_text(result["stdout"], encoding="utf-8")
        return result

    properties = {}
    for key in PROPERTIES:
        result = shell("getprop " + shlex.quote(key))
        properties[key] = result["stdout"].strip() if result["returncode"] == 0 else None
    (output / "properties.json").write_text(json.dumps(properties, indent=2) + "\n")

    listing = shell("pm list packages -f", retain_stdout=False)
    packages = {}
    for line in listing["stdout"].splitlines():
        if line.startswith("package:") and "=" in line:
            location, package = line[8:].rsplit("=", 1)
            if PACKAGE_NAME.fullmatch(package) and (XREAL_PACKAGE.search(package) or package in args.package):
                packages[package] = location
    for package in args.package:
        packages.setdefault(package, None)
    (output / "xreal-packages.json").write_text(json.dumps(packages, indent=2) + "\n")

    for name, command in (
        ("shell-identity.txt", "id"),
        ("display.txt", "dumpsys display"),
        ("usb.txt", "dumpsys usb"),
        ("services.txt", "service list"),
        ("shared-libraries.txt", "pm list libraries"),
        ("features.txt", "pm list features"),
        ("framework-files.txt", "ls /system/framework /system_ext/framework /product/framework"),
        ("permission-files.txt", "ls /system/etc/permissions /system_ext/etc/permissions /product/etc/permissions /vendor/etc/permissions"),
    ):
        shell(command, name)

    pulled = []
    for package in sorted(packages):
        shell("dumpsys package " + shlex.quote(package), package + ".txt")
        paths = shell("pm path " + shlex.quote(package))
        if not args.pull_apks:
            continue
        for index, line in enumerate(paths["stdout"].splitlines()):
            if not line.startswith("package:"):
                continue
            remote = line[len("package:"):].strip()
            if not remote.startswith("/") or not remote.endswith(".apk"):
                continue
            destination = output / "apks" / package / (str(index) + "-" + Path(remote).name)
            destination.parent.mkdir(parents=True, exist_ok=True)
            result = run(adb + ["pull", remote, str(destination)], timeout=180)
            records.append(result)
            if result["returncode"] == 0 and destination.is_file():
                with destination.open("rb") as stream:
                    digest = hashlib.sha256()
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(chunk)
                pulled.append({"package": package, "remote": remote,
                               "local": str(destination.relative_to(output)), "sha256": digest.hexdigest()})

    failures = [r for r in records if r["returncode"] != 0]
    summary = {"label": args.label, "serial": args.serial, "properties": properties,
               "packages": packages, "apks": pulled, "failed_commands": len(failures),
               "status": "partial" if failures else "complete"}
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    (output / "commands.json").write_text(json.dumps(records, indent=2) + "\n")
    print(str(output))
    print("Selected packages: " + (", ".join(sorted(packages)) or "none detected"))
    print("APKs copied: %d; failed commands: %d" % (len(pulled), len(failures)))
    return 2 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
