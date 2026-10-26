#!/usr/bin/env python3
"""Verify launcher history hooks while preserving the installed native fixes and resources."""
import argparse
import json
from pathlib import Path
import subprocess
import zipfile

from build_port_bootstrap import sha256
from verify_xreal_telemetry import verify

ROOT = Path(__file__).resolve().parents[1]


def verify_candidate(apk, baseline):
    def payloads(archive):
        return {name for name in archive.namelist()
                if not (name.upper().startswith("META-INF/")
                        and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC", "MANIFEST.MF")))}
    changed = {"classes2.dex", "classes4.dex"}
    with zipfile.ZipFile(baseline) as previous, zipfile.ZipFile(apk) as candidate:
        names = payloads(previous)
        if names != payloads(candidate):
            raise ValueError("Candidate added or removed application payloads")
        for name in names - changed:
            if candidate.read(name) != previous.read(name):
                raise ValueError(f"Unrelated working-build payload changed: {name}")
        if candidate.read("classes4.dex") != (ROOT / "artifacts/port-client/classes.dex").read_bytes():
            raise ValueError("APK client differs from the compiled source")
    java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
    dex = subprocess.check_output([
        str(java / "bin/java"), "--class-path", str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
        str(ROOT / "tools/VerifyRecentAppsDex.java"), str(baseline), str(apk),
    ], text=True).strip()
    result = verify(apk)
    result.update({"baseline_apk": str(baseline.resolve()), "baseline_sha256": sha256(baseline),
                   "changed_payloads": sorted(changed), "unchanged_payloads": len(names) - len(changed),
                   "history_dex_scope": dex, "all_native_libraries_and_resources_unchanged": True,
                   "recent_apps": {"launcher_only": True, "max_entries": 32,
                                   "helper_owned_window_previews": True,
                                   "requires_updated_helper": True}})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--baseline", type=Path, default=ROOT / "artifacts/port-icon-placement/myglasses-startup-candidate.apk")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    value = json.dumps(verify_candidate(args.apk, args.baseline), indent=2) + "\n"
    if args.report:
        args.report.write_text(value)
    print(value, end="")


if __name__ == "__main__":
    main()
