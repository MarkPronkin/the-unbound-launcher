# Direct glasses display (no DeX/Desktop)

The [OPPO CPH2791 / Air 2 Pro 0.1.2 report](oppo-air2-pro-v012.md) reaches native
1080p output but fails EGL surface creation with `EGL_BAD_ATTRIBUTE`. Build 2942
corrects an OpenVG colorspace attribute in the SDK's GLES profile; the original
failure and corrected rendering are reproduced on a Mali GPU. Physical OPPO
glasses validation is pending.

The [Pixel 10 Pro / One Pro v0.1.2 report](pixel10-pro-one-pro-v012.md)
shows a separate startup failure: after the stereo switch, SurfaceFlinger
exposes only 640 × 480, with no native mode for the helper to select. The
receiver bug found in its stereo hook is fixed in source; the VGA-only output
failure still needs hardware investigation.

## Background trackpad lifecycle — build 2928

The Pixel 8 Pro on build 2927 returned to phone mirroring when the user left the
trackpad. Wireless ADB evidence in `artifacts/trackpad-background-2928/before/`
shows `LaunchSpaceAcrivity.onStop` pausing Unity at 13:14:12, NRSDK quitting
rendering and destroying its external surface, and Android subsequently freezing
the cached `:space` process. The XREAL One remained connected at 3840 × 1080 / 60 Hz.
This was a renderer lifecycle failure, rather than a lost wireless ADB connection
or a display-mode fallback.

Build 2928 forwards the original phone activity's three Unity pause calls
(`onPause` and both `onStop` calls) through a session-scoped adapter. A live,
unlocked session with an active glasses display retains Unity rendering and
engine focus when its phone controller is hidden. Calls for another player,
finishing/destroyed owners and closed sessions retain ordinary pause/focus
behavior. Android's activity callbacks, phone input lifecycle, IME cleanup and
mode-switch handling remain in the original SDK methods. Each modified DEX body
can be restored exactly to the pinned baseline.

A private `ArSessionService` runs in the renderer's `:space` process and uses
Android's [connected-device foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).
It starts while the controller is visible and prevents the renderer becoming a
cached process when the phone switches apps. Its ongoing **AR Space is running**
notification returns to the trackpad and offers **Stop AR**. The service is not
sticky, rejects stale session commands, and stops on display loss, phone lock or
session teardown. The APK already declared the required foreground-service
permissions.

Validation and hardware results for this change are recorded under
`artifacts/trackpad-background-2928/`. The emulator fixture executes the actual
patched SDK methods with a real external Presentation and covers a full-screen
phone activity stopping the controller, continued rendering ownership, normal
return, foreground-service cleanup, stale commands and the notification actions.
It also checks ordinary pause/focus forwarding after the session ends.

Validation completed on 2026-10-28: 15 relevant host tests pass (including 103
SDK-derived native-mode assertions), all 49 Android lifecycle fixture checks
pass, and the full signed-APK/manifest/DEX restoration audit passes. Build 2928
was installed on the Pixel; the device file's SHA-256 matches the audited APK.
With the controller stopped, the same renderer PID and foreground service
survived a 102.36-second interval; the external renderer's frame counter advanced
from 8435 to 20037. Home, Android Settings and the launcher's settings were used
outside the trackpad. The wearer confirmed **“i tested it, it continues running”**
and accepted the ongoing notification as the return control. No floating bubble
was added. Results: `artifacts/trackpad-background-2928/validation.json`.

The launcher draws AR through the SDK's native glasses Presentation from the
phone, with no Desktop/DeX task on the glasses. This is the standard path: it is
always installed and there is no Experimental toggle.

## Design

### External monitors and desktop mode

Display overrides are leased only to identified physical glasses. The first
snapshot without glasses restores the previous Mirror setting, app-mode policy
and glasses connection preference, with no disconnect grace period. Ordinary
monitors retain their own mirroring/desktop choices, including when Android
reuses a glasses display ID. An unplugged pair's route is also restored when
another pair stays connected. Individual restoration failures do not prevent
attempts to release the other overrides; pending recovery stays journaled.

AR keep-awake and the phone screen-off action also require an identified glasses
display. The helper independently checks the display's product identity before
authorizing phone panel blanking. A normal USB-C or ASUS monitor cannot keep an
old AR controller's screen protection active or authorize that power operation.

Host coverage includes Pixel-style connection preferences and Samsung/legacy
frameworks without that API, absent/disabled/enabled initial Mirror settings,
monitor-only startup and polling, glasses-to-monitor handoff, recycled display
IDs, helper recovery, glasses reconnect, and lifecycle release of keep-awake.
The production adapter is exercised across partial/backported OEM API contracts.
These are software regressions; physical Samsung/Pixel monitor handoff remains
to be checked on devices.

Commands:

```sh
python3 -m unittest discover -s tools -p 'test_managed_display.py' -v
python3 -m unittest discover -s tools -p 'test_managed_display_platform.py' -v
python3 -m unittest discover -s tools -p 'test_multibrand_compat.py' -v
python3 -m unittest discover -s tools -p 'test_legacy_display_startup.py' -v
python3 -m unittest discover -s tools -p 'test_external_display_controls.py' -v
python3 -m unittest discover -s tools -p 'test_phone_screen.py' -v
```

### Glasses presentation lifecycle

- The working OnePlus path already drew AR through the SDK's native
  Presentation; it still relied on the SDK's extra external ("fake") activity
  for lifecycle. The direct path removes that dependency and uses the SDK's
  phone-controlled lifecycle (`multiResumeMode=false`): `LaunchSpaceAcrivity`
  stays on the phone and the native renderer shows its Presentation on the
  glasses display.
- `PatchPresentationDex` adds guards to five `NRXRApp` methods
  (`startUnityActivityInternal`, `moveToVirtualDisplay`,
  `readyForDynamicSwitchDPImpl`, `readyForRestartSessionImpl`,
  `getFakeActivity`) and routes the phone activity's Unity focus call through
  `PresentationOnlyCompat.windowFocus`. `PresentationOnlyCompat` is installed
  unconditionally from `PortStatus`; the guards always hand AR to it. Each
  original method body stays intact behind its guard, and the patch restores the
  exact baseline DEX.
- The embedded helper routes XREAL displays to **Mirror** (connection preference
  2), enables the display, and journals the previous connection choice. While
  Mirror-routed glasses are present it also turns on Android's global
  `mirror_built_in_display` setting. Both are restored when the glasses leave,
  the phone locks or overheats, or the helper stops. It never requests Desktop. Its existing temporary stereo mode still corrects the
  Pixel's 640 × 480 fallback and is restored on disconnect, lock or failure.
- Startup waits silently for native glasses output (1920 or 3840 × 1080 at
  60/72/90/120 Hz, held for 1 s). It shows no status view, so the dark
  connection screen stays visible. It presents one dark dialog only if DeX is
  still active or startup times out/fails.

## Defects found during validation

1. **VerifyError in the focus hook (2916 candidate, fixed before install).**
   The SDK's `onWindowFocusChanged` overwrites `p0` with `mUnityPlayer` before
   the call, so passing `p0` as the activity made ART reject
   `LaunchSpaceAcrivity` — every AR launch would have crashed, setting on or
   off. The hook now takes only `(player, focused)` and matches the session by
   its player instance. Caught by the emulator fixture.
2. **Startup never accepted full-resolution output (2916, Pixel).** The wait
   runs in the `:space` process, where the SDK service never starts:
   `NRServiceControl.isEthernetSeries` stays false, so target 5
   (`NR_EDID_3840_1080_60`) was read in the legacy namespace as 1920 × 1080 @
   72 Hz. The Force 3D / 60 Hz filters also cannot run there. Readiness is now
   judged from the physical mode alone; the XREAL One advertises only its
   current EDID mode plus VGA fallbacks, so native 1080p output means the mode
   switch is complete. After an in-AR 2D/3D switch, restart waits until the
   glasses re-enumerate or change mode, like the SDK's placeholder check.
3. **Unity left paused by a start-up permission dialog (2917, Pixel).** An
   implicit `READ_EXTERNAL_STORAGE` request (auto-denied on Android 13+) paused
   the phone owner during Unity's initialisation. Unity's pause timed out
   (2000 ms), applied after the resume, and the session stayed paused; its
   glasses connection closed and the native display skipped the 3840 × 1080
   output. The direct session now sets the SDK's own `fakePause` mode (used on
   Android ≤ 9): a phone dialog does not pause the renderer, leaving the app
   (onStop) still does, and renderer focus is kept while the owner is visible.
4. **White startup dialog (2918 feedback, fixed in 2919).** The status dialog
   inherited the entry activity's light Unity theme
   (`UnityThemeSelector.Translucent`), so white status messages appeared over
   the phone and in the glasses mirror while the session was starting. Waiting
   is now silent; only a real failure opens a dark dialog. The fixture fails if
   any window appears during the wait.
5. **Desktop routing (removed in 2919).** The helper previously saved Desktop
   for the glasses and turned `mirror_built_in_display` off. It now saves Mirror
   and turns that setting on, restoring both afterwards. No Desktop code path
   remains in production; `verify_embedded_adb.py` rejects `AUTO_DESKTOP` and the
   old mode-switch calls in the helper. The Settings → Experimental → Direct
   glasses display toggle and its mode-switch RPC were removed; the direct path
   is always active.

## Validation (2026-10-27)

- Host tests: Mirror routing and restoration of the previous connection choice
  and `mirror_built_in_display` (the fake phone starts with mirroring off, so
  the write and its restoration are both exercised), helper crash recovery,
  OEM API combinations, 103 native output checks derived from the dumped SDK
  enums (including the Pixel's 640 × 480 / 850 × 480 fallbacks), exact DEX
  restore and rejection of repeated or unrelated changes; the related
  display/stereo suites pass.
- Lifecycle fixture with the real patched SDK methods, a real Presentation and
  an external display; the fixture's renderer process throws if the SDK target
  or service is read. The display starts at the 640 × 480 fallback: AR must not
  start and **no dialog or status window may appear** while waiting, then the
  display switches to 3840 × 1080 in place and AR starts. **34/34** on the API 33
  emulator. A deliberately re-added waiting dialog makes the fixture fail on the
  no-window check.
- Fixture history on the Pixel 8 Pro / Android 17: the 29-check 2916 version
  passed. The 35-check (2918) version ran only on the emulator. The 2919 run on
  the Pixel failed to install its test package
  (`INSTALL_FAILED_VERIFICATION_FAILURE`, Android's verification of ADB
  installs) and was not retried while the glasses were attached, because the
  fixture would also detect the real XREAL display.
- `verify_embedded_adb.py` passes for 2916, 2917 and 2918. Build 2919 passes the
  expanded audit, including the always-on Presentation wiring, absent Settings
  toggle, removed Desktop routing and Mirror-preference restoration.
- **Pixel 8 Pro (Android 17) + XREAL One, build 2918: AR works.** The phone keeps
  `LaunchSpaceAcrivity` on display 0 and the `:space` process owns a visible
  `PRESENTATION` window on the glasses display (44, 3840 × 1080 @ 60 Hz); the
  helper log shows Mirror routing and the temporary stereo mode. Evidence:
  `artifacts/presentation-only-2916/`.
- Build 2919 installed successfully on the Pixel 8 Pro (Android 17), but its
  silent startup has not yet been confirmed with the glasses.
- Settings screenshots after removing the toggle (emulator, font scale 1.0 and
  1.5): `artifacts/presentation-only-2919/screens/`.
- **Samsung** has not been tested on hardware. DeX must be off or the glasses
  must be set to Mirrored; the helper no longer requests Desktop.

## Not yet validated

- Samsung (DeX off / Mirrored): no device was available.
- Switching 2D/3D inside AR, unplugging during AR and leaving/returning to the
  app on real glasses (covered only by the fixture).
- Build 2919's silent startup and always-on path on real glasses.

Commands:

```sh
python3 -m unittest discover -s tools -p 'test_presentation_only.py' -v
python3 tools/verify_presentation_runtime.py            # emulator-5554
python3 tools/verify_presentation_runtime.py --serial IP:PORT --physical-device  # glasses unplugged
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-presentation-only-2919
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-presentation-only-2919/the-unbound-launcher.apk
```
