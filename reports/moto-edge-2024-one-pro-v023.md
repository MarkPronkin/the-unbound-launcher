# Moto Edge 2024 / XREAL One Pro: protected app-window failures and frame degradation

The wearer reports that **AR Space works after disabling Motorola Smart
Connect**, with Chrome, Google, YouTube Music and Reddit usable. YouTube,
Brave and Firefox reportedly fail to open or render, and the visuals feel
laggy. `unbound-debug.zip` identifies **motorola edge 2024, Android 15 / API
35, Unbound 0.2.3 alpha build 2957**, with **XREAL One Pro (SDK model 6)**.

**The captured connection reaches native stereo rendering. Opening Brave
selects a protected overlay, followed by repeated ImageReader acquisition
warnings and two crashes in Unbound's auxiliary protection process.** This
is the leading rendering-path issue for the captured Brave attempt. The
archive does not establish the cause of the YouTube or Firefox failures,
or that Smart Connect caused the earlier startup problem.

## Candidate correction, build 2964

`AppSurfacePolicy` now requires both GPU color support and a working local
Widevine session before enabling the SDK's optional protected app overlays.
The Moto capture's `NotProvisionedException`, unsupported DRM, a busy or
broken plugin, and session cleanup errors select the existing app-copy
route. The check opens and closes a temporary session and releases its
`MediaDrm` instance. It makes no provisioning or license requests and does
not change DRM security levels. Android documents provisioning failures
as an outcome of [`MediaDrm.openSession`](https://developer.android.com/reference/android/media/MediaDrm#openSession()).

Copy routing is published **before** the check starts. A failure,
interruption or the existing 1.5-second deadline leaves that route in
place. Only the startup caller publishes the completed result: a slow
worker may finish and clean up later, but cannot enable overlays during
the AR session. Phones whose GPU and DRM checks succeed keep their
existing overlay route. This applies by capability, without a Motorola
allowlist.

The existing six native app-open/change hooks and immersive-mode hook
already consume this private flag. Selecting copies clears the SDK's
`AppInfo.isProtected` routing hint, which avoids creating the protected
quad and starting its auxiliary codec for the captured kind of app open.
Android window security, the helper's virtual-display flags and the
compositor binary are unchanged. This targets ordinary app UI; it does
not add protected-video playback support.

This is a **test candidate**, with Moto/One Pro verification pending. The
captured DRM failure motivates the fallback, but it does not prove that
every reported launch failure has that cause or that the change removes
the idle-session lag.

The signed candidate is **0.2.4 alpha, build 2964**:
`artifacts/unbound-launcher-moto-render-2964/the-unbound-launcher.apk`.
SHA-256:
`d33d211cdf231fcd9135de19b5308abdf470e4a2a432608ad2e450688d70ea94`.
Install it in place, start its helper under **Settings → On-device ADB →
Connect and start**, then reconnect the glasses with Smart Connect still
disabled. Installing the update starts a fresh app process, which reruns
the readiness check. No production APK was installed on a physical phone.

Validation evidence is under `artifacts/moto-edge-2024-onepro-023/`:

- **19 host regressions pass**, including 109 Java policy assertions for
  the captured missing-provisioning condition, plugin/constructor/resource
  failures, cleanup, stale flags, deadlines and interruption. The native
  tests execute all six app-routing entries and immersive routing across
  nine file states, retaining exact patch restoration and register checks.
- **18 Android assertions pass** on an isolated API 33 ARM64 emulator,
  loading the policy directly from the candidate's `classes4.dex` under an
  ordinary app UID. Real EGL/MediaDrm calls select copies on that emulator;
  Android AtomicFile publication, failure and late-completion handling pass.
  The fixture has no Internet permission and is removed after the test.
- The APK's **36 copy-shader fragments** pass **262,656 RGB component
  checks** with zero maximum error. Missing-decode and double-decode
  controls both expose errors. This verifies the copy material on the
  emulator, not the Moto GPU or glasses output.
- Compared with the existing 2963 development candidate, every native
  library and Unity payload is identical, and the helper's logical files
  are identical. The client DEX changes only `AppSurfacePolicy`, its
  capability interface and two generated lambda classes. This candidate
  includes the development changes already present in that earlier build.
- APK signing and 16 KB alignment pass. The signing certificate was
  checked directly against public 0.2.3 / build 2957 and matches it.
  The bundled readiness source matches the runtime candidate.
- The complete APK scope audit passes, including exact DEX restoration,
  unique classes, native patch scope, package identity and corresponding
  sources. The build, audit and both Android fixtures identify the same
  APK checksum. Results are consolidated in
  `candidate-verification-2964.json` beside the retained evidence.

Reproduce the focused checks with:

```sh
python3 -m unittest discover -s tools -p 'test_app_surface_routing.py' -v
python3 -m unittest discover -s tools -p 'test_universal_color.py' -v
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-moto-render-2964/the-unbound-launcher.apk
python3 tools/verify_app_surface_runtime.py \
  --apk artifacts/unbound-launcher-moto-render-2964/the-unbound-launcher.apk \
  --serial emulator-5580 --output artifacts/moto-edge-2024-onepro-023/surface-runtime
python3 tools/verify_app_color_runtime.py \
  --apk artifacts/unbound-launcher-moto-render-2964/the-unbound-launcher.apk \
  --serial emulator-5580 --output artifacts/moto-edge-2024-onepro-023/color-runtime
```

## Retained evidence

Times below are October 31, 2026, in the phone's EDT log time (UTC−4).
Line numbers refer to the unchanged archive entries extracted under
`artifacts/moto-edge-2024-onepro-023/export/`.

- `glasses-startup.txt:34–35`: firmware **1.9.1.20260914175625** matches
  the pinned stable recommendation and is accepted without an override.
  At line 171 the SDK returns result 0; lines 250–251 record committed
  controller/glasses frames and the ready state.
- `renderer-geometry.txt`: the connected physical display is **43,
  3840 × 1080 / 60 Hz**. Unity reaches one render pass with two eye
  parameters and texture-array slices 0 and 1. At full scale, the eye
  target is 1968 × 1134; the degraded sample is 1574 × 907 at scale 0.8.
  The disconnected final device summary's glasses type −1 does not
  describe the earlier working session.
- `app.log:7974,8028,8168,8189–8194`: Brave is clicked at 13:40:17.486,
  requests a 1920 × 1080 app window, and receives an
  `onActivityLaunched` callback with display **44** and `true`.
  This callback and display creation do not prove the app drew a frame;
  the shell launch result and Brave's own process logs are absent.
- `app.log:8150–8166`: the SDK creates an app surface, starts its
  `Acquire-p` reader thread, creates a quad layer, sets persistent
  protection, and emits `OnProtectedCodecCallback: True`.
- `app.log:8974–12947`: **620** `ImageReader_JNI` warnings occur in the
  renderer process **17791**, thread **21337**, from 13:40:18.532 through
  13:40:33.814: `Unable to acquire a buffer item`. The warning text
  suggests exceeding `maxImages`; it does not independently prove a
  particular image leak. These warnings start **before** either crash.
- `app.log:8960–8963,9121,9231–9233,9253–9255,9362–9365`: Unbound's
  `:remote_protect` process **21340** starts `ProtectedCodec`, fails
  `MediaDrm.openSession` with `ERROR_DRM_NOT_PROVISIONED`, then crashes
  at 13:40:19.656 with `MediaDrmStateException` /
  `ERROR_DRM_GENERIC_PLUGIN` from `getProvisionRequest`, called by
  `ai.nreal.protect.session.drmRequester$1.run`.
- `app.log:9372,9503–9506,9630,9761–9763,9870–9872`: the service
  disconnects, is recreated in process **21499**, and repeats the same
  failure at 13:40:22.042. These are Unbound process crashes, not evidence
  that Brave itself crashed.
- `app.log:7724–7767,10405–10448,12476–12519`: the load monitor selects
  the **45 fps / 0.8 scale** profile at 13:40:14.995 after detecting
  slow frames, restores **60 fps / full scale** at 13:40:25.012, then
  degrades again at 13:40:32.709. These are requested rendering profiles,
  not measured sustained output frame rates. The first degradation
  precedes the Brave click. Six SDK `frame missing count` messages also
  occur during the app-window interval.

## Code trace and interpretation

The retained stock `ProtectSessionService.onCreate` starts
`ProtectedCodec` with a null surface. `ProtectedCodec` opens the bundled
`checkProtect.mp4`, attempts its own Widevine session and, after
`NotProvisionedException`, calls `drmRequester.provideProvision`.
This is an auxiliary SDK session, separate from any app's own playback
or content license. See the [protected playback investigation](crunchyroll-spatial-drm.md).

Single-class extraction from the pinned original primary DEX confirms
that `drmRequester.provideProvision` starts a worker whose
`mediaDrm.getProvisionRequest()` call has no exception handler. Its
`IOException` catches surround later HTTP operations. The captured
failure happens while generating the local provisioning request, before
that worker reaches its HTTP call. Changing Wi-Fi or blaming a remote
license server is therefore not supported by this stack trace. The
extracted class and decompiler log are retained beside the export.

The renderer's acquisition warnings and the auxiliary codec's crashes
are distinct failures: they occur in different processes, and the
warnings begin first. Their shared protected-overlay context makes
that route a useful investigation target, but the archive cannot prove
that catching the provisioning exception would restore Brave's picture.

The GPU identifies as **Adreno 710** and advertises
`GL_EXT_texture_format_sRGB_override` (`app.log:2479,2512`). In the
reported build, `AppSurfacePolicy` tests for the absence of that extension;
it does not test DRM readiness. The captured attempt selects the overlay.
That explains why the earlier Pixel/Mali color fallback did not cover
this failure. Build 2964 extends the prerequisite check while keeping the
same copy implementation and Android secure-content restrictions.

## Limits and next checks

Only **one app-icon click and one launch request, both for Brave**, occur
in this collection. YouTube and Firefox appear in app enumeration, and
an existing YouTube task appears in a later system dump, but neither has
a captured launch attempt. The report's working-app list and the Smart
Connect workaround remain wearer observations. No controlled Smart
Connect comparison, thermal trace, CPU/GPU timing breakdown or optical
measurement was supplied. The frame degradation is real; attributing it
to SoC incompatibility alone is premature. Debug collection and the
repeated warning/crash activity are additional unmeasured workload.

For a retest, keep the reporter's working Smart Connect configuration,
enable collection before entering AR Space, and open each failing app
separately from a fresh session. Retain a known working app as a control.
For each failure, collect helper launch results, system activity/window
state while the app window is still open, and the target app's crash
logs. Then distinguish placement failure, missing app frames and a
protected compositor stall. An idle-session performance sample without
collection is also needed to separate baseline lag from debug overhead.

For build 2964, first verify `app_surface=copy` and
`protected_app_session_ready=false` after a fresh app start on the affected
phone. A timeout instead reports `capability_complete=false` and also
retains copies. Reopen Brave and check that its ordinary UI draws, accepts
input, survives closing/reopening and entering/leaving immersive mode.
The previous `OnProtectedCodecCallback: True`, `:remote_protect` crashes
and acquisition-warning flood should not recur for that window. Test
YouTube and Firefox separately, then Chrome as a regression control.
Check lag before and after enabling collection. Keep the existing Smart
Connect workaround for this test; the candidate does not change it.

## Disposition

Archive entries were checked byte for byte against the ZIP, and
launch/crash/warning counts were verified from the retained log.
ZIP SHA-256:
`a504fa161f866d11f12dc7425606b60e7c4f41a7854c86917fb68bff52f0b200`.

Suggested reply to the reporter:

> Thanks for the report and logs. They confirm that your Moto reaches AR
> Space with native stereo output. When Brave opens, the logs show a
> failure in Unbound's protected rendering path, including crashes in an
> auxiliary DRM service. They also confirm rendering slowdowns, though
> we can't yet attribute those to the chipset. We've recorded the Smart
> Connect workaround and the affected apps. The capture contains a Brave
> attempt; separate captures of YouTube and Firefox would help check
> whether they hit the same issue. A test build now uses a compatible app
> rendering path when the DRM readiness check fails. It still needs a
> retest on your phone.
