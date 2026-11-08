# Honor Magic 6 Pro / XREAL One Pro+: unresponsive phone controller

The reporter describes MagicOS 11 / Android 17 and an unresponsive Spatial
Mouse screen, including its menu. `unbound-debug-20261102-000746-a925a83f.zip`
identifies **HONOR BVL-AN16, Android 17 / API 37, Unbound 0.2.3 alpha build
2957**, and **XREAL One Pro+ (SDK model 20, USB 3318:0444)**. The screenshot
shows the Spanish Spatial Mouse controller. Raw evidence is retained locally
under `artifacts/honor-magic6-touch/export/`.

## What the bundle establishes

- Firmware `2.0.0.20260920102250` matches the build's pinned recommendation.
- After initial USB reconnect failures, SDK startup returns success at
  **00:04:49.794 UTC on November 2**, followed by a successful connection
  handoff at **00:04:56.321**. A later attempt also succeeds, with handoff
  at **00:05:57.141**. This is not a persistent *Starting AR Space* timeout.
- The external display is **3840 × 1080 / 60 Hz**. Renderer samples show
  `GlassConfig.initialized=true`, `isDeviceActive=true` and a running XR
  display subsystem. They establish renderer activity, not optical correctness.
- `app.log` contains collection headers without timestamped logcat records.
  There is no retained touch dispatch, keyboard-state transition, helper-input
  rejection or crash that identifies the failing input stage.
- At export, `MenuActivity` owns the phone focus and the controller's task
  is not visible. The SurfaceFlinger dump likewise describes the settings
  UI. The earlier screenshot therefore cannot be paired with a contemporaneous
  controller-window/input dump. Opening settings later does not disprove the
  reported controller failure.

## Confirmed source defect; Honor causality remains unconfirmed

`LaunchSpaceAcrivity.onCreate` adds `mImeMaskView` with
`new WindowManager.LayoutParams(0, 0, 2038, 0, -3)`. `onResume` repeats the
same addition after `onPause` removes it. This is an invisible, zero-sized,
**focusable, touch-modal application overlay**. It has no touch forwarding
to the phone controller.

Zero dimensions do not express input pass-through. Android documents
[`FLAG_NOT_TOUCH_MODAL` and `FLAG_NOT_TOUCHABLE`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)
as the controls for outside-window and all-window touch delivery,
respectively. The original mask lacks both. This is a concrete input-routing
hazard consistent with all controller controls becoming unresponsive; it is
separate from Unity discarding trackpad gestures when it believes a keyboard
is visible. The latter already has a correction in the reported build.

The supplied evidence does **not** establish that this mask caused the Honor
failure, or that MagicOS alone exhibits it. Hardware confirmation is required.

## Correction

Both mask additions now route through `KeyboardCompat.addImeMask`, which ORs
`FLAG_NOT_TOUCHABLE | FLAG_NOT_TOUCH_MODAL` into the original parameters
before calling `WindowManager.addView`. The mask retains its focusability,
IME flags, geometry, window type, existing removal and recreation behavior.
In particular, the correction does not add `FLAG_NOT_FOCUSABLE`, which would
change the mask's keyboard role.

`PatchInputCompatDex` replaces only those two calls, using the original
receiver/view/layout registers and the same instruction width. Its existing
IME classification and private pointer-flag corrections remain. It validates
the entire input DEX against the pinned baseline and restores it byte for
byte; unrelated or duplicate changes are rejected.

## Reporter retest

Install the signed candidate in place. Start its helper under **Settings →
On-device ADB → Connect and start**, then reconnect the glasses. Check the
phone menu, mode selector, Back and Home, plus tapping, dragging and scrolling
in Spatial Mouse and both trackpad orientations. Leave and return to the
controller to exercise mask recreation. Open and dismiss an app keyboard
and confirm pointer control returns afterward. Confirm the glasses' own
app selection separately from phone-toolbar interaction.

If the controller remains blocked, collect evidence while the failing
controller is still foregrounded, before opening phone settings. A system
input/window dump at that point is needed to distinguish window interception
from Unity input suppression or a helper injection failure. This archive's
settings-time dumps cannot settle that distinction.

## Validation and candidate

**120 Android instrumentation assertions pass on an isolated Android 13 / API
33 AOSP emulator.** The regression reproduces a blocked phone tap beneath the
original zero-sized overlay, then verifies taps and drags reaching the activity
beneath the corrected mask across two attachments. It checks retained mask
focus, unchanged geometry and IME focus flags, and focus recovery after removal.
The existing actual-DEX keyboard classification, Android IME show/hide and
separate-process pointer-flag checks also pass. A fixture `InputMethodService`
provides a real keyboard window because this minimal test image has no bundled
IME; the initial missing-keyboard failure is retained separately.

Eleven host regressions pass across input compatibility, controller-mode,
Home-button, Presentation-patch and corresponding-source packaging checks.
These include exact DEX restoration and rejection of duplicate or unrelated
edits. The complete APK scope audit passes, including the full DEX restoration
chain, class uniqueness, package identity, native patch scope and bundled
corresponding sources. The signed APK passes signature and 16 KB alignment
checks; its signing certificate matches public 0.2.3, allowing an in-place update.

The candidate is **0.2.4 alpha, build 2963** at
`artifacts/unbound-launcher-honor-touch-2963/the-unbound-launcher.apk`, SHA-256
`46ad820fcb4116b6f09bb4dac30188e552de9c04cc68d59eec697e738bb563a0`.
It is built from the current working tree, including the development changes
already present before this investigation. Validation logs and the runtime
report are under `artifacts/honor-magic6-touch/`.

**Honor / Android 17 / glasses verification remains pending.** No production
APK was installed on a physical phone. Emulator touch routing and keyboard
checks do not establish the state of Unity's input dispatcher, the glasses'
rendered pointer or shell input injection on the reporter's device.

Reproduce the focused checks with:

```sh
python3 -m unittest discover -s tools -p 'test_input_compat.py' -v
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-honor-touch-2963
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-honor-touch-2963/the-unbound-launcher.apk
python3 tools/verify_input_compat.py \
  --apk artifacts/unbound-launcher-honor-touch-2963/the-unbound-launcher.apk \
  --serial emulator-5580 --output artifacts/honor-magic6-touch/input-runtime
```
