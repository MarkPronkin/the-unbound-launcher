# My Glasses to OnePlus 15 — investigation, 2026-10-25

## Status

**A working experimental ADB-assisted port is installed and tested on the OnePlus.**
The user confirmed AR Space, simultaneous AIDA64/browser windows, phone-pointer
clicks and scrolling, smooth close/reopen, anchored and smooth-follow modes, and
disconnecting/reconnecting the glasses. The final helper regression passed all
checks, and no new crash was captured during the final hardware tests.

The port needs its companion and ADB-started helper; it is not a standalone
production release. The initial offline assessment below is retained as background.
See [user validation](oneplus-user-validation.json) for confirmed scope.

## OnePlus measurements and implementation progress

- OnePlus 15 / CPH2747, Android 16 / API 36, security patch 2026-09-01.
- ARM64-only ABI; **4096-byte** memory pages. The 16 KB ELF concern is not a current
  blocker on this device configuration.
- Bootloader reports locked. No root commands were used; the helper runs as UID
  **2000**, while modified My Glasses runs as UID **10638**.
- Ordinary application requests for trusted/secure displays are denied.
- ADB shell has `ADD_TRUSTED_DISPLAY`, `INJECT_EVENTS`, and normal capture rights,
  but lacks `CAPTURE_SECURE_VIDEO_OUTPUT`.
- Trusted non-secure display masks **1033** and **1481** both rendered the fixture
  and produced visual feedback from an injected tap. The original **1485** mask
  was rejected because it requests secure capture.
- A second test used My Glasses 2.2.2's exact `LaunchConfig` and input Binder
  transactions from normal app UID **10637**. It rendered 26 frames, saw the
  expected fixture and tap-feedback pixels, and confirmed primary-display input
  rejection. See [final contract report](oneplus-15-bridge-contract-final.json).
- The actual patched My Glasses process connected to its replacement
  `LaunchService` and received descriptor
  `ai.nreal.nebula.service.ILaunchManager`. See
  [in-app service result](oneplus-myglasses-bridge-status.json).
- The detached helper survived ADB transport reconnection and the physical
  phone-to-glasses cable swap. It remains an ADB-started process and needs
  restarting after reboot or companion-app updates.

The adapter preserves the original launch Binder contract, delegates app launching
through a shell-owned context, removes the secure display bit, and restricts input
to its managed app displays. A certificate-checked local provider supplies the
Binder to the port; there is no listening network service. The original platform
shared UID is removed. Five audited Java OTA/boot-mode entry points return failure,
and the firmware-cache initializer is disabled in the test build.

The app's normal Internet permission was restored after the deliberately offline
startup candidate hit an uncaught DNS/security error. That artificial failure is
not evidence of OnePlus SDK incompatibility. The user enabled the required overlay
permission in OnePlus settings after OxygenOS denied changing it through ADB.

### First glasses-connected test

The user reported that the interface appeared, but clicking apps returned them to
My Glasses on the phone. The helper log shows Play Store and AIDA64 launch requests
were executed on virtual displays. My Glasses' `:space` process then crashed in
`EvaProHelper.setFlagNotFocusable` because it attempted `Settings.Global.putInt`
without `WRITE_SECURE_SETTINGS`. Disconnecting also crashed the main process in
`SystemVidPidConfig.fixHelenSystemPlugOut` while writing `Settings.System` flags.

These are Beam-specific keys (`xreal.flag.not_focusable`, navigation/launcher flags,
and XREAL USB VID/PID/connection flags). The compatibility implementation redirects
only the audited helper classes' accesses to certificate-protected, app-local
state shared through the companion provider. It does not grant the app broad system
settings permissions or write those flags into OxygenOS global settings.

Raw device logs are retained in `artifacts/port-bootstrap/glasses-test-1/`.
New source is under `bridge/`, `port-client/`, and `probe/`; build and helper
management commands are in the [development log](../DEVELOPMENT_LOG.md).

### Black display investigation with wireless ADB

With the settings adaptation installed, the user saw the phone trackpad but no
glasses image. Wireless ADB now permits live inspection with the glasses attached.
Android detects XREAL One as a 3840 × 1080 / 60 Hz external display. The app starts
its `NRFakeActivity` there, but the captured output is black.

The live startup log shows `NRFakeActivity.onPause` pausing Unity while that
secondary-display activity is still visible. Unity subsequently delivers
`OnApplicationPause(true)` while the SDK is initializing. The SDK pauses the
glasses MCU connection; subsequent tracking configuration and display-information
requests fail. This sequence is a concrete candidate cause, not yet proof that it
is the only rendering problem. Attempting to resume this already-invalid native
session caused a native SIGSEGV, so retrying it is not a recovery strategy.

The next candidate retains Android's `Activity.onPause` callback but defers the
Unity suspension to the original `NRFakeActivity.onStop`. Its original start and
resume paths remain intact. Optional Bluetooth input discovery also returns empty
results if Nearby Devices permission is denied, instead of repeatedly throwing a
Unity Java exception. This grants no additional permissions.

The fixed startup now renders the AR Space tutorial and AIDA64. The user confirmed
that AIDA64 opened, and an external-display screenshot independently shows its
stereo window. Logs report a successful switch to `MODE_3DOF`. This is startup and
rendering evidence; it does not establish the quality of every tracking mode.

Closing AIDA64 at 09:05:31 released its virtual display, then Unity sent a focus
touch to physical glasses display 26. The helper correctly refused input outside
its managed windows, but did so by throwing `SecurityException`. The original
client does not handle that runtime exception, and its pending JNI exception
caused a native SIGABRT. The fix preserves the input restriction and returns the
normal AIDL boolean `false`, without forwarding the rejected event. Invalid
caller certificates still raise a security error. The regression probe now checks
the physical display and late input after fixture closure as well as normal
fixture rendering and taps. All checks passed on the OnePlus with the updated
helper, including rendered frames, visual tap feedback, display removal after
closing, and exception-free rejection of primary, physical-glasses, and retired
display input. The user then confirmed real app closing and reopening worked,
but reported a 1–2 second freeze. The helper ownership check called the original
`findActivityContent`, which retries absent displays 50 times with a 10 ms sleep.
Four focus touches during closure therefore caused about two seconds of blocking.
Using the original immediate `findApp` ownership lookup removes those retries
without changing which displays may receive input. The revised regression passed
with 23 rendered frames; unmanaged and retired-display input round trips measured
**0.139 ms** and **0.310 ms**. See [close regression](oneplus-15-close-regression.json).
The user confirmed closing is now smooth. They also confirmed AIDA64 and a browser
remain open together, with working clicks and scrolling in both. The live log
shows the close focus sequence completing in **33 ms** and AIDA64 reopening in
the same spatial process, without a new crash. See
[user validation](oneplus-user-validation.json).

The incremental update was removed by Android after installation. A full
`--no-incremental` reinstall succeeded after the user approved installation, but
reset app permissions and changed its UID to **10639**. The user restored overlay
and USB access. The fresh UID still connected successfully to the existing
certificate-checked launch helper. Use full installs during this investigation.

Build identity and raw runtime evidence are retained locally in
`artifacts/port-bootstrap/glasses-live/`. The user has now confirmed multiple windows, clicks and scrolling, tracking
modes, smooth closing, and reconnect behavior.

The target experience is floating Android app windows, the phone pointer, and
anchored/smooth-follow viewing on XREAL One. Merely installing ControlGlasses
does not reproduce this full experience.

## Working reference

- Device: XREAL Beam Pro / X4000, Android 14 / API 34.
- Firmware: `X4000_X502_260518_ROW`, security patch 2026-05-01.
- Spatial app label: **My Glasses**.
- Package: `com.xreal.evapro.nebula`, version **2.2.2**, version code **2873**.
- Manifest: `android:sharedUserId="android.uid.system"`, min API 29, target API 33.
- Installed identity: UID **1000**, updated system app.
- APK size: **1,034,035,872 bytes**, including the Unity scene/runtime and native libraries.
- Original APK SHA-256: `c509b9790dde9518012d536b478091e22c4a48a4ff32bf7535274fe62bc8a3ce`.
- Seven XREAL-related installed APKs were copied successfully, including all
  reported splits; the collection recorded zero failed commands.

Original APKs and detailed device evidence are retained locally under
`reports/devices/beam-pro-20261025T055332771855Z/`. Decompiled selections, exact
smali, framework JARs, manifest dumps, and ELF analysis are under
`artifacts/beam-pro-system/`. These directories are excluded from version control.

## Dependencies established from the extracted code

| Function | Implementation in My Glasses | Implication for a port |
| --- | --- | --- |
| Android identity | System shared UID and extensive privileged permissions | A rebuilt normal app cannot inherit these privileges just by requesting them. Removing the shared UID changes the privilege model. |
| Floating app windows | `LaunchManager` creates virtual displays with mask **1485 / 0x5CD**, registers a task-stack listener, starts a proxy activity on each display, and passes app surfaces/buffers to the renderer | The mask includes **PUBLIC, SECURE, OWN_CONTENT_ONLY, SUPPORTS_TOUCH, ROTATES_WITH_CONTENT, DESTROY_CONTENT_ON_REMOVAL, TRUSTED**. Capture and trusted-display capabilities must be replaced or supplied by an appropriate privileged component. |
| Pointer input | `PrivilegeManager` sets the display ID on an `InputEvent`, then calls `IInputManager.injectInputEvent` | Requires input-injection privileges. A user-enabled accessibility service is a possible touch-gesture replacement, with different semantics and limitations; it has not been implemented or tested here. |
| Mirror mode | `MirrorManager` requests display mask **21 / 0x15** | Includes AUTO_MIRROR and SECURE. A public MediaProjection path requires user consent and has content and lifecycle limitations. |
| Orientation | `DisplayOrientation` probes custom `lockDisplayOrientation(int,int,boolean)` methods | Already has a graceful missing-method path. A portable implementation needs a supported fallback for per-window orientation. |
| Recording | `XrealDisplayManager` directly calls `MediaProjectionManager.setDisplayToRecord(int,boolean)` | This is a custom framework interface. A normal public capture flow is not an equivalent drop-in implementation. |
| Display detection | `BaseDisplay` uses the custom Eva observer only for `Build.PRODUCT == "eva"`; otherwise it uses `NativeDisplay`. `DisplayModel` also uses standard product information on API 31+ | Confirmed on this OnePlus: standard detection identifies XREAL One at 3840 × 1080 / 60 Hz, and the glasses render AR Space. |
| Scheduling | Framework `semprivilege` service exposes `setThreadSchedulerFifo` | This particular service is about realtime scheduling, not the floating-window compositor. Missing it alone does not prove the full port impossible. |
| Screen-power integration | `BrightnessService` loads system-server display code and uses SurfaceControl display-power methods | Must be disabled or replaced in a normal app. It is not essential to initial spatial rendering. |

The native dependencies inspected for `libnr_api.so`, `libnr_service.so`,
`libXREALXRPlugin.so`, `libgraphics.so`, and `libnative-lib.so` name public Android
libraries and/or packaged XREAL libraries. This is encouraging but is **not**
proof of general binary portability. Runtime tests now establish the core
display, tracking, and app-window paths on this phone/glasses combination; other
OS, firmware, GPU, and device combinations remain untested.
The optional native manifest dependency `libnr_api.xreal.so` is also present.

## Native memory-page compatibility

The original My Glasses APK contains **77 native libraries**. **23** have one or
more ELF LOAD segments aligned below 16 KB, including `libHardwareBufferTexture.so`,
`libgraphics.so`, and `libnative-lib.so`. Core `libnr_api.so`, `libnr_service.so`,
and `libXREALXRPlugin.so` have 16 KB-aligned LOAD segments in this build.

The official ControlGlasses 3.1.0 reference has 15 native libraries; 13 have
sub-16-KB LOAD alignment. Blindly replacing the newer Beam Pro runtime with these
libraries would introduce another compatibility risk, as well as ABI/version
coupling.

Beam Pro and this OnePlus both report a **4096-byte** memory page size.
Alignment findings identify a test requirement; they do not establish
that loading will fail, because OS compatibility behavior also matters. Changing
ZIP alignment alone does not fix ELF LOAD alignment.

Full results: `artifacts/beam-pro-system/native-dependencies.json`.

## Initial Beam Pro diagnostic results

`artifacts/probe/beam-port-probe.apk` is **Beam Port Probe**, package
`dev.beampro.portprobe`. It has its own local debug signature and runs under an
ordinary app UID. It contains no XREAL runtime or proprietary app assets and has
no network permission. It does not modify firmware, request root, change settings,
or capture other applications. It briefly creates and releases test displays and
can launch its own fixture activities.

The probe tests both the original privileged configuration and ordinary display
alternatives, records actual exceptions, and checks rendered fixture pixels.
See the saved device report for measured outcomes. A `NoSuchMethodException` in
a reflection check can mean hidden-API enforcement as well as an absent method;
it must not be treated as proof that Beam Pro lacks the method.

Building and signature verification passed. The APK was installed and launched
successfully on Beam Pro. This validates the probe on that device, not the port
or the OnePlus. The probe remains installed as a separate app.

Final measured report: [beam-pro-probe.json](beam-pro-probe.json).

| Check | Final Beam Pro result under ordinary UID 10235 |
| --- | --- |
| Realtime page size | 4096 bytes |
| Requested privileged permissions | All seven checked permissions denied |
| Trusted display, mask 1033 | Rejected: `ADD_TRUSTED_DISPLAY` required |
| Secure display, mask 13 | Rejected: secure video capture permission / appropriate projection required |
| Original My Glasses display mask 1485 | Rejected: secure video capture permission / appropriate projection required |
| Own presentation and fixture rendering | **Skipped**: device reported non-interactive and locked during the final run |

The probe waits for display-added events before attempting to show test content.
It records wake/lock state and skips rendering controls when their prerequisites
are unmet. Earlier attempts to render on a sleeping device are not evidence that
normal virtual-display rendering is unsupported. The stored screenshot was black
with the display asleep, so visual layout verification remains pending.

The test results confirm the privilege boundary but do not establish a working
unprivileged compositor. No glasses were attached during these host-USB tests.

## Remaining work

1. Track further concrete lifecycle, native runtime, or framework failures from
   actual device logs. Preserve the original surface/Binder contracts.
2. Broader validation remains for drag gestures, rotation, background/resume,
   complex Intent launches, and additional applications.

The current evidence supports a **compatibility-layer investigation**, not a
promise that an unmodified or lightly resigned APK can provide the complete
Beam Pro experience on stock OxygenOS.

## Primary documentation

- [XREAL ControlGlasses architecture](https://docs.xreal.com/Tools/ControlGlasses)
- [XREAL supported host devices and Android 16 notice](https://docs.xreal.com/XREALDevices/Compatibility)
- [Android virtual displays and permissions](https://developer.android.com/reference/android/hardware/display/DisplayManager)
- [Android activity embedding attribute](https://developer.android.com/guide/topics/manifest/activity-element#embedded)
- [Accessibility gesture display selection](https://developer.android.com/reference/android/accessibilityservice/GestureDescription.Builder#setDisplayId(int))
