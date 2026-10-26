#!/usr/bin/env python3
"""Verify the ten-app limit, telemetry removal, signature and unrelated APK data."""
import argparse
import json
from pathlib import Path
import zipfile

from app_limit import TARGETS, verify_app_limit
from verify_xreal_telemetry import verify
from xreal_telemetry import LIBRARY


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as archive:
        limit = verify_app_limit(archive.read(LIBRARY))
    result = verify(args.apk)
    result["max_simultaneous_apps"] = limit
    result["app_limit_patches"] = list(TARGETS)
    value = json.dumps(result, indent=2) + "\n"
    if args.report:
        args.report.write_text(value)
    print(value, end="")


if __name__ == "__main__":
    main()
