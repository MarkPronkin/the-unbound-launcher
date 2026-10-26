#!/usr/bin/env python3
"""Audit the single-APK backend, class uniqueness, helper payload, and manifest access."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import zipfile

from branding import _pool_strings
from build_port_bootstrap import chunks
from build_embedded_adb import BASE, BASE_SHA, sha256, VERSION
from app_version import manifest_version
from embedded_adb_manifest import (
    AUTHORITY, SHIZUKU_AUTHORITY, SHIZUKU_PERMISSION, SHIZUKU_V3_META,
    patch_release_manifest, patch_usb_attach_manifest,
)
from adaptive_manifest import patch_adaptive_manifest
from display_startup import patch_display_startup
from app_color import APP_SHADER, LIBRARY, verify_app_shader, patch_color_dex, patch_color_native
from icon_resolution import patch_icon_resolution
from usb_permission import patch_usb_permission, patch_controller_usb_permission


def classes(dex):
    string_count, string_at, type_count, type_at = struct.unpack_from("<IIII", dex, 56)
    strings = []
    for i in range(string_count):
        at = struct.unpack_from("<I", dex, string_at + i * 4)[0]
        while dex[at] & 0x80: at += 1
        at += 1
        strings.append(dex[at:dex.index(b"\0", at)].decode("utf-8", errors="replace"))
    types = [strings[struct.unpack_from("<I", dex, type_at + i * 4)[0]] for i in range(type_count)]
    count, at = struct.unpack_from("<II", dex, 96)
    return {types[struct.unpack_from("<I", dex, at + i * 32)[0]] for i in range(count)}


def elements(manifest):
    nodes = list(chunks(manifest))
    _, at, _, size = next(n for n in nodes if n[0] == 1)
    strings = _pool_strings(manifest[at:at + size])
    result = []
    for kind, at, _, _ in nodes:
        if kind != 0x102: continue
        tag = strings[struct.unpack_from("<I", manifest, at + 20)[0]]
        offset, stride, count = struct.unpack_from("<HHH", manifest, at + 24)
        attrs = {}
        for i in range(count):
            pos = at + 16 + offset + i * stride
            name = strings[struct.unpack_from("<I", manifest, pos + 4)[0]]
            value = struct.unpack_from("<I", manifest, pos + 16)[0]
            attrs[name] = strings[value] if manifest[pos + 15] == 3 else value
        result.append((tag, attrs))
    return result


def verify_attribute_order(manifest):
    nodes = list(chunks(manifest))
    _, at, header, size = next(n for n in nodes if n[0] == 0x180)
    resources = struct.unpack_from("<" + "I" * ((size - header) // 4), manifest, at + header)
    for kind, at, _, _ in nodes:
        if kind != 0x102: continue
        offset, stride, count = struct.unpack_from("<HHH", manifest, at + 24)
        ids = []
        for i in range(count):
            name = struct.unpack_from("<I", manifest, at + 16 + offset + i * stride + 4)[0]
            if name < len(resources) and resources[name]: ids.append(resources[name])
        if ids != sorted(ids):
            raise ValueError(f"Manifest attributes are not ordered by Android resource ID at {at}")


def verify(apk):
    if sha256(BASE) != BASE_SHA: raise ValueError("Unexpected baseline")
    with zipfile.ZipFile(apk) as z, zipfile.ZipFile(BASE) as base:
        assert manifest_version(z.read("AndroidManifest.xml")) == VERSION
        verify_attribute_order(z.read("AndroidManifest.xml"))
        entries = elements(z.read("AndroidManifest.xml"))
        before = elements(patch_usb_attach_manifest(patch_release_manifest(patch_adaptive_manifest(base.read("AndroidManifest.xml")))))
        application = [attrs for tag, attrs in entries if tag == "application"]
        assert len(application) == 1 and application[0]["debuggable"] == 0
        assert [e for e in entries if e[0] == "uses-permission"] == (
            [e for e in before if e[0] == "uses-permission"] + [("uses-permission", {"name": SHIZUKU_PERMISSION})]
        )
        for tag, name, exported in [("activity", "AdbPairingActivity", 0), ("service", "PairingOverlayService", 0), ("provider", "LocalBridgeProvider", 0xFFFFFFFF)]:
            match = [a for t, a in entries if t == tag and a.get("name") == "dev.beampro.adb." + name]
            assert len(match) == 1 and match[0]["exported"] == exported
            if tag == "provider": assert match[0]["authorities"] == AUTHORITY and match[0]["process"] == ":unbound_bridge"
        shizuku_provider = [a for t, a in entries if t == "provider" and a.get("name") == "rikka.shizuku.ShizukuProvider"]
        assert len(shizuku_provider) == 1 and shizuku_provider[0]["exported"] == 0xFFFFFFFF and shizuku_provider[0]["authorities"] == SHIZUKU_AUTHORITY
        shizuku_meta = [a for t, a in entries if t == "meta-data" and a.get("name") == SHIZUKU_V3_META]
        assert len(shizuku_meta) == 1 and shizuku_meta[0]["value"] == 0xFFFFFFFF
        for entry in before:
            if entry[0] not in ("manifest",): assert entry in entries, entry
        backdrop=[a for t,a in entries if t=='activity' and a.get('name')=='dev.beampro.port.ArBackdropActivity']
        assert len(backdrop)==1 and backdrop[0]['exported']==0
        definitions = {}; duplicate = []
        for name in z.namelist():
            if not (name.startswith("classes") and name.endswith(".dex")): continue
            for cls in classes(z.read(name)):
                if cls in definitions: duplicate.append(cls)
                definitions[cls] = name
        assert not duplicate, duplicate
        for required in ["dev/beampro/adb/LocalAdb", "dev/beampro/adb/AdbBootstrap", "dev/beampro/adb/LocalBridgeProvider", "dev/beampro/adb/PhoneSetupHelp", "dev/beampro/adb/ShizukuClient", "rikka/shizuku/ShizukuProvider", "moe/shizuku/api/BinderContainer", "io/github/muntashirakon/crypto/spake2/Spake2Context", "org/conscrypt/Conscrypt", "dev/beampro/settings/AdaptiveLayout", "dev/beampro/settings/AppVersion"]:
            assert "L" + required + ";" in definitions
        assert VERSION["version_name"].encode() in z.read(definitions["Ldev/beampro/settings/AppVersion;"])
        assert not any(test in c for c in definitions for test in ("AdbDeviceChecks", "AdbSetupChecks", "SetupEntryActivity", "SetupApplication", "PairingCryptoChecks", "PairingNotificationChecks"))
        assert b"dev.beampro.portprobe.bridge" not in z.read("classes4.dex")
        assert "Ldev/beampro/port/DisplayStartupCompat;" in definitions
        assert "Ldev/beampro/port/StereoPresentationCompat;" in definitions
        assert "Ldev/beampro/port/ArBackdropActivity;" in definitions
        assert "Ldev/beampro/port/HeadsetIconCompat;" in definitions
        assert "Ldev/beampro/port/AppColorCompat;" in definitions
        assert "Ldev/beampro/port/UsbPermissionCompat;" in definitions
        java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
        with tempfile.TemporaryDirectory() as work:
            startup_base=patch_display_startup(base.read("classes.dex"),Path(work)/"startup-base",java)
            controller=patch_controller_usb_permission(z.read("classes.dex"),Path(work)/"controller",java,restore=True,baseline=startup_base)
            restored = patch_display_startup(controller, Path(work), java, restore=True,
                                             baseline=base.read("classes.dex"))
            assert restored == base.read("classes.dex"), "Display startup patch scope differs from the baseline"
            restored = patch_display_startup(z.read("classes3.dex"), Path(work) / "presentation", java, restore=True,
                                             baseline=base.read("classes3.dex"))
            assert restored == base.read("classes3.dex"), "SDK Presentation hook differs from the baseline"
            icon_base=patch_icon_resolution(base.read("classes2.dex"),Path(work)/"icon-base",java)
            color_base=patch_color_dex(icon_base,Path(work)/"color-base",java)
            restored=patch_usb_permission(z.read("classes2.dex"),Path(work)/"usb",java,restore=True,baseline=color_base)
            restored=patch_color_dex(restored,Path(work)/"colors",java,restore=True,baseline=icon_base)
            restored = patch_icon_resolution(restored,Path(work)/"icons",java,restore=True,baseline=base.read("classes2.dex"))
            assert restored == base.read("classes2.dex"), "Headset icon hook scope differs from the baseline"
            assert patch_color_native(z.read(LIBRARY),restore=True)==base.read(LIBRARY), "Native color selection scope differs"
        helper = z.read("assets/unbound-shell.jar")
        helper_sha = hashlib.sha256(helper).hexdigest()
        assert helper_sha.encode() in b"".join(z.read(n) for n in z.namelist() if n.startswith("classes") and n.endswith(".dex"))
        with zipfile.ZipFile(io.BytesIO(helper)) as h:
            server = h.read("classes.dex")
            assert "Ldev/beampro/bridge/XrealUsbPermissions;" in classes(server)
            assert b"BEAM_XREAL_USB_GRANTED" in server and b"dev.beampro.bridge.XREAL_USB" in server
            assert AUTHORITY.encode() in server and b"dev.beampro.portprobe.bridge" not in server
            assert b"getApplicationInfo" in server and b"com.xreal.evapro.nebula" in server
            for marker in (b"BEAM_DISPLAY_WATCHDOG_READY",b"setConnectionPreference",b"setShouldAlwaysRespectAppRequestedMode",
                           b"setUserPreferredDisplayMode",b"display-policy.properties"):
                assert marker in server, marker
            with tempfile.TemporaryDirectory() as work:
                dex=Path(work)/"helper.dex";dex.write_bytes(server)
                subprocess.run([str(java/"bin/java"),"--class-path",str(BASE.parents[1]/"tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
                                str(Path(__file__).with_name("VerifyManagedDisplayDex.java")),str(dex)],check=True)
                subprocess.run([str(java/"bin/java"),"--class-path",str(BASE.parents[1]/"tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
                                str(Path(__file__).with_name("VerifyRecentPreviewDex.java")),str(dex)],check=True)
                subprocess.run([str(java/"bin/java"),"--class-path",str(BASE.parents[1]/"tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
                                str(Path(__file__).with_name("VerifyProviderCallDex.java")),str(dex)],check=True)
                client=Path(work)/"client.dex";client.write_bytes(z.read("classes4.dex"))
                subprocess.run([str(java/"bin/java"),"--class-path",str(BASE.parents[1]/"tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
                                str(Path(__file__).with_name("VerifyUsbPermissionDex.java")),str(dex),str(client),str(apk)],check=True)
        for name in ["lib/arm64-v8a/libspake2.so", "lib/arm64-v8a/libconscrypt_jni.so", "assets/unbound-adb/pairing-sources.zip", "assets/unbound-adb/NOTICE.txt", "assets/unbound-adb/conscrypt-LICENSE.txt"]:
            assert len(z.read(name)) > 0
        with zipfile.ZipFile(io.BytesIO(z.read("assets/unbound-adb/pairing-sources.zip"))) as sources:
            assert json.loads(sources.read("version.json")) == VERSION
            assert sources.read("tools/app_version.py")
        changed = [n for n in base.namelist() if not n.upper().startswith("META-INF/") and base.read(n) != z.read(n)]
        color_report=verify_app_shader(z,base)
        assert color_report['automatic_decode_selection']
        expected={"classes.dex", "classes2.dex", "classes3.dex", "classes4.dex", "classes5.dex", "AndroidManifest.xml",APP_SHADER,LIBRARY}
        assert set(changed) == expected, changed
    return {"apk": str(apk.resolve()), "sha256": sha256(apk), "passed": True, "version": VERSION,
            "single_apk": True, "companion_dependency_removed": True, "helper_sha256": helper_sha,
            "debuggable": False,
            "about_version_packaged": True,
            "bundled_source_version_matches_package": True,
            "xreal_usb_request_hook_restores_exact_baseline": True,
            "xreal_usb_helper_and_deferred_callback_wired": True,
            "android_11_provider_contract_adapter_wired": True,
            "manufacturer_setup_help_packaged": True,
            "class_definitions": len(definitions), "duplicate_classes": 0, "original_permissions_preserved": True,
            "native_libraries_unchanged_except_audited_color_selector": True,
            "all_original_resources_except_audited_app_shader_identical": True,
            "all_original_native_libraries_and_resources_identical": False,
            "app_colors": color_report,
            "pairing_and_overlay_components_not_exported": True, "provider_separate_process": True,
            "runtime_device_checks_excluded": True, "android_attribute_order_verified": True,
            "display_startup_hooks_restore_exact_baseline": True,
            "headset_icon_hooks_restore_exact_baseline": True,
            "color_hooks_restore_exact_baseline": True,
            "headless_recent_preview_capture_verified": True,
            "managed_display_startup_and_watchdog_code_verified": True,
            "legacy_xreal_connected_display_auto_enable_code_verified": True,
            "direct_snapshot_reader_wired_in_production_dex": True,
            "display_guard_avoids_app_bound_services": True,
            "managed_display_hardware_validation": "pending", "changed_payloads": changed}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args(); result = verify(args.apk)
    text = json.dumps(result, indent=2) + "\n"
    if args.report: args.report.write_text(text)
    print(text)
