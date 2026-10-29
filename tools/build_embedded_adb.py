#!/usr/bin/env python3
"""Build one installable APK with on-phone ADB pairing and the bundled launch backend."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

from app_version import load_version, manifest_version, patch_manifest_version, write_java_version
from branding import APP_NAME, app_name_values, patch_resources
from permission_manifest import patch_menu_manifest
from embedded_adb_manifest import patch_manifest, patch_release_manifest, patch_usb_attach_manifest, AUTHORITY
from display_startup import patch_display_startup
from presentation_only import patch_presentation
from adaptive_manifest import patch_adaptive_manifest
from app_color import APP_SHADER, LIBRARY, OES_SHADERS, automatic_app_shader, direct_oes_shader, verify_app_shader, verify_direct_oes_shaders, verify_native_shader_loading, patch_color_dex, patch_color_native
from icon_resolution import patch_icon_resolution
from usb_permission import patch_usb_permission, patch_controller_usb_permission
from connection_startup import patch_connection_startup, patch_connection_theme_manifest
from disconnect_audio import patch_disconnect_audio
from controller_mode import patch_controller_mode
from home_button import patch_home_button
from input_compat import patch_input_compat
from ping_cancellation import patch_ping_cancellation
from glasses_startup import patch_glasses_startup
from ethernet_ping import patch_ethernet_ping
from notifications import patch_notifications
from rog_r1 import IDENTITY_SOURCE, patch_rog_r1
from settings_artwork import custom_settings_artwork
from display_mode_compat import LIBRARY as MODE_LIBRARY, patch_display_mode, verify_display_mode
from egl_colorspace_compat import patch_egl_colorspace, verify_egl_colorspace
from overlay_routing import patch_overlay_routing, verify_overlay_routing
from app_surface_routing import patch_surface_routing, verify_surface_routing
from recording_duration import patch_recording_duration, verify_recording_duration
from skip_ota import patch_skip_ota
from startup_tutorial import patch_startup_tutorial
from experimental_stereo import patch_stereo_dex, patch_stereo_native
from updater_manifest import patch_updater_manifest
from debug_manifest import patch_debug_manifest
from storage_manifest import patch_storage_manifest
from package_identity import PACKAGE, METADATA, BINARY_ENCODINGS, patch_binary_identity, repackage_dex
from hardware_buffer_texture import ENTRY as TEXTURE_LIBRARY, build_library
from phone_pointer import ENTRY as POINTER_LIBRARY, build_library as build_pointer_library
from renderer_diagnostics import ENTRY as RENDERER_LIBRARY, build_library as build_renderer_library

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "artifacts/unbound-launcher-help/the-unbound-launcher.apk"
BASE_SHA = "6844c00be41eb6b7a944a2a68572c310e62ea9af264a99257fa9e40cb5dddc1f"
DEPS = {
    "annotation-jvm-1.9.1.jar": "1e343917ebf27ba96fe4dc52b1cad7fd32b738fbc6355bb6cd5b3b305d7212d0",
    "bcprov-jdk15to18-1.81.jar": "0ef9c4d9536719aaa1c92a7c694d87981fa9d4d64dd9479cc322a08aebbc651e",
    "conscrypt-android-2.5.3.aar": "551ae4e301c571760d1791e647db6ed1dcb10d34dcae7aa12b67f220f2ce98d1",
    "spake2-android-2.2.1.aar": "8798fb6c04b5d53a6307ed9481e9afe563227abd4e8b9e917c8514fa850071bb",
}
VERSION = load_version()
SOURCE_SUFFIXES = {".java", ".c", ".h", ".cpp", ".hpp", ".gradle", ".xml", ".md", ".txt", ".json", ".py", ".s", ".html", ".cmake"}
SOURCE_NAMES = {"LICENSE", "NOTICE", "COPYING", "Makefile", "CMakeLists.txt"}
SOURCE_EXCLUDED_PARTS = {"__macosx", "__pycache__", "node_modules", "build", "dist", "cache", "caches", "keys", "secrets", "credentials"}
SOURCE_KEY_SUFFIXES = {".key", ".pem", ".p12", ".pfx", ".jks", ".keystore", ".der", ".crt", ".cer", ".pyc", ".pyo"}
SOURCE_SECRET = re.compile(rb"-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}")


def pairing_source_file(path, root=ROOT):
    """Allow only source/licenses, excluding machine metadata and signing material."""
    relative = path.relative_to(root)
    if any(part.startswith(".") or part.lower() in SOURCE_EXCLUDED_PARTS for part in relative.parts):
        return False
    if relative.parts[:3] == ("embedded-adb", "vendor", "deps"):
        return False
    if any((root / Path(*relative.parts[:count])).is_symlink() for count in range(1, len(relative.parts) + 1)):
        return False
    if not path.is_file() or any(suffix.lower() in SOURCE_KEY_SUFFIXES for suffix in path.suffixes):
        return False
    if path.stem.lower() in {"id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "credentials", "passwords", "secrets"}:
        return False
    return path.suffix.lower() in SOURCE_SUFFIXES or path.name in SOURCE_NAMES or "LICENSES" in relative.parts


def write_pairing_sources(destination, root=ROOT):
    """Package corresponding sources without filesystem identities or private files."""
    paths = []
    for directory in ["embedded-adb", "settings-ui/src", "bridge/src", "port-client/src", "compat/src"]:
        paths.extend(path for path in (root / directory).rglob("*") if pairing_source_file(path, root))
    paths.extend(root / name for name in ["version.json", "LICENSE"])
    for name in ["build_embedded_adb.py", "app_version.py", "updater_manifest.py", "debug_manifest.py", "embedded_adb_manifest.py", "adaptive_manifest.py", "display_startup.py", "PatchDisplayStartupDex.java", "connection_startup.py", "PatchConnectionStartupDex.java", "disconnect_audio.py", "PatchDisconnectAudioDex.java", "skip_ota.py", "PatchSkipOtaDex.java", "startup_tutorial.py", "experimental_stereo.py", "experimental_stereo.S", "experimental_stereo_native.json", "PatchExperimentalStereoDex.java", "app_color.py", "PatchAppColorDex.java", "app_color_select.S", "app_color_oes.S", "app_color_native.json", "xreal_telemetry.py", "icon_resolution.py", "PatchIconResolutionDex.java", "usb_permission.py", "PatchUsbPermissionDex.java", "PatchControllerUsbDex.java"]:
        paths.append(root / "tools" / name)
    paths.extend(root / "tools" / name for name in ("package_identity.py", "RepackageDex.java"))
    paths.extend(root / "tools" / name for name in ("startup_theme.py", "branding.py", "permission_manifest.py"))
    paths.extend(root / "tools" / name for name in ("ping_cancellation.py", "PatchPingCancellationDex.java"))
    paths.extend(root / "tools" / name for name in ("controller_mode.py", "PatchControllerModeDex.java"))
    paths.extend(root / "tools" / name for name in ("home_button.py", "PatchHomeButtonDex.java"))
    paths.extend(root / "tools" / name for name in ("input_compat.py", "PatchInputCompatDex.java"))
    paths.extend(root / "tools" / name for name in ("glasses_startup.py", "PatchGlassesStartupDex.java"))
    paths.extend(root / "tools" / name for name in ("ethernet_ping.py", "PatchEthernetPingDex.java"))
    paths.extend(root / "tools" / name for name in ("notifications.py", "PatchNotificationsDex.java"))
    paths.extend(root / "tools" / name for name in ("rog_r1.py", "PatchRogR1Dex.java"))
    paths.append(root / "tools/settings_artwork.py")
    paths.append(root / "tools/display_mode_compat.py")
    paths.append(root / "tools/egl_colorspace_compat.py")
    paths.append(root / "tools/overlay_routing.py")
    paths.extend(root / "tools" / name for name in ("app_surface_routing.py", "app_surface_routing.S", "app_surface_routing_native.json"))
    paths.append(root / "tools/unity_shader_loader.py")
    paths.extend(root / "tools" / name for name in ("presentation_only.py", "PatchPresentationDex.java"))
    paths.append(root / "tools/hardware_buffer_texture.py")
    paths.append(root / "native/hardware-buffer-texture/library.c")
    paths.extend(root / name for name in ("tools/phone_pointer.py", "native/phone-pointer/library.c", "native/phone-pointer/pointer_state.h"))
    paths.extend(root / name for name in ("tools/renderer_diagnostics.py", "native/renderer-diagnostics/library.c"))
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(set(paths)):
            if not pairing_source_file(path, root):
                raise ValueError("An explicit source archive input is not permitted")
            value = path.read_bytes()
            if SOURCE_SECRET.search(value):
                raise ValueError("Private key or credential data found in source archive input")
            info = zipfile.ZipInfo(path.relative_to(root).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, value, compress_type=zipfile.ZIP_DEFLATED)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / ("artifacts/unbound-launcher-" + VERSION["version_name"].replace(" ", "-")))
    parser.add_argument("--test-only", action="store_true", help="Build a small isolated device-test APK instead of the launcher")
    args = parser.parse_args()
    authority = "dev.beampro.adbtest.bridge" if args.test_only else AUTHORITY
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    build = output / "build"; build.mkdir(exist_ok=True)
    sdk = Path.home() / "Library/Android/sdk"
    tools = sdk / "build-tools/36.0.0"; platform = sdk / "platforms/android-34/android.jar"
    java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))
    vendor = ROOT / "embedded-adb/vendor"
    for name, expected in DEPS.items():
        if sha256(vendor / "deps" / name) != expected:
            raise ValueError(f"Dependency checksum mismatch: {name}")
    if not args.test_only and sha256(BASE) != BASE_SHA:
        raise ValueError("Unexpected launcher baseline")

    def run(*command):
        subprocess.run([str(arg) for arg in command], env=env, check=True)

    def compile_java(sources, name, libraries=()):
        classes = build / (name + "-classes")
        if classes.exists(): shutil.rmtree(classes)
        classes.mkdir()
        run(java / "bin/javac", "--release", "8", "-classpath", os.pathsep.join(map(str, [platform, *libraries])), "-d", classes, *sources)
        jar = build / (name + ".jar"); run(java / "bin/jar", "cf", jar, "-C", classes, ".")
        return jar

    def dex(jars, name):
        dest = build / name
        if dest.exists(): shutil.rmtree(dest)
        dest.mkdir()
        run(tools / "d8", "--min-api", "29", "--lib", platform, "--output", dest, *jars)
        return sorted(dest.glob("*.dex"))

    # Keep the default developer helper and companion client unchanged.
    server_sources = build / "server-src"; server_sources.mkdir(exist_ok=True)
    sources = [IDENTITY_SOURCE]
    for source in sorted((ROOT / "bridge/src").rglob("*.java")):
        text = source.read_text()
        if source.name == "LaunchServer.java":
            if not args.test_only:
                marker="private static final boolean ENABLE_DISPLAY_MANAGEMENT = false;"
                if text.count(marker)!=1: raise ValueError("Managed display startup marker changed")
                text=text.replace(marker,"private static final boolean ENABLE_DISPLAY_MANAGEMENT = true;")
            text = text.replace('getApplicationInfo("dev.beampro.portprobe", 0)', 'getApplicationInfo("' + PACKAGE + '", 0)')
            text = text.replace("dev.beampro.portprobe.bridge", authority)
            if not args.test_only:
                text = text.replace('"/data/local/tmp/beampro-recent-apps.bin"',
                                    '"/data/local/tmp/' + PACKAGE + '/recent-apps.bin"')
                text = text.replace('new java.io.File("/data/local/tmp")',
                                    'new java.io.File("/data/local/tmp/' + PACKAGE + '")')
            if args.test_only:
                text = text.replace('getApplicationInfo("' + PACKAGE + '", 0)', 'getApplicationInfo("dev.beampro.adbtest", 0)')
            text = text.replace('externalProvider = holder.getClass().getField("provider").get(holder);',
                                'externalProvider = holder.getClass().getField("provider").get(holder);\n'
                                '        ((android.os.IInterface) externalProvider).asBinder().linkToDeath(() -> System.exit(0), 0);')
        target = server_sources / source.name; target.write_text(text); sources.append(target)
    sources.append(ROOT / "settings-ui/src/dev/beampro/settings/NetworkDiagnostics.java")
    sources.append(ROOT / "settings-ui/src/dev/beampro/settings/DisplayDiagnostics.java")
    sources.append(ROOT / "settings-ui/src/dev/beampro/settings/ControlTrace.java")
    server_jar = compile_java(sources, "server")
    server_dex = dex([server_jar], "server-dex")
    with zipfile.ZipFile(ROOT / "artifacts/beam-pro-system/myglasses-dex.zip") as archive:
        (build / "original.dex").write_bytes(archive.read("classes.dex"))
    run(java / "bin/java", "--class-path", ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar",
        ROOT / "tools/PatchLaunchDex.java", "server", build / "original.dex", build / "server-original.dex")
    helper = build / "unbound-shell.jar"
    with zipfile.ZipFile(helper, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, path in (("classes.dex", server_dex[0]), ("classes2.dex", build / "server-original.dex")):
            value = path.read_bytes()
            if not args.test_only:
                value = repackage_dex(value, build / "identity-helper" / name, java, env)
            archive.writestr(name, value)
    helper_sha = sha256(helper)
    generated = build / "generated/dev/beampro/adb"; generated.mkdir(parents=True, exist_ok=True)
    payload = generated / "HelperPayload.java"
    payload.write_text('package dev.beampro.adb;\nfinal class HelperPayload { static final String SHA256 = "' + helper_sha + '"; }\n')
    with zipfile.ZipFile(vendor / "deps/conscrypt-android-2.5.3.aar") as archive:
        (build / "conscrypt.jar").write_bytes(archive.read("classes.jar"))
        native = {"lib/" + name[4:]: archive.read(name) for name in archive.namelist() if name.startswith("jni/") and name.endswith(".so")}
    with zipfile.ZipFile(vendor / "deps/spake2-android-2.2.1.aar") as archive:
        native.update({"lib/" + name[4:]: archive.read(name) for name in archive.namelist() if name.startswith("jni/") and name.endswith(".so")})
    libraries = [vendor / "deps/annotation-jvm-1.9.1.jar", vendor / "deps/bcprov-jdk15to18-1.81.jar", build / "conscrypt.jar"]
    common = sorted((ROOT / "settings-ui/src").rglob("*.java"))
    # Keep the package and About screen on the shared release version.
    common.append(write_java_version(build / "generated", VERSION, {"version_name": "2.2.2", "version_code": 2873}))
    sources = common + sorted((ROOT / "embedded-adb/src").rglob("*.java")) + [payload]
    if args.test_only:
        source = ROOT / "embedded-adb/src/dev/beampro/adb/AdbBootstrap.java"
        test_source = generated / "AdbBootstrap.java"
        test_source.write_text(source.read_text().replace(AUTHORITY, authority))
        sources = [test_source if p == source else p for p in sources]
    sources += sorted((vendor / "libadb/java").rglob("*.java")) + sorted((vendor / "spake2/java").rglob("*.java"))
    if args.test_only: sources += sorted((ROOT / "embedded-adb/test/src").rglob("*.java"))
    app_jar = compile_java(sources, "embedded", libraries)
    app_dex = dex([app_jar, libraries[1], libraries[2]], "embedded-dex")
    client_sources = build / "client-src"; client_sources.mkdir(exist_ok=True)
    sources = [IDENTITY_SOURCE]
    for source in sorted((ROOT / "port-client/src").rglob("*.java")):
        target = client_sources / source.name
        target.write_text(source.read_text().replace("dev.beampro.portprobe.bridge", authority)); sources.append(target)
    client_dex = dex([compile_java(sources, "client")], "client-dex")[0]
    # Ship the library source and modifications so the LGPL component can be replaced/relinked.
    source_zip = build / "pairing-sources.zip"
    write_pairing_sources(source_zip)
    additions = {"assets/unbound-shell.jar": helper.read_bytes(), "assets/unbound-adb/pairing-sources.zip": source_zip.read_bytes(),
                 "assets/unbound-adb/NOTICE.txt": (ROOT / "embedded-adb/NOTICE.txt").read_bytes(),
                 "assets/unbound-adb/conscrypt-LICENSE.txt": (ROOT / "embedded-adb/conscrypt-LICENSE.txt").read_bytes()}
    additions.update(native if args.test_only else {k: v for k, v in native.items() if "/arm64-v8a/" in k})
    additions.update({name: path.read_bytes() for name, path in custom_settings_artwork(build / "settings-artwork").items()})
    if not args.test_only:
        additions[POINTER_LIBRARY] = build_pointer_library(build / "libUnboundPhonePointer.so")
        additions[RENDERER_LIBRARY] = build_renderer_library(build / "libUnboundRendererDiagnostics.so")
    if args.test_only:  # The launcher APK already carries this settings artwork.
        additions["assets/settings/brand-mark.png"] = (ROOT / "branding/symbol.png").read_bytes()
    unsigned = build / "unsigned.apk"
    if args.test_only:
        run(tools / "aapt2", "link", "-I", platform, "--manifest", ROOT / "embedded-adb/test/AndroidManifest.xml", "-o", build / "test-base.apk")
        baseline = build / "test-base.apk"
    else: baseline = BASE
    startup_dex = None
    presentation_dex = None
    icon_dex = None
    if not args.test_only:
        with zipfile.ZipFile(BASE) as source:
            startup_dex = patch_display_startup(source.read("classes.dex"), build / "display-startup", java, env)
            startup_dex = patch_presentation(startup_dex, build / "presentation-only", java, env)
            startup_dex = patch_controller_usb_permission(startup_dex, build / "controller-usb", java, env)
            startup_dex = patch_connection_startup(startup_dex, build / "connection-startup", java, env)
            startup_dex = patch_skip_ota(startup_dex, build / "skip-ota", java, env)
            startup_dex = patch_stereo_dex(startup_dex, build / "experimental-stereo", java, env)
            startup_dex = patch_home_button(startup_dex, build / "home-button", java, env)
            startup_dex = patch_input_compat(startup_dex, build / "keyboard-input", java, env)
            presentation_dex = patch_display_startup(source.read("classes3.dex"), build / "stereo-presentation", java, env)
            icon_dex = patch_icon_resolution(source.read("classes2.dex"), build / "icon-resolution", java, env)
            icon_dex = patch_color_dex(icon_dex, build / "app-colors", java, env)
            icon_dex = patch_usb_permission(icon_dex, build / "usb-permission", java, env)
            icon_dex = patch_disconnect_audio(icon_dex, build / "disconnect-audio", java, env)
            icon_dex = patch_controller_mode(icon_dex, build / "controller-mode", java, env)
            icon_dex = patch_ping_cancellation(icon_dex, build / "ping-cancellation", java, env)
            icon_dex = patch_glasses_startup(icon_dex, build / "glasses-startup", java, env)
            icon_dex = patch_ethernet_ping(icon_dex, build / "ethernet-ping", java, env)
            icon_dex = patch_notifications(icon_dex, build / "notifications", java, env)
            icon_dex = patch_rog_r1(icon_dex, build / "rog-r1", java, env)
            icon_dex = patch_input_compat(icon_dex, build / "pointer-input", java, env)
    texture_library = None if args.test_only else build_library(build / "libHardwareBufferTexture.so")
    with zipfile.ZipFile(baseline) as source, zipfile.ZipFile(unsigned, "w", allowZip64=True) as target:
        for info in source.infolist():
            if info.filename.upper().startswith("META-INF/"): continue
            if info.filename == "classes5.dex": continue
            value = source.read(info)
            if info.filename == "classes.dex" and startup_dex is not None: value = startup_dex
            if info.filename == "classes3.dex" and presentation_dex is not None: value = presentation_dex
            if info.filename == "classes2.dex" and icon_dex is not None: value = icon_dex
            if info.filename == "classes4.dex": value = client_dex.read_bytes()
            if info.filename == APP_SHADER and not args.test_only: value = automatic_app_shader(source)
            if info.filename in OES_SHADERS and not args.test_only: value = direct_oes_shader(value)
            if info.filename == LIBRARY and not args.test_only:
                value = patch_surface_routing(patch_overlay_routing(patch_stereo_native(patch_startup_tutorial(patch_color_native(value)))))
                value = patch_recording_duration(value)
            if info.filename == MODE_LIBRARY and not args.test_only:
                value = patch_egl_colorspace(patch_display_mode(value))
            if info.filename == TEXTURE_LIBRARY and texture_library is not None:
                value = texture_library
            if info.filename == "AndroidManifest.xml" and not args.test_only:
                value = patch_usb_attach_manifest(patch_release_manifest(patch_adaptive_manifest(patch_manifest(patch_manifest_version(value, VERSION)))))
                value = patch_debug_manifest(patch_updater_manifest(value))
                value = patch_menu_manifest(patch_connection_theme_manifest(value))
                value = patch_storage_manifest(value)
            if info.filename == "resources.arsc" and not args.test_only:
                # This builder reuses a pinned APK; apply current branding here too.
                value = patch_resources(value)
            if not args.test_only:
                if info.filename in BINARY_ENCODINGS:
                    value = patch_binary_identity(info.filename, value)
                elif info.filename.startswith("classes") and info.filename.endswith(".dex"):
                    value = repackage_dex(value, build / "identity" / info.filename, java, env)
            # ZipFile.writestr mutates ZipInfo.header_offset. Preserve source
            # entries so a later shader substitution can still read an earlier entry.
            target.writestr(copy.copy(info), value)
        for index, path in enumerate(app_dex, 1 if args.test_only else 5):
            target.write(path, "classes.dex" if index == 1 else f"classes{index}.dex", compress_type=zipfile.ZIP_DEFLATED)
        for name, value in additions.items(): target.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    apk = output / ("unbound-adb-device-test.apk" if args.test_only else "the-unbound-launcher.apk")
    aligned = build / "aligned.apk"
    run(tools / "zipalign", "-f", "-P", "16", "4", unsigned, aligned)
    run(tools / "apksigner", "sign", "--ks", ROOT / "artifacts/probe/local-debug.p12", "--ks-key-alias", "port-probe",
        "--ks-pass", "pass:android", "--out", apk, aligned)
    run(tools / "apksigner", "verify", "--verbose", apk); run(tools / "zipalign", "-c", "-P", "16", "4", apk)
    changed = []; added = []
    if not args.test_only:
        with zipfile.ZipFile(BASE) as source, zipfile.ZipFile(apk) as target:
            for name in source.namelist():
                if name.upper().startswith("META-INF/"): continue
                if source.read(name) != target.read(name): changed.append(name)
            added = sorted(set(target.namelist()) - set(source.namelist()))
            expected={"classes.dex", "classes2.dex", "classes3.dex", "classes4.dex", "classes5.dex", "AndroidManifest.xml", "resources.arsc", METADATA, APP_SHADER, *OES_SHADERS, LIBRARY, MODE_LIBRARY, TEXTURE_LIBRARY}
            if set(changed) != expected: raise ValueError(f"Unexpected changes: {changed}")
            color_report=verify_app_shader(target,source)
            color_report.update(verify_direct_oes_shaders(target,source))
            color_report.update(verify_native_shader_loading(target))
            if not color_report['automatic_decode_selection'] or not color_report['direct_oes_software_uniform']:
                raise ValueError('App color selection was not packaged')
            if manifest_version(target.read("AndroidManifest.xml")) != VERSION: raise ValueError("Version mismatch")
            if any(label != APP_NAME for label in app_name_values(target.read("resources.arsc"))):
                raise ValueError("App label mismatch")
    report = {"apk": str(apk), "sha256": sha256(apk), "version": VERSION, "single_apk": True,
              "requires_companion_apk": False, "helper_sha256": helper_sha, "loopback_only": True,
              "dependency_hashes": DEPS, "changed_payloads": changed, "added_payloads": added,
              "signature_and_alignment_verified": True, "physical_device_validation": "pending"}
    if not args.test_only:report['app_colors']=color_report
    if not args.test_only:report['debuggable']=False
    if not args.test_only:
        report['notifications'] = {'system_only_intent_inspection_removed': True,
                                   'tap_action': 'renderer native-app fallback using PendingIntent creator',
                                   'callbacks_and_access_unchanged': True}
    if not args.test_only:
        report['controller_mode'] = {'beam_pointer_flag_in_private_state': True,
                                     'write_secure_settings_required': False,
                                     'service_and_other_dex_behavior_preserved': True}
    if not args.test_only:
        report['package_identity'] = {'package': PACKAGE, 'original_class_names_preserved': True,
                                      'dex_reverse_semantics_verified': True,
                                      'install': 'separate app; repeat permissions and pairing'}
    if not args.test_only:
        report['app_detection'] = {'settings_section': 'Experimental', 'control': 'Detect as game',
                                   'default': 'normal app', 'system_category_readback': True,
                                   'installer_restored': True, 'installer_crash_recovery': True,
                                   'scope': 'launcher package only', 'manufacturer_game_tools': 'device dependent'}
    report['onboarding'] = {'steps': ['permissions', 'connection', 'terms'],
                            'inline_terms': True, 'original_acceptance_record': True,
                            'automatic_entry_waits_for_agreement': True}
    if not args.test_only:
        report['app_updater'] = {'repository': 'MarkPronkin/the-unbound-launcher',
                                 'about_controls': ['App updates', 'Check on startup', 'Check for updates'],
                                 'prerelease_aware': True, 'verified_downloads': True,
                                 'android_install_confirmation': True}
    if not args.test_only:
        report['startup_tutorial']={'automatic_first_use_disabled':True,
                                    'manual_tutorial_preserved':True,
                                    'saved_completion_data_unchanged':True,
                                    'hardware_validation':'pending'}
    if not args.test_only:
        report['skip_ota']={'always_enabled':True,'original_ota_update_probe_removed':True,
                            'model_sku_preserved':True,'forced_update_ui_bypassed':True,
                            'low_level_flash_and_boot_guards_retained':True,'hardware_validation':'pending'}
        report['firmware_gate']={'before_sdk_initialization':True,'read_only_usb':True,
                                'recommendations_verified_on':'2026-10-29','exact_per_model_stable_release':True,
                                'older_newer_beta_or_unreadable_pauses':True,'override':'current connection only',
                                'screen':'full-screen firmware warning with updater, Exit and small explicit override',
                                'hardware_validation':'pending'}
    if not args.test_only:
        report['xreal_usb_access']={'deferred_until_helper_ready':True,'launcher_only':True,
                                   'usb_handler_confirmation_removed':True,'all_five_sdk_requests_hooked':True,
                                   'usb_parcels_decoded_outside_system_context':True,
                                   'vendor_id':13080,'live_device_revalidated':True,
                                   'persistent_grant':False,'android_permission_readback':True,
                                   'original_sdk_callback_preserved':True,'hardware_validation':'pending'}
    report['recent_previews']={'capture_api_from_binder_signature':True,'headless_direct_services':True,
                              'fresh_owned_display_checks':True,'max_dimension':400,
                              'secure_and_protected_content_excluded':True,'hardware_validation':'pending'}
    report['connection_animation'] = {'phone_full_screen': True, 'style': 'Dot-matrix USB, glasses and trackpad pointer',
                                      'live_connection_stages': True, 'sdk_initialization_unchanged': True,
                                      'success_handoff_ms': 650, 'reduced_motion': True, 'retry_and_cancel': True,
                                      'continuous_controller_cover': True, 'permission_free_cover_fallback': True,
                                      'waits_for_controller_layout': True, 'waits_for_glasses_surface': True,
                                      'waits_for_controller_and_glasses_frame_commit': True,
                                      'optional_pointer_visuals_do_not_gate_startup': True,
                                      'hardware_validation': 'pending'}
    report['network_diagnostics'] = {'fixed_read_only_probes': True, 'app_and_shell_identities': True,
                                     'failure_and_export_snapshots': True, 'retained_snapshots': 3,
                                     'max_snapshot_bytes': 64000, 'helper_optional_for_export': True}
    report['display_diagnostics'] = {'services': ['display', 'SurfaceFlinger'], 'launcher_uid_only': True,
                                     'pipe_transport': True, 'max_dump_bytes': 1010000,
                                     'command_timeout_ms': 10000, 'helper_optional_for_export': True,
                                     'display_link_file': 'display-link.txt', 'link_snapshot_bytes': 200000,
                                     'retained_link_snapshots': 3, 'link_capture_client_timeout_ms': 30000,
                                     'link_sources': ['USB/DisplayPort Alt Mode', 'display windows', 'fixed-tag system logs',
                                                      'filtered kernel logs/dmesg', 'DRM/Type-C sysfs and EDID',
                                                      'temporary display-policy journal'],
                                     'baseline_failure_and_export_snapshots': True,
                                     'missing_or_denied_sources_retained': True}
    report['glasses_startup_diagnostics'] = {'opt_in_collection': True, 'passive_sdk_events': True,
                                            'attempt_ids_and_java_state': True,
                                            'first_failure_retained_across_retry_and_restart': True,
                                            'startup_file': 'glasses-startup.txt',
                                            'control_file': 'glasses-control.txt',
                                            'control_capture_bytes': 32000, 'control_snapshots': 3,
                                            'independent_control_capture_slot': True,
                                            'fixed_glasses_peers_only': True,
                                            'native_getters_not_invoked': True,
                                            'zip_limit_bytes': 15000000,
                                            'first_control_failure_and_export_snapshots': True}
    report['renderer_geometry_diagnostics'] = {'file': 'renderer-geometry.txt', 'direct_unity_readback': True,
                                              'logcat_independent': True, 'collection_opt_in': True,
                                              'sample_interval_ms': 2000, 'native_renderer_binary_unchanged': True,
                                              'screenshots_and_head_poses': False}
    report['ping_cancellation'] = {'complete_once_on_usb_detach': True, 'sdk_worker_delivery': True,
                                    'late_callbacks_rejected': True, 'connectivity_check_preserved': True,
                                    'hardware_validation': 'pending'}
    report['ethernet_readiness'] = {'fixed_shell_ping': True, 'launcher_uid_only': True,
                                    'original_sdk_polling_and_timeout_preserved': True,
                                    'cancel_and_usb_identity_rechecked': True,
                                    'helper_timeout_and_no_backlog': True,
                                    'enable_when_explicitly_disabled': True,
                                    'live_usb_revalidated_before_enable': True,
                                    'enable_once_per_attachment': True,
                                    'legacy_requests_read_only': True,
                                    'ip_configuration_unchanged': True,
                                    'real_ping_required_after_enable': True,
                                    'hardware_validation': 'pending'}
    report['glasses_startup'] = {'type_11_unsupported_query_fallback': not args.test_only,
                                 'native_init_and_active_xreal_display_required': True,
                                 'only_native_error_103': True, 'ethernet_startup_unchanged': True,
                                 'hardware_validation': 'pending'}
    if not args.test_only:
        with zipfile.ZipFile(apk) as built:
            mode_library = built.read(MODE_LIBRARY)
            report['egl_colorspace_compatibility'] = verify_egl_colorspace(mode_library)
            mode_library = patch_egl_colorspace(mode_library, restore=True)
            # The full-library hash in this audit also rejects the retired
            # compositor gamma patches, including the sampler2D double decode.
            report['rog_r1_compatibility'] = {'experimental': True, 'usb_ids': ['0b05:1d9c', '0b05:1d9d'],
                'edid': {'manufacturer': 'AUS', 'product': '43593'}, 'sdk_profile': 6, 'hardware_verified': False}
            report['display_mode_compatibility'] = verify_display_mode(mode_library)
            report['overlay_color'] = {'compositor_color_patches_enabled': False,
                                       'stock_compositor_color_pipeline_verified': True}
            routed = patch_binary_identity(LIBRARY, built.read(LIBRARY), restore=True)
            report['recording_duration'] = verify_recording_duration(routed)
            routed = patch_recording_duration(routed, restore=True)
            report['app_surface_routing'] = verify_surface_routing(routed)
            report['overlay_routing'] = verify_overlay_routing(patch_surface_routing(routed, restore=True))
    report['headset_icons']={'max_texture_size':432,'requested_source_density':640,
                             'adaptive_layer_contract_preserved':True,'native_pixel_transport_unchanged':True,
                             'scope':'Installed-app headset icons; built-in artwork and phone editor unchanged'}
    report["adaptive_phone_layout"] = {"two_panes_from_dp": 600, "maximum_width_dp": 1280,
                                       "settings": "glasses and launch beside settings",
                                       "app_menu": "editor beside live headset preview",
                                       "large_text": "raises the two-pane breakpoint",
                                       "phone_orientation_unrestricted": not args.test_only,
                                       "draft_search_preview_page_and_undo_preserved": True,
                                       "landscape": {"aspect_ratios": ["16:9", "16:10"],
                                                     "pinned_launch_action": True,
                                                     "toolbar_search_above_editor_and_preview": True,
                                                     "compact_preview_below_height_dp": 520,
                                                     "setup": "overview and actions beside scrolling details"}}
    report["experimental_stereo"] = {"enabled_by_default": False, "only_original_mono_modes_overridden": True,
                                      "output": "3840x1080 SBS at 60 Hz", "java_and_unity_selectors_filtered": not args.test_only,
                                      "apply": "Reconnect glasses", "hardware_validation": "pending"}
    report["samsung_compatibility"] = {"auto_entry_phone_display": 0, "auto_entry_fullscreen": True,
                                       "dex_keep_process_alive_metadata": not args.test_only,
                                       "manual_display_choice_respected_after_initial_configuration": True,
                                       "choice_scope": "same physical display for the helper lifetime",
                                       "optional_mono_60_hz": False, "retired_mono_60_choices_ignored": True,
                                       "physical_validation": "pending"}
    report["display_startup"] = {"window_scoped_stereo_mode_request": True,
                                 "actual_sdk_presentation_window_hooked": True,
                                 "native_window_discovery_via_window_inspector": True,
                                 "opaque_ar_backdrop_controls_immersive_bars_before_android_17": True,
                                 "android_17_presentation_owns_bar_focus": True,
                                 "android_17_renderer_excluded_from_touch_and_ime_target": True,
                                 "consent_wait_seconds": 45,
                                 "temporary_resolution_store_mode": False,
                                 "per_xreal_mirror_preference_restored": not args.test_only,
                                 "desktop_routing_removed": True,
                                 "direct_presentation_always_on": True,
                                 "background_controller_keeps_glasses_rendering": True,
                                 "background_unity_surface_survives_phone_view_destruction": True,
                                 "ar_foreground_service_in_renderer_process": True,
                                 "legacy_xreal_connected_display_auto_enable": not args.test_only,
                                 "embedded_display_watchdog_enabled": not args.test_only,
                                 "discovery_uses_direct_service_snapshots": True,
                                 "guard_uses_direct_window_and_thermal_services": True,
                                 "mirror_setting_uses_shell_client_with_readback": True,
                                 "global_app_mode_override_only_if_sbs_exceeds_live_cap": not args.test_only,
                                 "hardware_mode_timeout_seconds": 10,
                                 "dex_hooks_restore_exact_baseline": not args.test_only}
    (output / "build.json").write_text(json.dumps(report, indent=2) + "\n")
    unsigned.unlink(); aligned.unlink()
    print(apk)


if __name__ == "__main__": main()
