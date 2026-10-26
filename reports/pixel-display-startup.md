# Pixel 8 Pro stereo display startup — 2026-10-26

The saved logs identify Android 17 / API 37, launcher 0.1.2 (2876), and XREAL One.
The Pixel was not contacted or modified while developing the 0.1.3 candidate.

## Why VGA becomes the default

At 03:01:48.971, Android adds the mono display at 1920 × 1080. After the mode
switch, at 03:01:59.563, it adds a new display whose active and default mode is
640 × 480, while 3840 × 1080 at 60 Hz is advertised. `userPreferredModeId=-1`
shows no matching user preference. The later 850 × 480 mode is Android's generated
anisotropy correction, not native stereo output. See the raw-log excerpts in
`artifacts/pixel-display-logs-20261026/`.

Google's published [external-display driver](https://android.googlesource.com/platform/hardware/google/graphics/common/+/64372d59364118b64feaa7c09f1bea988134bbe0/libhwc2.1/libexternaldisplay/ExynosExternalDisplay.cpp)
defaults to a relaxed 1920 × 1080 / 60 Hz lookup. The corresponding
[lookup implementation](https://android.googlesource.com/platform/hardware/google/graphics/common/+/64372d59364118b64feaa7c09f1bea988134bbe0/libhwc2.1/libdevice/ExynosDisplay.cpp)
falls back to a smaller resolution; 3840 is wider than its requested limit.
Compiling that original function locally with the logged mode lists reproduces
1920 × 1080 for mono and 640 × 480 for stereo. This strongly explains the logged
choice, although the exact driver binary on this Android build has not been
matched to that published revision.

The original app accepts the display default. Its deferred renderer startup also
waits just five seconds and only reacts to a display-added event. Android disables
each newly connected display until consent; the logs show the repeated prompt
around the mono-to-stereo reconnect. The retained 03:05 startup occurs after the
glasses disconnect, so it does not prove the earlier glasses-connected timeout.

## Local app fix

`DisplayStartupCompat` requests an advertised 3840 × 1080 / approximately 60 Hz
mode through `WindowManager.LayoutParams.preferredDisplayModeId` only on the
SDK-identified XREAL renderer window when it starts in 640/850 × 480 fallback.
It keeps that request while visible, re-reads mode IDs after display changes,
and releases its own request on stop or loss of the XREAL display. Existing SDK
requests and working modes are preserved. No shell display command, persistent
preference, firmware modification, or extra permission is added.

Both existing-display and deferred startup now wait up to 45 seconds, handle
added/changed events, re-query displays, respect Android's display-access check,
and cancel on owner destruction or successful launch. Only a successful launch
sets readiness. The renderer requests a fullscreen task on its target display;
other tasks and the phone display retain their original options. Android's
consent and desktop/mirror selection remain under user control.

Two scoped DEX hooks restore byte for byte to the pinned baseline. The original
device-specific glasses configuration selector and all native libraries remain
unchanged. The candidate is `artifacts/unbound-launcher-pixel-display/the-unbound-launcher.apk`.

## Validation limits

Host tests exercise the actual Java adapter with deterministic Android test
boundaries, including delayed consent, rejected launches, changing mode IDs,
window ownership, destruction, and timeout cleanup. APK verification checks
signature, alignment, unique DEX definitions, manifest attributes, original
permissions, native/resource preservation, and exact DEX restoration.

The full host suite encountered the existing Unicorn ARM64 emulator SIGILL
failure in the unchanged app-menu tests, also recorded for 0.1.2. The remaining
suite runs separately; counts and results are saved with this candidate.

No hardware test or installation of this candidate has been performed. Android
can override window mode preferences; physical 3840 × 1080 output, rendering,
tracking, pointer input, and the chooser path still require a wearer test.

## Subsequent 0.1.3 wearer test: Mirror blocks renderer startup

The user subsequently installed 0.1.3 and reported no glasses image and no normal
trackpad after Enter AR Space. Read-only logs saved in
`artifacts/pixel-display-013-20261026/` confirm installed code 2877 and a successful
shell-helper startup. The user confirmed selecting **Mirror** in the repeated
Android chooser.

At 07:51:55.773 (device log time), Android creates a recording session that
mirrors phone display 0 onto glasses display 13. The glasses display is recognized
as XREAL One and reaches ON, but ActivityTaskManager repeatedly rejects the
renderer: “activity launch is not allowed on a display that cannot host tasks”.
At 07:52:37.063 the 45-second wait expires. No `NRFakeActivity` creation or AR
window mode-request message appears in this retained run. Consequently this run
does not exercise the window-scoped resolution fix or establish its outcome.

This matches Android's [display content-mode implementation](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/wm/DisplayContent.java):
mirroring displays do not host tasks. AR Space's phone control flow depends on
the renderer activity reaching its lifecycle callbacks, so startup stops before
the normal trackpad handoff as well. The next wearer test must select **Desktop
in both Android prompts**, including the repeated prompt after Enter AR Space.
That grants the renderer a task-capable display. Physical mode selection and
rendering remain to be checked after that prerequisite is met.

No app was installed, started, stopped or updated by the agent while collecting
these logs. No Pixel setting was written and no log buffer was cleared.

## Subsequent Desktop-mode test: the SDK's Presentation stays at VGA

The user selected Desktop and confirmed the launcher now appears, compressed
toward the centre, with a line across the top and mismatched per-eye output.
Read-only logs are saved in `artifacts/pixel-display-013-desktop-20261026/`.
They show renderer `NRFakeActivity` starting on display 15 at 07:57:24.176. At
07:57:27.379 an SDK surface is created at **850 × 480** in a separate window;
the placeholder stops at 07:57:28.020. DisplayDeviceRepository retains **640 × 480**
active throughout the connected interval until 08:00:19.653. There is no recorded
switch to 3840 × 1080. Thus Desktop removes the task-access blocker, but the
physical stereo mode is still not achieved.

The SDK's `GlassesDisplay` owns a FrameLayout and SurfaceView, used by
`libnr_api.so` through JNI; the native library constructs an Android Presentation.
0.1.3 placed its mode request on the placeholder Activity. Its request lifecycle
does not cover the separately created SDK Presentation. Android also gives a
[Presentation its own display context](https://developer.android.com/reference/android/app/Presentation),
while the SDK creates these child views from the phone Activity context.

The 0.1.4 candidate adds a scoped constructor hook that observes attachment of
those two SDK-owned views. For an SDK view attached to an identified XREAL
Presentation window, it uses the external decor's WindowManager to request an
advertised 3840 × 1080 / approximately 60 Hz mode, fills the window at origin
(0, 0), removes system-bar insets and hides those bars. The two views share one
window session so detaching one cannot clear the other's request. The last
detach releases the owned mode request and listeners. Other displays, the phone,
non-Presentation windows and later explicit SDK mode requests remain outside
the override. No per-eye calibration or firmware value is changed.

Tests exercise the real adapter against deterministic view/display boundaries,
including the different phone/external contexts and the placeholder stopping
before the SDK view detaches. The additional DEX hook is checked by restoring
the original constructor and comparing every complete class definition. This
also handles D8/DexPool shared-pool encoding differences without accepting changes
to unrelated classes; a deliberately changed source-file attribute is rejected.
All original native libraries remain byte-identical.

Physical 3840 × 1080 output, disappearance of the top line, native viewport
adaptation and per-eye alignment remain unverified until the wearer tests 0.1.4.
The agent did not install this build or change Pixel settings.

## Live wireless inspection: 3440-pixel system cap and installed version

Read-only wireless ADB connected using the Mac's existing authorization. The
supplied pairing code was not used and no pairing was added. The capture is
`artifacts/pixel-display-014-live-20261026/`; despite that provisional directory
name, `app-package.txt` confirms the installed app is still **0.1.3 / 2877**.
Consequently this is not a hardware result for 0.1.4.

The live display is in Desktop content mode (`canHostTasks=true`, mirror flag 0),
but SurfaceFlinger scans out **640 × 480**, scaling an **850 × 480** logical
display horizontally by 0.7529. At screenshot time the external output contains
Android's desktop, not a visible stereo AR frame. The glasses advertise
3840 × 1080 / 60 Hz as a physical mode, consistent with the user's SBS report.

Crucially, DisplayModeDirector records **PRIORITY_LIMIT_MODE** with maximum
dimensions **3440 × 1600**. The framework and services JARs were copied read-only
and selected classes decompiled locally. On this exact Pixel build, the limit
has priority 14, while an app's size request has priority 8 and the user preferred
mode has priority 5. Thus the limit excludes 3840-pixel output regardless of
which ordinary app window requests it. The earlier driver-default explanation
is incomplete: the live framework cap is a separate, stronger blocker, and
0.1.4's window-only request cannot remove it.

The display-scoped `requestDisplayModes` alternative requires RESTRICT_DISPLAY_MODES
and is additionally gated on VRR support in this build; the connected glasses
do not report VRR. Shell does hold MANAGE_DISPLAYS, MODIFY_USER_PREFERRED_DISPLAY_MODE
and OVERRIDE_DISPLAY_MODE_REQUESTS. No protected setter was invoked on the Pixel.

## 0.1.5: automate routing and mode preparation in the embedded helper

The requested implementation uses the existing shell UID helper:

- The Pixel exposes `setConnectionPreference(uniqueId, 1)`, where 1 means Desktop.
  The helper saves this only for identified physical XREAL displays and handles
  all-including-disabled displays and connection events. It selects Desktop and
  enables the display without asking for the same choice after every reconnect.
- For the measured SBS EDID (3840 × 1080 advertised, no 1920 × 1080 mono mode),
  the helper applies `setUserPreferredDisplayMode(id, mode, false)`. The final
  argument prevents storing the resolution preference. Pixel LocalDisplayAdapter
  uses the preferred mode as its default, allowing preparation before rendering.
- If the target mode exceeds the live LIMIT_MODE cap, the watchdog temporarily
  sets `setShouldAlwaysRespectAppRequestedMode(true)`. This API is global, not
  per display, so the override can also affect phone mode selection while active.
  It is not a permanent resource/feature-flag patch. Thermal and lock guards stop
  management, and Android's independent external-display thermal policy remains.
- Normal cleanup, unplugging, mono mode, parent EOF and heartbeat expiry restore
  the app-mode policy and previous temporary preference. The mirror setting is
  restored on disconnect, lock or watchdog exit. A ten-second hardware-mode
  deadline rolls back unsuccessful switches; failed sessions retry at most once
  per thirty seconds. A synced recovery
  journal and exclusive OS lock handle abrupt child death and helper replacement.
  A hard kill of the watchdog can leave the temporary policy until the next
  helper recovery or reboot; the API does not offer a per-display Binder lease.
- On Android 17 the renderer waits for actual 3840 × 1080 mode before opening,
  avoiding native Presentation creation with cached VGA geometry. The existing
  SDK-view layout hook is retained; no native library or calibration is altered.

The persistent per-glasses Desktop choice is intentional and is not rolled back.
Initial ADB pairing/consent and restarting the helper after a phone reboot remain
part of the one-APK setup. The candidate is local only and has not been installed
or run on the Pixel. Host tests cover the policy, rollback, real cap fixture,
re-enumeration, watchdog STOP/timeout/failure and journal recovery. Hardware
success, chooser suppression, final output and eye alignment remain pending.

## 0.1.5 wearer test: shell display events are deferred

Read-only wireless diagnostics in `artifacts/pixel-display-015-user-test-20261026/`
confirm version 0.1.5 / 2879 installed, launch helper PID 5983 and watchdog PID
6044 alive, and `BEAM_DISPLAY_WATCHDOG_READY`. There are no display-state or
Desktop-configuration messages. Android's display dump marks both shell callback
records `cached=true`, with 11 and 6 pending display events respectively. No
XREAL connection preference was saved. The retained test creates Mirror sessions
on external displays 3 and 4 and rejects renderer tasks. The glasses were absent
from the current display snapshot, so it is not a new measurement of SBS scanout.

The exact Pixel framework shows `DisplayManager.getDisplays` using
`DisplayManagerGlobal.getDisplayIds`, whose connected-ID cache is updated by
display callbacks. DisplayManagerService defers those callbacks for cached
processes. Repeatedly querying the same client cache cannot discover hotplug.
Thus 0.1.5's new policy was running but never reached its XREAL actions; the
previous host policy tests did not model this Android client-cache boundary.

The 0.1.6 helper instead calls `IDisplayManager.getDisplayIds(true)` and
`getDisplayInfo(id)` synchronously on every poll. It builds detached physical
display snapshots directly from returned DisplayInfo fields, including product
identity and active/supported modes. It needs neither callback delivery nor a
DisplayManager/Display instance cache. A regression test holds framework IDs at
the phone-only list while checking disabled hotplug, mode changes, unplug and
new logical IDs through the direct reader. The production DEX audit verifies
the policy actually calls that reader and rejects the old getDisplays path.

No helper was restarted, new code executed, APK installed, setting changed or log
buffer cleared on the Pixel by the agent. Reading the protected system preference
XML was denied; no attempt was made to bypass that permission. The new build
still requires a wearer test for automatic Desktop selection and stereo output.

## 0.1.6 wearer test: app-bound keyguard initialization crashes the watchdog

The read-only capture `artifacts/pixel-display-016-user-test-20261026/` verifies
0.1.6 / 2880 installed. Its helper log now identifies XREAL displays 2–5 in mono
and SBS, confirming the discovery correction runs. Each watchdog then fails in
`AndroidPlatform.unlockedAndCool` with `ApplicationSharedMemory not initialized`.
`KeyguardManager` constructs `WindowManagerGlobal`, which initializes animator
scale from memory supplied during normal app binding. A shell `app_process`
does not receive that binding. The crash occurs before Desktop selection or
any temporary display-policy journal. The current physical display remains
640 × 480, the cap remains 3440 pixels wide, and Mirror remains selected.

The current device's framework SHA-256 matches the inspected local JAR exactly.
Read-only calls to the verified `IWindowManager.isKeyguardLocked` and
`IThermalService.getCurrentThermalStatus` Binder endpoints both succeed (false
and status 0); the shell settings read succeeds and returns mirror value 1.
Only these getters were invoked; no setting or display policy was changed.

0.1.7 removes the watchdog's app Context and queries the same lock and thermal
contracts directly by named interface methods. It keeps both guards. The mirror
setting uses Android's fixed-key shell client, verifies readback after writes,
and restores the captured value. Global policy restoration is now attempted
even if display enumeration fails; pending per-display state stays journaled.

The new test executes the real AndroidPlatform together with the policy against
simulated Binder/command boundaries while all Context service factories throw
the recorded shared-memory error. The archived 0.1.6 adapter reproduces that
crash. The corrected adapter completes Desktop/SBS preparation and exercises
lock, heat, service failure, settings readback and recovery. Production DEX
verification rejects app-bound Context, keyguard, window and secure-settings
wrapper calls in the adapter. These host tests do not establish actual 0.1.7
scanout or chooser behavior. The agent did not install or run the new helper on
the Pixel, restart anything, clear logs, or write device settings.

## 0.1.7 succeeds; app color decode and overlaid desktop chrome remain

The user confirmed AR Space now works and clarified that only opened app windows
look washed out. In `artifacts/pixel-color-017-20261026/`, the helper records
automatic Desktop configuration, a temporary 3840 × 1080 / 60 Hz preference,
that native mode becoming active, and successful app launches. The later active
capture with Calculator confirms 3840 × 1080 physical and logical output.
This establishes the display-startup result for 0.1.7, not exhaustive tracking,
keyboard, reconnect or recovery coverage.

The app log records `AndroidSurfaceTexture: checkGLError: error=1280, OES_SRGB`.
The original Java texture setup requests `GL_TEXTURE_FORMAT_SRGB_OVERRIDE_EXT`
(0x8FBF) with SRGB, then continues after the error. The app copy resource
`49c045fd3d4b4ce45b97f3111d8a0ed8` passes sampled RGB through. That operation
expects hardware decoding; its rejection leaves encoded app pixels in the
linear rendering path and lifts midtones. This matches the app-only symptom.
The extension's intended conversion is described in the
[Khronos specification](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_texture_format_sRGB_override.txt).

The APK already contains software-decoding resource
`1296c2c63c9cea8469aa31beb05d3a93`. All 48 GLES variants differ from the normal
copy shader only by applying the original `GammaToLinearSpace` function to RGB.
Shader properties, render state, alpha output, texture coordinates and stereo
variants match; only shader identifiers/hashes and compressed programs differ.
The opt-in Pixel 0.1.8 build substitutes that existing asset for the app copy
resource. It changes no native library. It is explicitly Pixel-specific because
using software decode on top of a working hardware override would decode twice.
The standard build's color path remains unchanged. No GL extension-preprocessor
macro is assumed to report this non-language texture-state extension.

The same capture proves the border issue is Android chrome over the picture:
the stereo surface fills `[0,0–3840,1080]`, while separate StatusBar and Taskbar
layers cover the top 23 and bottom 73 pixels. The actual native Presentation is
NOT_FOCUSABLE and requests only LOW_PROFILE bars. No UnboundStereo root update
was logged, because the native SDK created its own views rather than attaching
the Java views watched by 0.1.4. Pixel InsetsPolicy chooses a focused window as
the bar-control target; a non-focusable Presentation cannot receive that control.

The 0.1.8 adapter discovers the real Presentation using WindowInspector during
the AR owner's lifetime and fills it without insets. An opaque fullscreen
application activity behind the native surface supplies the display's immersive
bar requests. This matters when input focus moves to an app's virtual display:
Pixel InsetsPolicy can fall back to the top fullscreen opaque application, but
not to a system Presentation. The two AR windows remain NOT_FOCUSABLE and
NOT_TOUCHABLE, preserving app/phone input focus. The new activity is non-exported,
excluded from Recents and only accepts a matching live AR lease; root/owner loss
closes it and a stale restoration finishes immediately. The existing per-view
hook remains compatible. Host tests cover native roots without Java children,
opaque background/input flags, unrelated-window exclusion, invalid leases,
detach/recreation and owner teardown. All 96 vertex/fragment stages of the
stock software shader compile locally. Wearer checks for final color, hidden
bars and input focus remain pending. Pixel access stayed read-only.

## 0.1.8 color success; installed-app headset icons are density-dependent

The user confirms correct app-window colors on 0.1.8 / 2882. They report soft
installed-app icons inside the glasses, while built-in Tutorial/Photos artwork
is sharp; the OnePlus does not show the problem. Read-only diagnostics in
`artifacts/pixel-icons-018-20261026/` verify the Pixel version and 360-dpi setting.
The connected OnePlus reports 0.1.7 and a 476-dpi override (560 physical).
Neither device's settings, packages or files were modified. The attempted
run-as external-directory listing was denied; an ordinary shell listing was
available. No permission was changed and no icon cache was cleared.

The original `com.xreal.evaprolib.IconLoadingHelper` rasterizes adaptive foreground
and background layers at 108 dp using `Resources.getSystem()` metrics. At the
reported densities this is 243 pixels on the Pixel and 321 on the OnePlus.
Legacy/flattened paths instead use the drawable's intrinsic dimensions. A live
icon-size readback was not captured, so these are source-derived sizes, not a
claim of measured cached PNG dimensions. Native disassembly verifies that
EPHAndroidIconLoader binds this Java class and uploads the returned bitmap
width/height without an intermediate rescale. Built-in headset art is 432 pixels.

The 0.1.9 candidate introduces HeadsetIconCompat for only this headset icon path:
adaptive layers render at 432 × 432 and legacy icons preserve aspect ratio with
a 432-pixel maximum side. It asks Android for 640-dpi resource artwork before
rasterization, with fallback to the normal PackageManager result. Adaptive/flat
semantics and larger OEM bitmaps are retained. It restores drawable bounds,
preserves transparent adaptive and white-backed legacy output, and safely
handles missing intrinsic sizes/layers. Seven pinned DEX hooks change source
loading and rasterization; original vertical flipping, raw pixel bytes/metadata,
layer flags, Unity texture upload, native libraries and built-in art remain.

Host tests cover density independence, source selection/fallback, layer alignment,
aspect ratio, invalid dimensions, missing layers and rendering failure cleanup.
DEX tests restore every class to the baseline and reject double-patching or
unrelated edits. The delivered Pixel build retains the confirmed software color
variant and the 0.1.8 immersive backdrop. Icon sharpness remains pending a wearer
test; the prior bar/input-focus changes were not explicitly reconfirmed by the user.
