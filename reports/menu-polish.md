# Android app name, About and menu transitions

## Build 2939 — explicit fade, emulator only

The requested effect is a fade without upward motion. Menu launches now pass
explicit Android fade-in/fade-out options. Android 14+ also receives persistent
open/close transition overrides; older Android versions use the corresponding
pending-transition override. This avoids relying on the phone's default slide.

The emulator preview passes 47 checks, including the actual launch options for
permissions, About, Help and the editor, opacity changes with no translation or
scaling, Back and recreation. Evidence:
`artifacts/fade-menus-ui-2939/fade-checks.log`.
Only the emulator preview was installed. The Pixel remains on build 2938, as
requested. The app name remains **Unbound Launcher**.

## Build 2938 — restore the previous editor animation consistently

The requested reference is the **app-menu editor before build 2937**. Its native
`Theme.Transparent` window style is preserved in the APK at resource
`0x7f1101da`. About and Help now open as real Android Activities using that same
style. Their page content no longer creates a dialog window or animates the
decor. Permission setup, authority management, Settings and on-device ADB use
the same native window style. Android handles the enter/return animations.

The manifest and runtime themes both select the translucent window, avoiding the
previous mismatch between a simulated dialog transition and Activity navigation.
The original editor's animation resources and styles are unchanged.

The final naming preference is **Unbound Launcher everywhere**, without “The”.
The Android label and all in-app branding use that name. About retains its app,
base-app and build-number rows, with build **2938** generated from `version.json`.

Page state survives configuration changes and Activity recreation. Update
observers follow the About Activity lifecycle. Completed debug exports remain
available when Help is reopened. The original XREAL legal helper is opened with
the visible About Activity as its owner.

Android emulator checks cover native navigation, matching manifest/runtime
window styles, Back, recreation/scroll restoration, About, Help/debug export,
updater settings and the editor. Evidence is under
`artifacts/native-menus-ui-2938/`; the signed candidate is
`artifacts/unbound-launcher-native-menus-2938/the-unbound-launcher.apk`.

- 34 host checks pass for branding, versions, private menu registration, adaptive
  manifests, the embedded backend, updater and source archive.
- 156 Android emulator checks pass: native menu navigation/recreation (39),
  animations disabled (39), About/legal helper (11), Help/debugging and retained
  exports (38), About update preferences/reopening (7), and the editor (22).
- The signed APK passes signature and 16 KB alignment verification. Its manifest
  selects the original editor theme for all six phone-menu Activities.
- SHA-256 comparison with build 2937 confirms identical original DEX, native
  libraries, Unity assets, resources, launcher client and helper DEX. Changes are
  limited to the phone settings/backend DEX, manifest, corresponding sources and
  helper ZIP metadata.

## Build 2937 — initial name and version changes

The requested Android name is **Unbound Launcher**. An earlier edit changed
`tools/branding.py`, but the final embedded-ADB builder copied its resource table
from a pinned APK that still used **The Unbound Launcher**. The phone UI also had
its own unchanged name constant.

The final builder now reapplies branding before package relocation and verifies
every app-name configuration. The resource patch accepts the previous project
name as a migration input. Resource IDs, launcher components, icons, package ID
and signing identity are preserved. The phone toolbar, About, setup, installer
guidance and debug report use the updated name.

About shows **App version**, **Base app version** and **Build number**. The app
version and build number are generated from `version.json`, which also supplies
the Android manifest. This build is **0.1.2 alpha / 2937**, based on
**MyGlasses 2.2.2**.

App permissions already uses Android's Material activity transition. The app-menu
editor now selects that same theme explicitly. About and Help share `MenuPage`,
which loads the platform activity-open and activity-close animations at runtime.
Their decor animates independently of content refreshes; a second window
animation is disabled after decor creation. Back navigation waits for the exit,
repeated Back does not restart it, and owner destruction removes the window
immediately. Android's transition-animation scale is respected, including zero.

## Verification

- 27 host checks pass: branding, versions, embedded manifest, updater and source
  archive. The branding regression starts with an APK resource table using the
  previous name and confirms all 13 language configurations receive the new name.
- Android 13 emulator: 46 menu checks compare motion and duration against the
  real App permissions window's style, exercise reopen and repeated Back, and
  check cleanup. Another 16 checks pass with transition animations disabled.
- Existing Android checks pass: About (20), Help/debugging (37), app-menu editor
  (22). About compares its visible version and build number with PackageManager,
  exercises the original legal callbacks, and checks both Back paths and owner
  destruction.
- The signed APK passes signature and 16 KB alignment checks. Android's APK
  resource reader reports **Unbound Launcher** for every supported locale.
- The complete `tools/verify_embedded_adb.py` audit passes for the final signed
  APK (`artifacts/unbound-launcher-menu-polish-2937/verification.json`).
- SHA-256 comparison with build 2936 confirms identical native libraries, Unity
  assets, original controller DEX, launcher client and helper DEX. Changes are
  limited to the settings/backend DEX, app-name resources, version metadata,
  source archive and the helper ZIP's build-time metadata.
- The exact audited APK is installed on the Pixel 8 Pro / Android 17. Its
  installed SHA-256 matches the signed local artifact. Twenty-three read-only
  production checks pass for the Android application and app-menu labels, 13
  locale selections, the shipped About name/version/build constants, and shared
  menu classes. Settings opens successfully after the update. Evidence is in
  `installed-identity.log` and `installed-verification.json` beside the APK.

Local build: `artifacts/unbound-launcher-menu-polish-2937/the-unbound-launcher.apk`.
Emulator fixture and evidence: `artifacts/menu-polish-ui-2937/`.
