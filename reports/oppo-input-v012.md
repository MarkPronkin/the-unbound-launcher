# OPPO CPH2671: invisible pointers, blocked selection and persistent Enter prompt

The supplied `unbound-debug-20261028-002945-d0a4ab98.zip` is from **0.1.2
alpha / build 2924**, OPPO CPH2671, Android 15 / API 35. It reports working
launcher/menu access, trackpad hover and spatial-mode paging, but no visible
pointer or selection in either mode, plus an Enter prompt that stays visible.
Analysis and runtime evidence are retained under `artifacts/oppo-input-2945/`.

## Confirmed keyboard-state defect

At 00:27:33.013, `InputDetectService` observes a root height of **2616 px**
and a visible bottom of **2571 px**, with top zero. The original detector
treats any bottom gap over **10 px** as a keyboard. This **45 px** system-bar
gap therefore sends `onInputKeyboardShow` to Unity before normal interaction.
At 00:27:33.205, Unity logs `KeyboardDeviceHelper:OnInputKeyboardShow` and
`XRInput:set_RaycastersActive(False)`. The false keyboard state lasts until
00:29:22.799, then returns at 00:29:25.828.

This is more than a missing cursor image. The existing native
`VirtualControllerTouchDispatcher.OnInputKeyboardShow` sets `mKeyboardShow`.
Its `FillTouchpadInput` then returns before either Spatial Mouse or trackpad
gesture handling, discarding down, move and up events. A host regression
executes those exact ARM64 methods from the reported public APK: all three
events are suppressed in both modes while the flag is set; after the existing
hide callback, input reaches each mode's original handler again. The log also
records repeated focus/recenter attempts while the wearer tries to select.

`KeyboardCompat` uses the public root-window **IME visibility** type on Android
11 and newer. Navigation bars, cutouts and window geometry no longer establish
keyboard presence. A detached root is treated as having no visible IME. The
Android 10 fallback excludes stable bottom bars and requires a keyboard-sized
gap; it is outside this report's tested Android version.

`PatchInputCompatDex` replaces only the detector's boolean calculation. Its
replacement occupies the same 17 DEX code units and preserves registers,
callback/state-transition logic, branches, try ranges and debug addresses.
Actual keyboard show/hide events continue to use the original callbacks.

## Confirmed pointer-flag defect

The log records five `WRITE_SECURE_SETTINGS` permission failures from
`com.xreal.evapro.InputHelper.hidePointerIcon`, beginning at 00:27:57.197.
Startup also reads the absent system setting `xreal_hide_pointicon` in
`InputHelper.isPointerIconHidden`. The prior controller-mode correction
handled the `JobService` write, but these direct helper accesses remained.

The new patch routes both helper accesses through `SettingsCompat`, sharing
the same certificate-protected private flag provider already used by the
controller service. An unset flag defaults to visible, matching the original
helper's fallback. No secure-settings permission or system-setting mutation
is required. The original direct/remote request routing stays intact.

## Validation and test candidate

The original build reproduces both defects in an isolated Android 13 emulator:
the exact reported geometry emits a keyboard-show callback with the IME
hidden, and the actual pointer-helper write throws `SecurityException`.
That reproduction passes six assertions. The candidate fixture exercises the
APK's unmodified detector, helper and service DEX, with the production Java
adapters and a separate-process private provider under a fixture authority.
It covers synthetic system-bar/cutout/IME cases, real Android keyboard
opening and closing, and repeated direct/remote pointer visibility changes.
The signed build passes **92 Android assertions**, including 24 direct/remote
pointer changes without `WRITE_SECURE_SETTINGS`, with the real global flag
unchanged. The fixture waits for its editor to become the served input view
before requesting the IME; an initial fixture attempt requested it too early
and Android ignored that request. That fixture failure is retained separately
under `fixture-initial-failure/`; no production change was needed for it.

Host checks verify exact DEX restoration and reject duplicate or unrelated
changes. Existing controller-mode, source-archive and version checks pass.
The existing Android controller-service regression also passes **149 assertions
over 36 transitions** with build 2945; both temporary fixture packages are
removed afterward. The launcher was not installed on the connected Pixel.
The complete signed-APK audit passes: the builder and independent auditor
apply and reverse the new corrections as part of the existing patch chain,
verify corresponding sources, preserve original permissions and confirm
unique classes, signing, alignment, package identity and native patch scope.

The signed candidate remains **0.1.2 alpha**, Android build **2945**, at
`artifacts/unbound-launcher-oppo-input-2945/the-unbound-launcher.apk`.
Consolidated results and the artifact hash are recorded in
`artifacts/oppo-input-2945/validation.json`.
Its SHA-256 is
`b14fdfa16741bd4c6442757e42dfcad9ee5387ff65c2a7c7ab3d31c7038593f4`.
Its signing certificate matches public 0.1.2 / 2924, allowing an in-place update.

**Physical OPPO/glasses confirmation is pending.** Emulator and native host
checks do not verify the rendered AR image, phone tracking, application launch
or every reported gesture on this hardware. Update in place, reconnect the
glasses, and check visible pointing, app selection, settings selection and
paging in Spatial Mouse and both trackpad orientations. The Enter prompt
should appear only when a real keyboard is open. If a symptom remains, export
a fresh debug ZIP while the glasses are connected and the problem is present.
