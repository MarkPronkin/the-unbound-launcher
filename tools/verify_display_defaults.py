#!/usr/bin/env python3
"""Audit stock display startup and exclusion of the archived display controls."""
import argparse
import json
from pathlib import Path
import subprocess
import zipfile

from app_menu import restore_app_menu
from trackpad_taps import restore_trackpad_taps
from app_color import patch_color_native, native_spec

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "artifacts/port-default-m/myglasses-startup-candidate.apk"
MARKERS = (b"Ldev/beampro/settings/DisplayMode", b"unbound-display-mode",
           b"mono_refresh_hz", b"stereo_refresh_hz")


def verify_display_control_excluded(apk):
    with zipfile.ZipFile(apk) as candidate:
        for name in candidate.namelist():
            if name.startswith("archive/") or name.endswith((".tar.gz", ".java", ".py", ".S")):
                raise ValueError(f"Research source packaged in APK: {name}")
            if name.endswith(".dex"):
                dex = candidate.read(name)
                if any(marker in dex for marker in MARKERS):
                    raise ValueError(f"Archived display control still referenced in {name}")
        manifest = candidate.read("AndroidManifest.xml")
        for name in ("dev.beampro.settings.DisplayModeActivity", "dev.beampro.settings.DisplayModeChecks"):
            if name.encode("utf-16le") in manifest or name.encode() in manifest:
                raise ValueError(f"Archived display component remains in the manifest: {name}")


def verify_display_defaults(apk, baseline=BASELINE, java=None, env=None):
    verify_display_control_excluded(apk)
    with zipfile.ZipFile(baseline) as original, zipfile.ZipFile(apk) as candidate:
        unity = "lib/arm64-v8a/libil2cpp.so"
        library = candidate.read(unity)
        # Accept the independently pinned color-selector patch on universal builds.
        color_target=native_spec()['targets'][0]
        color_at=color_target['address']
        if library[color_at:color_at+4]==bytes.fromhex(color_target['replacement']):
            library=patch_color_native(library,restore=True)
        if b"unbound-display-mode" in library:
            raise ValueError("Native saved display-choice reader remains")
        if restore_app_menu(restore_trackpad_taps(library)) != original.read(unity):
            raise ValueError("Unity library differs outside app-menu and trackpad patches")
        xr = "lib/arm64-v8a/libXREALXRPlugin.so"
        if candidate.read(xr) != original.read(xr):
            raise ValueError("XR renderer differs from the baseline provider")
    if java is None:
        java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
    subprocess.run([str(Path(java) / "bin/java"), "--class-path",
                    str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
                    str(ROOT / "tools/VerifyDisplayDefaultsDex.java"), str(baseline), str(apk)],
                   check=True, env=env)
    return {"original_device_specific_startup_selector_verified": True,
            "native_library_restores_to_baseline_with_only_app_menu_trackpad_and_audited_color_changes": True,
            "xr_provider_identical_to_baseline": True,
            "archived_display_classes_preferences_and_native_reader_absent": True,
            "display_mode_activity_absent_from_manifest": True,
            "research_source_excluded_from_apk": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = json.dumps(verify_display_defaults(args.apk, args.baseline), indent=2) + "\n"
    if args.report:
        args.report.write_text(result)
    print(result, end="")


if __name__ == "__main__":
    main()
