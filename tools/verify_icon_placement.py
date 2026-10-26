#!/usr/bin/env python3
"""Verify the icon fix and every application payload against the installed build."""
import argparse
import json
from pathlib import Path
import zipfile

from app_limit import verify_app_limit
from build_port_bootstrap import sha256
from icon_placement import TARGETS, restore_icon_placement
from verify_xreal_telemetry import verify
from xreal_telemetry import LIBRARY

ROOT = Path(__file__).resolve().parents[1]


def verify_candidate(apk, baseline):
    def payloads(archive):
        return {name for name in archive.namelist()
                if not (name.upper().startswith("META-INF/")
                        and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC", "MANIFEST.MF")))}
    with zipfile.ZipFile(baseline) as previous, zipfile.ZipFile(apk) as candidate:
        names = payloads(previous)
        if names != payloads(candidate):
            raise ValueError("Candidate added or removed application payloads")
        if restore_icon_placement(candidate.read(LIBRARY)) != previous.read(LIBRARY):
            raise ValueError("Unity library differs outside the icon-placement patch")
        for name in names - {LIBRARY}:
            if candidate.read(name) != previous.read(name):
                raise ValueError(f"Unrelated working-build payload changed: {name}")
        limit = verify_app_limit(candidate.read(LIBRARY))
    result = verify(apk)
    result.update({"baseline_apk": str(baseline.resolve()),
                   "baseline_sha256": sha256(baseline),
                   "only_working_build_payload_changed": LIBRARY,
                   "unchanged_working_build_payloads": len(names) - 1,
                   "icon_placement_patches": TARGETS,
                   "max_simultaneous_apps": limit})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--baseline", type=Path,
                        default=ROOT / "artifacts/port-buffered-presentation/myglasses-startup-candidate.apk")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = verify_candidate(args.apk, args.baseline)
    value = json.dumps(result, indent=2) + "\n"
    if args.report:
        args.report.write_text(value)
    print(value, end="")


if __name__ == "__main__":
    main()
