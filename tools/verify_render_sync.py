#!/usr/bin/env python3
"""Verify the presentation candidate and exact payload scope against a working build."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

from app_limit import verify_app_limit
from render_sync import XR_LIBRARY, XR_SHA256, restore_render_sync, verify_render_sync
from verify_xreal_telemetry import verify
from xreal_telemetry import LIBRARY
from icon_placement import has_icon_placement_patch, restore_icon_placement

ROOT = Path(__file__).resolve().parents[1]


def verify_candidate(apk, baseline):
    with zipfile.ZipFile(baseline) as previous, zipfile.ZipFile(apk) as candidate:
        # APK signing blocks live outside the ZIP entries. Ignore legacy signing
        # entries if present, while comparing all actual application payloads.
        def payloads(archive):
            return {name for name in archive.namelist()
                    if not (name.upper().startswith("META-INF/")
                            and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC", "MANIFEST.MF")))}
        names = payloads(previous)
        if names != payloads(candidate):
            raise ValueError("Candidate added or removed application payloads")
        original = previous.read(XR_LIBRARY)
        if hashlib.sha256(original).hexdigest() != XR_SHA256:
            raise ValueError("Baseline does not contain the pinned original XR plugin")
        patched = candidate.read(XR_LIBRARY)
        presentation = verify_render_sync(patched)
        if restore_render_sync(patched) != original:
            raise ValueError("XR plugin differs outside the presentation patch")
        changed_payloads = [XR_LIBRARY]
        for name in names - {XR_LIBRARY}:
            current, before = candidate.read(name), previous.read(name)
            # New builds also fix floating-icon initialization. Audit that
            # complete patch independently when comparing to older builds.
            if name == LIBRARY and current != before and has_icon_placement_patch(current):
                if restore_icon_placement(current) != before:
                    raise ValueError("Unity library differs outside the icon-placement patch")
                changed_payloads.append(LIBRARY)
            elif current != before:
                raise ValueError(f"Working-build payload changed: {name}")
        limit = verify_app_limit(candidate.read(LIBRARY))
    with baseline.open("rb") as stream:
        baseline_hash = hashlib.file_digest(stream, "sha256").hexdigest()
    result = verify(apk)
    result.update({"baseline_apk": str(baseline.resolve()),
                   "baseline_sha256": baseline_hash,
                   "only_working_build_payload_changed": XR_LIBRARY if len(changed_payloads) == 1 else None,
                   "working_build_payloads_changed": changed_payloads,
                   "unchanged_working_build_payloads": len(names) - len(changed_payloads),
                   "max_simultaneous_apps": limit,
                   "buffered_presentation": presentation,
                   "runtime_validation": "not performed; user requested no OnePlus use"})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--baseline", type=Path,
                        default=ROOT / "artifacts/port-quick-access/myglasses-startup-candidate.apk")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = verify_candidate(args.apk, args.baseline)
    value = json.dumps(result, indent=2) + "\n"
    if args.report:
        args.report.write_text(value)
    print(value, end="")


if __name__ == "__main__":
    main()
