#!/usr/bin/env python3
"""Build the phone settings UI and layer it onto the verified port APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
from permission_manifest import patch_manifest as patch_permission_manifest, patch_menu_manifest
from adaptive_manifest import patch_adaptive_manifest
from app_menu import patch_app_menu, restore_app_menu
from launcher_layout import verify_launcher_layout
from launcher_icons import export_launcher_icons
from settings_artwork import export_settings_artwork, WIDGET_ARTWORK, MODEL_ARTWORK
from launcher_storage import verify_launcher_storage
from branding import APP_NAME, app_name_values, icon_payloads, patch_resources
from startup_theme import add_startup_theme, patch_splash_theme_manifest, STYLE_ID
from wireless_cast import compile_port_client, patch_wireless_cast_dex
from trackpad_taps import patch_trackpad_taps, restore_trackpad_taps, RELEASE_DELAY_SECONDS, patch_trackpad_dex
from trackpad_background import BACKGROUNDS, patch_trackpad_background, restore_trackpad_background
from verify_display_defaults import verify_display_control_excluded, verify_display_defaults
from app_version import load_version, manifest_version, patch_manifest_version, write_java_version
from updater_manifest import patch_updater_manifest
from debug_manifest import patch_debug_manifest

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "artifacts/port-default-m/myglasses-startup-candidate.apk"
BASELINE_SHA = "79aaf7a133931b7b886cb334d94d4b4e5a643ac272da4470ca22eceea7e5912e"
SDK = Path.home() / "Library/Android/sdk"
TOOLS = SDK / "build-tools/36.0.0"
PLATFORM = SDK / "platforms/android-34/android.jar"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/unbound-launcher-help")
    parser.add_argument("--preview-only", action="store_true")
    args = parser.parse_args()
    java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java))
    output = args.output.resolve()
    build = output / "build"
    classes = build / "classes"
    dex = build / "dex"
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(exist_ok=True)
    version = load_version()
    with zipfile.ZipFile(args.baseline) as source:
        base_version = manifest_version(source.read("AndroidManifest.xml"))
        if version["version_code"] <= base_version["version_code"]:
            raise ValueError("The app version code must allow updating the base APK")
        launcher_icons = export_launcher_icons(source, build / "launcher-icons")
        settings_assets = export_settings_artwork(source, build / "settings-artwork")
    icon_assets = {"assets/launcher-icons/" + name + ".png": build / "launcher-icons" / (name + ".png")
                   for name in launcher_icons}
    icon_assets.update(settings_assets)

    def run(*command):
        subprocess.run([str(arg) for arg in command], check=True, env=env)

    def compile_sources(sources, dest):
        # A reused output must not package classes removed from the source tree.
        if dest.exists():
            shutil.rmtree(dest)
        dest.mkdir(parents=True, exist_ok=True)
        run(java / "bin/javac", "--release", "8", "-classpath", PLATFORM, "-d", dest, *sources)
        jar = dest.parent / (dest.name + ".jar")
        run(java / "bin/jar", "cf", jar, "-C", dest, ".")
        return jar

    sources = sorted((ROOT / "settings-ui/src").rglob("*.java"))
    sources.append(write_java_version(build / "generated", version, base_version))
    jar = compile_sources(sources, classes)
    run(TOOLS / "d8", "--min-api", "29", "--lib", PLATFORM, "--output", dex, jar)

    def sign(unsigned, aligned, apk):
        run(TOOLS / "zipalign", "-f", "-P", "16", "4", unsigned, aligned)
        run(TOOLS / "apksigner", "sign", "--ks", ROOT / "artifacts/probe/local-debug.p12",
            "--ks-key-alias", "port-probe", "--ks-pass", "pass:android", "--out", apk, aligned)
        run(TOOLS / "apksigner", "verify", "--verbose", apk)
        run(TOOLS / "zipalign", "-c", "-P", "16", "4", apk)

    preview_jar = compile_sources(sources + sorted((ROOT / "settings-ui/preview/src").rglob("*.java"))
                                  + [ROOT / "port-client/src/dev/beampro/port/ConnectionAnimation.java",
                                     ROOT / "bridge/src/dev/beampro/bridge/DisplayDumpPipe.java"], build / "preview-classes")
    preview_dex = build / "preview-dex"
    preview_dex.mkdir(exist_ok=True)
    run(TOOLS / "d8", "--min-api", "29", "--lib", PLATFORM, "--output", preview_dex, preview_jar)
    preview_resources = build / "preview-res-source"
    shutil.copytree(ROOT / "settings-ui/preview/res", preview_resources, dirs_exist_ok=True)
    (preview_resources / "drawable").mkdir(exist_ok=True)
    shutil.copyfile(ROOT / "branding/app-icon.png", preview_resources / "drawable/unbound_icon.png")
    run(TOOLS / "aapt2", "compile", "--dir", preview_resources, "-o", build / "preview-res.zip")
    run(TOOLS / "aapt2", "link", "-I", PLATFORM, "--manifest", ROOT / "settings-ui/preview/AndroidManifest.xml",
        "--replace-version", "--version-name", version["version_name"], "--version-code", version["version_code"],
        "-o", build / "preview-base.apk", build / "preview-res.zip")
    with zipfile.ZipFile(build / "preview-base.apk") as source, zipfile.ZipFile(build / "preview-unsigned.apk", "w") as target:
        for info in source.infolist():
            target.writestr(info, source.read(info))
        target.write(preview_dex / "classes.dex", "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
        for name, path in icon_assets.items():
            target.write(path, name, compress_type=zipfile.ZIP_DEFLATED)
    preview_apk = output / "settings-preview.apk"
    sign(build / "preview-unsigned.apk", build / "preview-aligned.apk", preview_apk)
    verify_display_control_excluded(preview_apk)
    if args.preview_only:
        print(preview_apk)
        return

    if sha256(args.baseline) != BASELINE_SHA:
        raise ValueError("Unexpected baseline; use the verified M-default build")
    with zipfile.ZipFile(args.baseline) as source:
        original = source.read("classes.dex")
        launcher_layout = verify_launcher_layout(source)
        launcher_storage = verify_launcher_storage(source)
        if "classes5.dex" in source.namelist():
            raise ValueError("Settings UI already present")
        brand_icons = icon_payloads(source, ROOT / "branding/app-icon.png", build / "brand-icons",
                                   symbol_path=ROOT / "branding/symbol-white.png")
        branded_resources = add_startup_theme(patch_resources(source.read("resources.arsc")))
        branded_labels = app_name_values(branded_resources)
        if any(label != APP_NAME for label in branded_labels):
            raise ValueError("An app label did not receive the new brand")
    (build / "original.dex").write_bytes(original)
    patcher = [java / "bin/java", "--class-path", ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar",
               ROOT / "tools/PatchSettingsDex.java"]
    run(*patcher, "patch", build / "original.dex", build / "settings.dex")
    run(*patcher, "restore", build / "settings.dex", build / "restored.dex")
    if (build / "restored.dex").read_bytes() != original:
        raise ValueError("Restored DEX differs from baseline; original settings behavior was changed")
    permission_patcher = [java / "bin/java", "--class-path", ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar",
                          ROOT / "tools/PatchPermissionDex.java"]
    run(*permission_patcher, "patch", build / "settings.dex", build / "permission-settings.dex")
    run(*permission_patcher, "restore", build / "permission-settings.dex", build / "permission-restored.dex")
    if (build / "permission-restored.dex").read_bytes() != (build / "settings.dex").read_bytes():
        raise ValueError("Permission startup hooks did not restore the exact original settings DEX")
    settings_dex = patch_wireless_cast_dex((build / "permission-settings.dex").read_bytes(), build, java, env)
    settings_dex = patch_trackpad_dex(settings_dex, build, java, env)
    client_dex = compile_port_client(build, java, env, PLATFORM, TOOLS)
    unsigned = build / "unsigned.apk"
    with zipfile.ZipFile(args.baseline) as source, zipfile.ZipFile(unsigned, "w", allowZip64=True) as target:
        for info in source.infolist():
            if info.filename.upper().startswith("META-INF/") and info.filename.upper().endswith((".SF", ".RSA", ".DSA", ".EC", "MANIFEST.MF")):
                continue
            value = source.read(info)
            if info.filename in BACKGROUNDS:
                value = patch_trackpad_background(info.filename, value)
                if restore_trackpad_background(info.filename, value) != source.read(info):
                    raise ValueError("Trackpad background patch changed unrelated resource data")
            if info.filename in brand_icons: value = brand_icons[info.filename].read_bytes()
            if info.filename == "resources.arsc": value = branded_resources
            if info.filename == "classes.dex": value = settings_dex
            if info.filename == "classes4.dex": value = client_dex
            if info.filename == "AndroidManifest.xml":
                value = patch_manifest_version(value, version)
                value = patch_splash_theme_manifest(value)
                value = patch_permission_manifest(value)
                value = patch_permission_manifest(value, "dev.beampro.settings.AppMenuActivity")
                value = patch_menu_manifest(value)
                value = patch_adaptive_manifest(value)
                value = patch_debug_manifest(patch_updater_manifest(value))
                (build / "permission-manifest.bin").write_bytes(value)
            if info.filename == "lib/arm64-v8a/libil2cpp.so":
                baseline_library = value
                value = patch_app_menu(value)
                if restore_app_menu(value) != baseline_library:
                    raise ValueError("App menu patch did not restore the exact original library")
                menu_library = value
                value = patch_trackpad_taps(value)
                if restore_trackpad_taps(value) != menu_library:
                    raise ValueError("Trackpad tap patch did not restore the exact app-menu library")
            target.writestr(info, value)
        target.write(dex / "classes.dex", "classes5.dex", compress_type=zipfile.ZIP_DEFLATED)
        for name, path in icon_assets.items():
            target.write(path, name, compress_type=zipfile.ZIP_DEFLATED)
    apk = output / "the-unbound-launcher.apk"
    sign(unsigned, build / "aligned.apk", apk)
    changed = []
    with zipfile.ZipFile(args.baseline) as source, zipfile.ZipFile(apk) as target:
        for name in source.namelist():
            if name.upper().startswith("META-INF/") and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC", "MANIFEST.MF")):
                continue
            if source.read(name) != target.read(name):
                changed.append(name)
        added = set(target.namelist()) - set(source.namelist())
    expected_changed = {"classes.dex", "classes4.dex", "AndroidManifest.xml", "lib/arm64-v8a/libil2cpp.so", "resources.arsc", *brand_icons, *BACKGROUNDS}
    if set(changed) != expected_changed or added != {"classes5.dex", *icon_assets}:
        raise ValueError(f"Unexpected payload changes: {changed}, {added}")
    display_defaults = verify_display_defaults(apk, args.baseline, java, env)
    report = {
        "type": "signature-oled-phone-settings", "apk": str(apk), "apk_sha256": sha256(apk),
        "branding": {"name": APP_NAME, "all_app_name_configurations": len(branded_labels),
                     "launcher_icon_payloads": sorted(brand_icons), "adaptive_icon_xml_preserved": True,
                     "application_id_and_saved_state_preserved": True,
                     "masters": ["branding/logo.png", "branding/app-icon.png", "branding/symbol-white.png"],
                     "launcher_background": "#000000", "launcher_mark": "white",
                     "generation": "branding/generation.json"},
        "baseline": str(args.baseline), "baseline_sha256": BASELINE_SHA,
        "version": version, "base_app": {"name": "MyGlasses", **base_version},
        "about_page": {"fan_project_description": True, "original_privacy_and_terms_callbacks": True,
                       "app_and_base_versions": True, "github_app_updater": True},
        "help": {"placement": "immediately below About", "url": "https://discord.gg/J7t64xupfQ",
                 "opens_with": "Help menu with Get help first, then debugging controls",
                 "debug_export": {"format": "ZIP", "max_bytes": 15000000, "local_ring_bytes": 12000000,
                                  "share": True, "save_to_downloads": True, "collection_opt_in": True}},
        "theme": "Signature OLED: true black with monochrome controls in every device theme", "changed_payloads": changed,
        "adaptive_phone_layout": {"two_panes_from_dp": 600, "maximum_width_dp": 1280,
                                  "settings": "glasses and launch beside independently scrolling settings",
                                  "app_menu": "editor beside live headset preview",
                                  "large_text": "raises the two-pane breakpoint",
                                  "orientation": "unrestricted on the four phone pages",
                                  "ar_activity_declarations_preserved": True,
                                  "landscape": {"aspect_ratios": ["16:9", "16:10"],
                                                "pinned_launch_action": True,
                                                "toolbar_search_above_editor_and_preview": True,
                                                "compact_preview_below_height_dp": 520,
                                                "setup": "overview and actions beside scrolling details"}},
        "startup_theme": {"background": "#000000", "icon_background": "#000000", "mark": "white",
                          "activity": "SplashActivity only", "resource_id": hex(STYLE_ID),
                          "original_resource_ids_and_other_activity_themes_preserved": True},
        "display_defaults": {"resolution_refresh_and_mode": "original device-specific SDK defaults",
                             "settings_panel_included": False, "saved_display_choices_read": False,
                             "custom_startup_overrides_included": False,
                             "experimental_mono_renderer_included": False,
                             "verification": display_defaults},
        "oled_design": {"always_enabled": True, "background_color": "#000000",
                        "settings_rows": "unframed with subtle separators", "controls": "monochrome",
                        "widget_artwork": WIDGET_ARTWORK, "model_specific_artwork": MODEL_ARTWORK,
                        "model_selection": "onSdkSuccess model argument; SDK lookup only before first callback",
                        "unknown_connected_model": "no guessed product photo",
                        "disconnected_model": "last recognized model; XREAL One by default",
                        "cached_previous_model_ignored_while_connected": True,
                        "serial_and_firmware": "glasses information action"},
        "added_payloads": sorted(added), "original_dex_restored_byte_for_byte": True,
        "app_menu": {"works_without_glasses": True, "search_name_and_package": True,
                     "circular_icons_in_list_and_preview": True, "original_builtin_icons": launcher_icons,
                     "headset_preview": {**launcher_layout, "live_draft": True, "page_controls_and_swipes": True,
                                         "search_highlights_in_context": True, "tap_app_to_edit": True,
                                         "hidden_apps_excluded": True},
                     "drag_reorder": True, "searchable_move_destination": True, "hidden_apps_reversible": True,
                     "bulk_visibility": {"hide_all": True, "show_all": True, "ignores_search_filter": True,
                                         "undo": True, "restores_hidden_order_after_restart": True,
                                         "updates_unavailable_saved_entries": True, "empty_headset_page_safe": True},
                     "shared_native_layout": "AppDetails/EPHInfoConfig/LayoutConfig.json", "atomic_save": True,
                     "native_storage_path_verified": launcher_storage, "migrates_legacy_phone_layout": True,
                     "legacy_recovered_before_ar_startup": True, "apply_to_glasses_restarts_own_ar_process": True,
                     "detects_concurrent_changes": True, "existing_ar_reorder_retains_hidden_choices": True,
                     "apply": "Save then Apply to glasses; offline changes load on the next AR Space start", "native_patch_restores_byte_for_byte": True},
        "trackpad_taps": {"synthetic_press_seconds": RELEASE_DELAY_SECONDS,
                          "finger_jitter_tolerance_normalized": 0.025,
                          "both_orientations": True, "scroll_strip_and_multitouch_preserved": True,
                          "existing_click_eligibility_and_release_action_preserved": True,
                          "native_patch_restores_byte_for_byte": True},
        "trackpad_background": {"screen_and_pad_color": "#000000", "both_orientations": True,
                                "controller_modes": ["Spatial Mouse", "Vertical Trackpad", "Horizontal Trackpad"],
                                "dots_and_buttons_preserved": True, "resource_payloads": sorted(BACKGROUNDS),
                                "resource_patches_restore_byte_for_byte": True},
        "phone_screen_off": {"trackpad_button": True, "restore": "either volume button",
                             "physical_target": "default logical display's built-in physical address only",
                             "phone_remains_unlocked": True, "touches_intercepted_before_activity": True,
                             "requires_updated_shell_helper": True,
                             "independent_watchdog_restores_on_helper_exit_or_8_second_heartbeat_timeout": True},
        "ar_keep_awake": {"automatic": True, "built_into_apk": True,
                          "mechanism": "root View.keepScreenOn plus FLAG_KEEP_SCREEN_ON on visible phone AR controller windows",
                          "no_helper_or_extra_permissions_required": True,
                          "retained_across_visible_activity_pauses": True,
                          "released_on_activity_stop_or_display_disconnect": True,
                          "system_timeout_and_lock_settings_unchanged": True,
                          "manual_phone_screen_off_preserved": True},
        "other_native_libraries_and_unrelated_resources_unchanged": True,
        "manifest_change": "added non-exported PermissionSetupActivity and AppMenuActivity; existing permissions and components retained",
        "permission_setup": {"startup_checklist": True, "overlay_initialization_deferred_until_granted": True,
                             "first_launch_disclaimer": {"before_permissions_and_initialization": True,
                                                         "accept_countdown_seconds": 10, "acceptance_persisted": True,
                                                         "firmware_update_url": "https://www.xreal.com/ota/"},
                             "only_overlay_required_to_continue": True, "optional_permissions_can_be_skipped": True,
                             "live_android_status": True, "existing_initialization_preserved": True},
        "existing_settings_state_and_callbacks_preserved": True,
        "signature_and_alignment_verified": True,
        "wireless_cast": {"miracast_requires_accessible_vendor_service": True,
                          "unavailable_miracast_reported_without_crashing": True,
                          "other_cast_receivers_preserved": True,
                          "dex_hooks_restore_byte_for_byte": True},
        "installed_on_phone": False, "phone_runtime_validation": "not performed by this builder",
    }
    (output / "build.json").write_text(json.dumps(report, indent=2) + "\n")
    # The signed APK and DEX audit files are sufficient to reproduce/verify this build.
    # Avoid retaining two extra gigabytes of unsigned/aligned APK copies.
    unsigned.unlink()
    (build / "aligned.apk").unlink()
    print(apk)


if __name__ == "__main__":
    main()
