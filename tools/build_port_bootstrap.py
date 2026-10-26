#!/usr/bin/env python3
"""Build a local, offline My Glasses startup candidate. This is not a finished port.

Preserves resources, removes the platform shared UID, adds debugging, disables the
firmware-cache initializer and removes XREAL's analytics uploader and Unity collectors.
Network access is optional; ordinary network functionality is not telemetry.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import zipfile
import zlib
import re

from xreal_telemetry import LIBRARY, MANIFEST_COMPONENTS, patch_library, verify_library
from app_limit import MAX_APPS, patch_app_limit, verify_app_limit
from quick_access import MENU_ASSET, patch_menu, patch_quick_access_library, restore_quick_access_library, verify_menu
from render_sync import XR_LIBRARY, patch_render_sync, verify_render_sync
from icon_placement import patch_icon_placement, restore_icon_placement
from window_size import DEFAULT_SIZE, DEFAULT_FOV_PERCENT, patch_window_size, restore_window_size
from wireless_cast import patch_wireless_cast_dex

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "reports/devices/beam-pro-20261025T055332771855Z/apks/com.xreal.evapro.nebula/0-base.apk"
SOURCE_SHA = "c509b9790dde9518012d536b478091e22c4a48a4ff32bf7535274fe62bc8a3ce"


def sha256(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def chunks(blob):
    position = 8
    while position < len(blob):
        kind, header, size = struct.unpack_from("<HHI", blob, position)
        if size < header or position + size > len(blob):
            raise ValueError("Invalid XML chunk")
        yield kind, position, header, size
        position += size


def patch_manifest(original, allow_network=False):
    blob = bytearray(original)
    pool = next(item for item in chunks(blob) if item[0] == 1)
    _, start, header, size = pool
    count, styles, flags, string_start, style_start = struct.unpack_from("<IIIII", blob, start + 8)
    if flags & 256:
        raise ValueError("This patch is pinned to the original UTF-16 manifest")
    offsets = [start + string_start + struct.unpack_from("<I", blob, start + header + n * 4)[0] for n in range(count)]
    strings = []
    for offset in offsets:
        length = struct.unpack_from("<H", blob, offset)[0]
        if length & 0x8000:
            raise ValueError("Unexpected long manifest string")
        strings.append(blob[offset + 2:offset + 2 + length * 2].decode("utf-16le"))
    shared_index = strings.index("sharedUserId")
    assert "debuggable" not in strings
    new_name = "debuggable".encode("utf-16le")
    offset = offsets[shared_index]
    old_length = struct.unpack_from("<H", blob, offset)[0]
    replacement = struct.pack("<H", len("debuggable")) + new_name + b"\0\0"
    blob[offset:offset + 2 + old_length * 2 + 2] = replacement.ljust(2 + old_length * 2 + 2, b"\0")
    struct.pack_into("<I", blob, start + 16, flags & ~1)  # The repurposed pool is not sorted.
    strings[shared_index] = "debuggable"
    resource = next(item for item in chunks(blob) if item[0] == 0x180)
    _, resource_start, resource_header, resource_size = resource
    resources = list(struct.unpack_from("<" + "I" * ((resource_size - resource_header) // 4), blob, resource_start + resource_header))
    assert resources[shared_index] == 0x0101000B
    resources[shared_index] = 0x0101000F
    struct.pack_into("<I", blob, resource_start + resource_header + shared_index * 4, resources[shared_index])
    new_chunks = []
    removed_uid = 0
    removed_internet = 0
    added_debug = 0
    removed_telemetry = set()
    skipping_depth = 0
    for kind, position, chunk_header, chunk_size in chunks(blob):
        node = bytearray(blob[position:position + chunk_size])
        if skipping_depth:
            if kind == 0x102:
                skipping_depth += 1
            elif kind == 0x103:
                skipping_depth -= 1
            continue
        if kind == 0x102:
            element_index = struct.unpack_from("<I", node, 20)[0]
            element = strings[element_index]
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", node, 24)
            assert attr_size == 20
            base = 16 + attr_start
            attrs = [bytes(node[base + n * 20:base + (n + 1) * 20]) for n in range(attr_count)]
            if element in ("service", "provider", "meta-data"):
                for attr in attrs:
                    name = struct.unpack_from("<I", attr, 4)[0]
                    value_type, value = attr[15], struct.unpack_from("<I", attr, 16)[0]
                    if strings[name] == "name" and value_type == 3 and strings[value] in MANIFEST_COMPONENTS:
                        removed_telemetry.add(strings[value])
                        skipping_depth = 1
                if skipping_depth:
                    continue
            if element == "uses-permission":
                for attr in attrs:
                    name, raw = struct.unpack_from("<II", attr, 4)
                    value_type, value = attr[15], struct.unpack_from("<I", attr, 16)[0]
                    if not allow_network and strings[name] == "name" and value_type == 3 and strings[value] == "android.permission.INTERNET":
                        removed_internet += 1
                        skipping_depth = 1
                if skipping_depth:
                    continue
            if element == "manifest":
                filtered = []
                for attr in attrs:
                    if struct.unpack_from("<I", attr, 4)[0] == shared_index:
                        namespace = struct.unpack_from("<I", attr, 0)[0]
                        removed_uid += 1
                    else:
                        filtered.append(attr)
                attrs = filtered
            if element == "application":
                assert removed_uid == 1
                attrs.append(struct.pack("<IIIHBBI", namespace, shared_index, 0xFFFFFFFF, 8, 0, 0x12, 0xFFFFFFFF))
                attrs.sort(key=lambda a: resources[struct.unpack_from("<I", a, 4)[0]] if struct.unpack_from("<I", a, 4)[0] < len(resources) else 0)
                added_debug += 1
            if len(attrs) != attr_count:
                # No id/class/style index is used by the affected manifest/application nodes.
                assert struct.unpack_from("<HHH", node, 30) == (0, 0, 0)
                node = node[:base] + b"".join(attrs) + node[base + attr_count * 20:]
                struct.pack_into("<H", node, 28, len(attrs))
                struct.pack_into("<I", node, 4, len(node))
        new_chunks.append(node)
    assert (removed_uid, removed_internet, added_debug) == (1, 0 if allow_network else 1, 1)
    assert removed_telemetry == MANIFEST_COMPONENTS, "Unexpected XREAL analytics manifest components"
    output = bytearray(blob[:8] + b"".join(new_chunks))
    struct.pack_into("<I", output, 4, len(output))
    return bytes(output)


def uleb(blob, offset):
    value = 0
    shift = 0
    while True:
        byte = blob[offset]
        offset += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, offset
        shift += 7


def patch_dex(original):
    blob = bytearray(original)
    string_count, string_offset = struct.unpack_from("<II", blob, 56)
    strings = []
    for index in range(string_count):
        offset = struct.unpack_from("<I", blob, string_offset + 4 * index)[0]
        _, offset = uleb(blob, offset)
        end = blob.index(0, offset)
        strings.append(blob[offset:end].decode("utf-8", errors="replace"))
    type_count, type_offset = struct.unpack_from("<II", blob, 64)
    types = [strings[struct.unpack_from("<I", blob, type_offset + 4 * i)[0]] for i in range(type_count)]
    method_count, method_offset = struct.unpack_from("<II", blob, 88)
    class_count, class_offset = struct.unpack_from("<II", blob, 96)
    patched = []
    for index in range(class_count):
        offset = class_offset + 32 * index
        class_index = struct.unpack_from("<I", blob, offset)[0]
        if types[class_index] != "Lai/nreal/nebula/NebulaApplication;":
            continue
        cursor = struct.unpack_from("<I", blob, offset + 24)[0]
        counts = []
        for _ in range(4):
            count, cursor = uleb(blob, cursor)
            counts.append(count)
        for _ in range(counts[0] + counts[1]):
            _, cursor = uleb(blob, cursor)
            _, cursor = uleb(blob, cursor)
        for count in counts[2:]:
            method_index = 0
            for _ in range(count):
                delta, cursor = uleb(blob, cursor)
                method_index += delta
                _, cursor = uleb(blob, cursor)
                code, cursor = uleb(blob, cursor)
                name_index = struct.unpack_from("<I", blob, method_offset + method_index * 8 + 4)[0]
                if strings[name_index] != "initFwCache":
                    continue
                assert code and struct.unpack_from("<H", blob, code + 6)[0] == 0
                length = struct.unpack_from("<I", blob, code + 12)[0]
                assert length > 0
                blob[code + 16:code + 16 + length * 2] = b"\x0e\x00" + b"\x00\x00" * (length - 1)
                patched.append("ai.nreal.nebula.NebulaApplication.initFwCache: return-void")
    assert len(patched) == 1
    blob[12:32] = hashlib.sha1(blob[32:]).digest()
    struct.pack_into("<I", blob, 8, zlib.adler32(blob[12:]) & 0xFFFFFFFF)
    return bytes(blob), patched


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=SOURCE)
    parser.add_argument("--allow-network", action="store_true", help="Retain the original Internet permission; required by the original network startup code")
    parser.add_argument("--bridge-client-dex", type=Path, help="Replace LaunchService with the certificate-protected ADB bridge client")
    parser.add_argument("--buffered-presentation", action="store_true", help="Build the offline tearing-fix candidate using buffered XR screen presentation; requires hardware validation")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/port-bootstrap", help="Directory for the rebuilt APK and audit evidence")
    args = parser.parse_args()
    assert sha256(args.source) == SOURCE_SHA, "Unexpected source APK; do not apply pinned patches"
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path.home() / "Library/Android/sdk"
    tools = sdk / "build-tools/36.0.0"
    java = subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip()
    env = dict(os.environ, JAVA_HOME=java)
    if args.bridge_client_dex:
        subprocess.run([str(Path(java) / "bin/java"), "--class-path",
                        str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
                        str(ROOT / "tools/PatchWirelessCastDex.java"), "verify-client",
                        str(args.bridge_client_dex), str(args.bridge_client_dex)], check=True, env=env)
    patched_methods = []
    telemetry_dex = {}
    native_targets = []
    app_limit_targets = []
    quick_access_targets = []
    presentation_targets = []
    with zipfile.ZipFile(args.source) as source, zipfile.ZipFile(output / "unsigned.apk", "w", allowZip64=True) as target:
        for info in source.infolist():
            if info.filename.upper().startswith("META-INF/") and info.filename.upper().endswith((".SF", ".RSA", ".DSA", ".EC", "MANIFEST.MF")):
                continue
            value = source.read(info)
            if info.filename == "AndroidManifest.xml":
                value = patch_manifest(value, args.allow_network)
                (output / "AndroidManifest.xml.bin").write_bytes(value)
            elif info.filename == "classes.dex":
                value, patched_methods = patch_dex(value)
                (output / "classes.dex").write_bytes(value)
                if args.bridge_client_dex:
                    subprocess.run([str(Path(java) / "bin/java"), "--class-path", str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
                        str(ROOT / "tools/PatchLaunchDex.java"), "client", str(output / "classes.dex"), str(output / "client-base.dex")], check=True, env=env)
                    value = (output / "client-base.dex").read_bytes()
            elif info.filename == "classes2.dex" and args.bridge_client_dex:
                (output / "classes2-original.dex").write_bytes(value)
                subprocess.run([str(Path(java) / "bin/java"), "--class-path", str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
                    str(ROOT / "tools/PatchLaunchDex.java"), "compat", str(output / "classes2-original.dex"), str(output / "classes2-compatible.dex")], check=True, env=env)
                value = (output / "classes2-compatible.dex").read_bytes()
            elif info.filename == LIBRARY:
                value, native_targets = patch_library(value)
                verify_library(value)
                value, app_limit_targets = patch_app_limit(value)
                verify_app_limit(value)
                value, quick_access_targets = patch_quick_access_library(value)
                restore_quick_access_library(value)
                value, icon_placement_targets = patch_icon_placement(value)
                restore_icon_placement(value)
                value, window_size_targets = patch_window_size(value)
                restore_window_size(value)
            elif info.filename == MENU_ASSET:
                value = patch_menu(value)
                verify_menu(value)
            elif info.filename == XR_LIBRARY and args.buffered_presentation:
                value, presentation_targets = patch_render_sync(value)
                verify_render_sync(value)
            if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", info.filename):
                before = output / (info.filename + ".before-telemetry")
                after = output / (info.filename + ".no-xreal")
                before.write_bytes(value)
                command = [str(Path(java) / "bin/java"), "--class-path",
                           str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
                           str(ROOT / "tools/PatchTelemetryDex.java")]
                result = subprocess.run([*command, "patch", str(before), str(after)],
                                        check=True, env=env, text=True, capture_output=True)
                print(result.stdout, end="")
                telemetry_dex[info.filename] = result.stdout.strip().splitlines()
                subprocess.run([*command, "verify", str(after), str(after)], check=True, env=env)
                value = after.read_bytes()
                if info.filename == "classes.dex" and args.bridge_client_dex:
                    value = patch_wireless_cast_dex(value, output, java, env)
            target.writestr(info, value)
        if args.bridge_client_dex:
            assert "classes4.dex" not in source.namelist()
            target.writestr("classes4.dex", args.bridge_client_dex.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
    assert patched_methods
    assert native_targets and len(telemetry_dex) == 3, "Incomplete XREAL telemetry removal"
    assert len(app_limit_targets) == 3, "Incomplete app-limit patch"
    assert len(quick_access_targets) == 2, "Incomplete quick-access fix"
    assert len(icon_placement_targets) == 2, "Incomplete icon-placement fix"
    assert len(window_size_targets) == 3, "Incomplete default window-size patch"
    assert len(presentation_targets) == (2 if args.buffered_presentation else 0), "Incomplete presentation patch"
    def run(*command):
        subprocess.run([str(value) for value in command], check=True, env=env)
    run(tools / "zipalign", "-f", "-P", "16", "4", output / "unsigned.apk", output / "aligned.apk")
    apk = output / "myglasses-startup-candidate.apk"
    run(tools / "apksigner", "sign", "--ks", ROOT / "artifacts/probe/local-debug.p12", "--ks-key-alias", "port-probe",
        "--ks-pass", "pass:android", "--out", apk, output / "aligned.apk")
    run(tools / "apksigner", "verify", "--verbose", apk)
    report = {"type": "startup-candidate", "is_working_port": False, "network_enabled": args.allow_network, "source_sha256": SOURCE_SHA,
              "apk_sha256": sha256(apk), "patches": ["removed android.uid.system shared UID", "enabled app debugging",
              "retained INTERNET permission" if args.allow_network else "removed INTERNET permission", *patched_methods,
              "removed XREAL analytics service/provider/metadata and Sensors Data uploader SDK",
              "replaced XREAL analytics Java/JNI/Binder entry points with inert compatibility methods",
              "disabled automatic sensor calibration JSON uploads to XREAL-managed cloud storage",
              "disabled XREAL Unity event, identifier and analytics pose collectors"], "limitations": ["Window/input bridge is not integrated",
              "Original privileged runtime calls remain", "Firmware compatibility is not established"]}
    report["xreal_telemetry"] = {"removed": True, "manifest_components_removed": sorted(MANIFEST_COMPONENTS),
                                "dex_audit": telemetry_dex, "unity_methods_disabled": len(native_targets),
                                "ordinary_networking_preserved": args.allow_network,
                                "third_party_crash_reporting_unchanged": True,
                                "automatic_sensor_bias_uploads_disabled": True,
                                "validation": "static APK checks; hardware/runtime network validation pending"}
    report["bridge_client_included"] = bool(args.bridge_client_dex)
    report["wireless_cast"] = {"checks_vendor_service_access": bool(args.bridge_client_dex),
                               "requires_rebuilt_port_client": True,
                               "other_receivers_preserved": True}
    report["max_simultaneous_apps"] = MAX_APPS
    report["app_limit_patches"] = app_limit_targets
    report["quick_access"] = {"game_mode_removed": True,
                              "settings_intents_support_handlers_without_launcher_icons": True,
                              "connected_ssid_via_signed_helper": bool(args.bridge_client_dex),
                              "navigate_existing_radio_settings_window": bool(args.bridge_client_dex),
                              "native_patches": quick_access_targets}
    report["icon_placement"] = {"floating_icon_initialized": True,
                                "native_patches": icon_placement_targets,
                                "hardware_validation": "pending"}
    report["default_window_size"] = {"size": DEFAULT_SIZE,
                                   "fov_percent": DEFAULT_FOV_PERCENT,
                                   "saved_user_sizes_preserved": True,
                                   "native_patches": window_size_targets,
                                   "hardware_validation": "pending"}
    report["recent_apps"] = {"launcher_only_history": bool(args.bridge_client_dex),
                             "max_entries": 32,
                             "helper_owned_window_previews": bool(args.bridge_client_dex),
                             "requires_updated_helper": True,
                             "hardware_validation": "pending"}
    report["buffered_presentation"] = {
        "enabled": args.buffered_presentation,
        "requested_nr_screen_buffer_mode": 2 if args.buffered_presentation else None,
        "native_patches": presentation_targets,
        "hardware_validation": "pending; no device changes performed by the builder",
    }
    if args.buffered_presentation:
        report["type"] = "render-sync-candidate"
        report["patches"].append("selected buffered XR presentation at startup and prevented runtime single-buffer requests")
        report["limitations"].append("Buffered presentation may add latency; visual tearing fix is not hardware-validated")
    report["patches"].append("removed Game Mode from quick access and fixed settings handlers without launcher icons")
    report["patches"].append("initialized floating icon copies so pointer clicks can place moved icons")
    report["patches"].append("initialized new app windows at the exact M preset; retained saved user sizes")
    report["patches"].append("raised native-app and total-window limits to ten")
    if args.bridge_client_dex:
        report["patches"].append("replaced LaunchService with certificate-protected ADB helper client")
        report["patches"].append("fed the recent-apps panel from persistent successful spatial launches instead of privileged Android task history")
        report["patches"].append("captured recent-app thumbnails only from helper-owned spatial windows and retained them after close")
        report["patches"].append("blocked five OTA/boot-mode Java entry points and added an in-app Binder connection check")
        report["patches"].append("kept Beam-only focus and USB-state flags in companion-local state instead of global/system settings")
        report["patches"].append("kept visible glasses rendering active across transient activity pauses; original onStop still suspends Unity")
        report["patches"].append("made optional Bluetooth input discovery tolerate denied Nearby Devices permission")
        report["limitations"].remove("Window/input bridge is not integrated")
        report["limitations"].append("Window/input bridge requires the separately started ADB LaunchServer; current rendering candidate requires hardware validation")
    (output / "build.json").write_text(json.dumps(report, indent=2) + "\n")
    print(apk)


if __name__ == "__main__":
    main()
