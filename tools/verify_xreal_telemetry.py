#!/usr/bin/env python3
"""Verify the rebuilt APK's XREAL telemetry removal and preserve unrelated payloads."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

from build_port_bootstrap import SOURCE, SOURCE_SHA, sha256
from xreal_telemetry import LIBRARY, MANIFEST_COMPONENTS, verify_library
from quick_access import MENU_ASSET, has_quick_access_patch, restore_quick_access_library, verify_menu
from render_sync import XR_LIBRARY, verify_render_sync
from icon_placement import has_icon_placement_patch, restore_icon_placement
from window_size import DEFAULT_SIZE, DEFAULT_FOV_PERCENT, has_window_size_patch, restore_window_size

ROOT = Path(__file__).resolve().parents[1]


def verify(apk, source=SOURCE):
    if sha256(source) != SOURCE_SHA:
        raise ValueError("Unexpected source APK")
    sdk = Path.home() / "Library/Android/sdk/build-tools/36.0.0"
    java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))
    tree = subprocess.check_output([str(sdk / "aapt2"), "dump", "xmltree", str(apk),
                                    "--file", "AndroidManifest.xml"], text=True)
    for component in MANIFEST_COMPONENTS:
        if re.search(r'android:name[^\n]*="' + re.escape(component) + '"', tree):
            raise ValueError(f"Telemetry component remains: {component}")
    if not re.search(r'android:name[^\n]*="android.permission.INTERNET"', tree):
        raise ValueError("Ordinary Internet permission was removed")
    if '="ai.nreal.nebula.service.InputDetectService"' not in tree:
        raise ValueError("Input service was removed")
    if re.search(r'android:sharedUserId[^\n]*="android.uid.system"', tree):
        raise ValueError("Platform shared UID remains")
    command = [str(java / "bin/java"), "--class-path",
               str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
               str(ROOT / "tools/PatchTelemetryDex.java")]
    dex_checks = {}
    preserved = 0
    quick_access = False
    quick_access_client = False
    presentation = {"enabled": False}
    icon_placement = False
    window_size = False
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(apk) as patched, tempfile.TemporaryDirectory() as temporary:
        for info in original.infolist():
            name = info.filename
            if name.upper().startswith("META-INF/") and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC", "MANIFEST.MF")):
                continue
            if name in ("AndroidManifest.xml", LIBRARY) or re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name):
                continue
            current = patched.getinfo(name)
            if (current.CRC, current.file_size) != (info.CRC, info.file_size):
                if name == MENU_ASSET:
                    verify_menu(patched.read(name))
                    quick_access = True
                    continue
                if name == XR_LIBRARY:
                    presentation = verify_render_sync(patched.read(name))
                    continue
                raise ValueError(f"Unrelated APK payload changed: {name}")
            preserved += 1
        for name in patched.namelist():
            if not re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name):
                continue
            data = patched.read(name)
            if any(value in data for value in (b"Lcom/sensorsdata/", b"Lcom/xreal/analytics/sdk/", b"/v1/data-country-domain")):
                raise ValueError(f"XREAL uploader SDK or discovery URL remains in {name}")
            path = Path(temporary) / name
            path.write_bytes(data)
            dex_checks[name] = subprocess.check_output([*command, "verify", str(path), str(path)],
                                                       text=True, env=env).strip()
        unity_blob = patched.read(LIBRARY)
        unity_count = verify_library(unity_blob)
        if has_window_size_patch(unity_blob):
            unity_blob = restore_window_size(unity_blob)
            window_size = True
        if has_icon_placement_patch(unity_blob):
            unity_blob = restore_icon_placement(unity_blob)
            icon_placement = True
        if quick_access:
            restore_quick_access_library(unity_blob)
            if "classes4.dex" in patched.namelist():
                subprocess.run([str(java / "bin/java"), "--class-path",
                                str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
                                str(ROOT / "tools/VerifyQuickAccessDex.java"), str(apk)], check=True, env=env)
                quick_access_client = True
        elif has_quick_access_patch(patched.read(LIBRARY)):
            raise ValueError("Quick-access native fix is present without Game Mode removal")
    signature = subprocess.check_output([str(sdk / "apksigner"), "verify", "--verbose", str(apk)], text=True, env=env)
    subprocess.run([str(sdk / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
    return {"apk": str(apk.resolve()), "sha256": sha256(apk), "result": "passed",
            "internet_permission_preserved": True, "analytics_components_absent": sorted(MANIFEST_COMPONENTS),
            "dex_checks": dex_checks, "unity_collectors_disabled": unity_count,
            "unrelated_entries_preserved": preserved,
            "third_party_crash_reporting_payloads_preserved": True,
            "automatic_sensor_bias_uploads_disabled": True,
            "signature": signature.strip().splitlines(), "zip_alignment": "passed",
            "quick_access": {"game_mode_removed": quick_access,
                             "settings_intent_metadata_fallback": quick_access,
                             "connected_ssid_via_signed_helper": quick_access_client,
                             "navigate_existing_radio_settings_window": quick_access_client},
            "buffered_presentation": presentation,
            "icon_placement": {"floating_icon_initialized": icon_placement},
            "default_window_size": {"size": DEFAULT_SIZE if window_size else None,
                                    "fov_percent": DEFAULT_FOV_PERCENT if window_size else None,
                                    "saved_user_sizes_preserved": window_size},
            "runtime_validation": "not performed by this static verifier; see runtime-verification.json when available"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = verify(args.apk)
    value = json.dumps(result, indent=2) + "\n"
    if args.report:
        args.report.write_text(value)
    print(value, end="")


if __name__ == "__main__":
    main()
