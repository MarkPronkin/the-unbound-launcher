# Glasses connection recovery and automatic entry

## Dot-matrix connection and trackpad handoff — build 2920

The portal artwork is replaced by the same sparse dot-grid visual language as the
phone controller. In portrait, a dotted USB-C plug and travelling cable pulse lead
to dot-built glasses; landscape uses a horizontal cable composition. The scene
follows the real USB, preparation and AR startup stages without estimated progress.
Connection copy is reduced to the current task, and the existing failure, retry,
cancel and large-text behavior remains available.

On success, the cable and glasses dissolve over 650 ms into a trackpad outline and
pointer, ending on the controller's static grid. Only the intercepted controller
launch and original presenter completion wait for this visual handoff; USB access,
SDK preparation/initialization, display readiness and direct launches outside the
connection flow keep their existing behavior. The launch and presenter callback
each occur once, in that order. Closing or disposing a pending morph prevents a
late launch. With Android animations disabled, touch exploration active, or no
animation view, the final trackpad tableau and handoff complete immediately.

The isolated preview supports both timed stages and a direct connected frame; it
does not access USB, the SDK, helper or production app state. Host validation
passes 59 connection assertions, including deferred-handoff order, duplicate
completion and close-during-morph coverage. The Android 13 emulator passes 22
checks in each of three configurations: portrait, landscape, and landscape at
1.5x font scale with animations disabled. Visual captures cover both cable
compositions, the morph, pointer and reachable recovery controls. Reduced motion
completes immediately; disposal cancels a pending animated handoff. Captures,
recording and validation logs are under `artifacts/connection-dot-matrix/`.
The signed build 2920 APK installs on the Android 13 emulator and passes the
complete APK audit, including signature/alignment, scoped binary restoration,
manifest access, unchanged permissions and class uniqueness. It is
`artifacts/unbound-launcher-dot-matrix-2920/the-unbound-launcher.apk`, SHA-256
`46ff1f0d63a06318470d981e880ac3864a648051f2117128a9da3b0248b3fcbd`.
Physical glasses validation of the wearer-visible handoff remains pending.

## Display and SurfaceFlinger debug exports — build 2915

With Debug mode enabled, **Create debug file** captures fresh `display.txt`
and `surfaceflinger.txt` alongside the existing app log, session metadata and
network history. Keep the glasses connected in the failed state until the
file is ready. Restart the updated paired helper under **On-device ADB** if
the export reports an older or missing helper. Share and Save to Downloads
use the existing ZIP and private file provider.

The existing shell helper runs only `/system/bin/dumpsys -t 8 display` and
`/system/bin/dumpsys -t 8 SurfaceFlinger`, each with a ten-second process
deadline and one-million-byte command-output limit. Each result includes its
capture time, shell identity, command, exit status, timeout and truncation
information. The app has a fifteen-second deadline for each helper capture.
Unavailable services, permission errors and helper failures remain readable
inside the corresponding dump file; app-log export still works.

A new launcher-UID-only Binder transaction accepts one of two fixed service
selectors. It returns a pipe descriptor so large dumps do not occupy Binder's
transaction buffer. The helper permits one dump producer at a time. The app
permits one outstanding request, closes a timed-out pipe and rejects additional
requests until a blocked Binder call returns. No arbitrary command, service
name or output path is accepted from callers.

Each dump file is capped at 1,010,000 UTF-8 bytes including metadata. The existing
12 MB app ring, three 64 KB network snapshots and 15 MB ZIP hard cap remain.
Help and the session report now explain that dumps can contain display
identifiers and app/window names. Nothing is uploaded automatically.

Host checks cover fixed commands, partial output, command failures and timeouts,
UTF-8 truncation, and the complete app ring plus both dump budgets. Android 13
emulator checks exercise actual cross-process Binder descriptor transport,
production ZIP export, missing/old helpers, empty and oversized results, blocked
Binder calls, stalled pipe reads and recovery. Real shell-UID captures on that
emulator successfully read both Android services. This validates capture and
packaging; the Pixel 10 Pro stereo failure still needs a wearer export.

## Samsung DeX coexistence — test candidate 2913

The Air 2 report `unbound-debug-20261027-034043-e09ea1f9.zip` identifies
Samsung SM-S928W / Android 17. The wearer confirms working 3D and fullscreen
output despite DeX opening. The log records four successful stereo renderer
starts, 3840 × 1080 / 60 Hz output and the fullscreen Presentation adapter on
three external display IDs. This is positive evidence for that setup, not a
guarantee for other Samsung models.

Both this report and the SM-F956U1 / Android 16 / Air 2 Pro blank-screen report
show the connection entry/OTA windows initially on the external display.
The helper's `am start` previously omitted a display target. Build 2913 explicitly
targets phone display 0 and fullscreen windowing for that fixed entry activity.
The SDK continues to launch its renderer separately on the glasses.

The helper also previously rewrote the Desktop connection preference on every
poll. Current builds route identified XREAL displays to Mirror, configure each
physical display once per helper lifetime, and journal the previous choice so a
later manual change is respected. Logical display re-enumeration cannot reset
that decision. If no identified screen remains routed to Mirror, the helper
releases its temporary built-in-mirroring override and restores the saved
connection choice. Restarting the helper begins a new configuration lifetime;
this is not permanent system-wide DeX suppression. The Android 16 path without
the connection-preference API is unaffected.

The application declares Samsung's documented
`com.samsung.android.multidisplay.keep_process_alive` metadata to opt out of
DeX transition process termination. Existing SDK activity configuration handlers
remain intact. Samsung documents the setting in its
[DeX optimization guide](https://developer.samsung.com/samsung-dex/modify-optimizing.html).

Disabling DeX globally is not the selected approach. The renderer's external
Activity needs a task-capable display; Android can reject it in mirroring mode.
Samsung's enterprise `setDexDisabled` API requires Knox administration and
enforces mirroring on Android 16 and later, according to its
[API reference](https://docs.samsungknox.com/devref/knox-sdk/reference/com/samsung/android/knox/dex/DexManager.html).
The existing fullscreen stereo Presentation path remains the tested way to cover
the desktop while AR Space is running.

The separate Air 2 Pro failure follows the switch to 2D 90 Hz: Android exposes
only fallback resolutions, and the native renderer reports error 12 / no matching
display before producing zero-size buffers. Build 2913 adds an optional
**Settings → Experimental → Use 60 Hz in 2D** test path, described in
[the display override notes](experimental-stereo.md). Neither DeX nor the refresh
rate alone has been proven to cause that fallback.

Host checks cover fixed phone routing, Android-user preservation, manual display
changes, logical re-enumeration, existing display-policy recovery and manifest
scope. The isolated Android 13 emulator passes 83 production settings assertions
and 136 ARM64 filter runs. These tests do not emulate Samsung's DeX implementation.
Physical Samsung validation of build 2913 remains pending.

The signed candidate passes the complete APK audit, including native/DEX baseline
restoration, the fixed phone-routing endpoint, manifest ordering and unchanged
permissions. All 169 corresponding-source files match their build inputs. The
signing certificate matches the existing 0.1.1 alpha APK, supporting an in-place
update. The candidate is
`artifacts/unbound-launcher-samsung-dex-2913/the-unbound-launcher.apk`, SHA-256
`87a0073804823d092d8cf57893de7e279a0fe9651761f14fc0b36642216bc2ac`.
Validation logs and the settings screenshot are under `artifacts/samsung-dex-2913/`.
For the first wearer test, retain the Air 2's working SBS option; on the Air 2 Pro
that goes black, enable Use 60 Hz in 2D and reconnect. Collect a fresh export
while connected if either case fails. DeX may still start underneath the renderer.

## Ethernet readiness through the paired helper — build 2912

The Pixel 10 Pro Fold's build 2910 export identifies the readiness failure:
all three app-UID snapshots exit 2 with `SO_BINDTODEVICE: Operation not
permitted`, while the helper reaches `169.254.2.1` over `eth0` with zero packet
loss. Addresses and routes are already present. This is an app privilege
restriction on the SDK's fixed ping, before native SDK initialization.

Build 2912 delegates that one ping to the existing paired shell helper. The
new Binder operation accepts only the installed launcher's UID and has no
caller-supplied address, interface or command. It executes only
`/system/bin/ping -I eth0 -c 1 -W 1 169.254.2.1` with a bounded command runner,
returns the actual exit/timeout/output, and rejects overlapping requests.
The client accepts only exit zero without timeout, then rechecks the pending
connection and the same authorized XREAL USB device. Re-enumeration, detach,
revoked permission and cancellation invalidate a positive result.

The SDK's existing ping Runnable retains its retry delay, five-second retry
window, USB listener, callback and native initialization path. Only its
29-code-unit subprocess/result block is replaced; the hook's code size is
unchanged, preserving all branch targets and exception ranges. The client
allows at most four seconds per helper RPC and checks cancellation every
100 ms, with at most one outstanding IPC and no request queue. Missing, old
or unresponsive helpers fail readiness rather than assuming the glasses are
reachable. `UnboundEthernet` records the delegated result in the app logs.
The app-UID diagnostic ping remains available to compare the permission
restriction with helper reachability.

The separate type-11/Sony compatibility patch remains in the build without
changes. Native networking after the readiness check still needs the Pixel
tester's physical validation; this change addresses the confirmed startup
blocker without assuming later SDK operations succeed.

Host regressions cover helper success/failure, caller restriction, absent and
older helpers, USB identity replacement, permission loss, cancellation and
late replies, IPC timeout and lack of backlog. DEX patch/restore checks prove
the rest of the SDK is unchanged. The Android fixture executes the actual
ping Runnable extracted from the signed candidate for success, retries,
deadline rejection and cancellation, using controlled helper replies.

## Network diagnostics and cancelled ping recovery — build 2910

The 0.1.1 public report from a Pixel 10 Pro Fold on Android 17 identifies XREAL
One Pro, grants USB permission and resumes after USB re-enumeration. At
20:22:52 the SDK enters its Ethernet readiness check; at 20:22:57 repeated
failed pings reach its five-second timeout and produce the paused screen.
The released SDK runs `ping -I eth0 -c 1 -W 1 169.254.2.1`. The app-only archive
does not contain interface addresses, routes or command stderr, so it cannot
establish the underlying networking failure or attribute it to the beta OS.

Debug exports now add `network-diagnostics.txt`. The paired helper runs fixed,
read-only probes for interface addresses, every routing table, Ethernet service
status and the SDK's glasses endpoint through `eth0`. The app separately runs
the same endpoint probe under its own UID. Exit codes, stderr, timeouts and
unavailable commands remain in the report. The helper transaction accepts only
the installed launcher UID and takes no command arguments. Each subprocess has
a time and output limit; helper IPC has a client timeout and no request backlog.
An absent or older helper records an explanation without preventing export.

While both debug switches are on, connection failure queues a snapshot off the
main thread. Creating an export with debug mode on also captures current network
state. The newest three snapshots survive process restarts, each capped at
64,000 bytes, so exporting after unplugging retains preceding failure evidence.
The existing 12 MB app log ring and hard 15 MB ZIP cap remain. Debug reports now
include the Android version code, and Help describes network collection and asks
the tester to keep the glasses attached until export finishes.

The stuck retry has two causes. `PingGSeriesHelper.onRelease` removes polling
on unplug without completing its pending result. A replacement connection can
also copy that request's busy flag and retain it after the original finishes.
Two audited SDK hooks wrap the ping callback and finish cancellation once on
the original SDK worker after USB cleanup. A second removal of the queued poll
handles a detach racing an in-flight ping. Late completions are suppressed.
Replacement screens track the outstanding owner and refresh to Try again when
it finishes. The original connectivity check, deadline, native initialization
and model selection remain in place.

Validation includes 55 connection assertions, command failure/stderr/timeout and
output-bound tests, absent/old/hung helper coverage, persisted snapshot rotation
and the ZIP cap with incompressible app logs. Exact DEX restoration rejects
double patching and unrelated edits. The isolated Android 13 emulator passed
33 Help/export checks, including retained failure evidence, fresh snapshots,
sharing and Downloads. Physical Pixel 10 Pro Fold/One Pro testing is pending;
this build collects evidence for the Ethernet failure rather than assuming a
network configuration fix.

The emulator also executes the real SDK DEX cleanup method with
`tools/PingCancellationDeviceChecks.java`, verifying cancellation on the SDK
worker and rejection of duplicate/late success. This catches a D8 register
reuse in the original `onRelease`: its incoming receiver slot becomes a log
argument before return. The patch preserves the receiver in a new, untouched
incoming register and copies it to the old slot for the original body.
The shared helper probes also ran under Android's actual shell UID and
retained command results, including unavailable Ethernet service and ping loss.

## Phone connection animation — build 2909

The bottom connection sheet is replaced by an opaque, full-screen phone view.
A native Canvas animation reveals glasses inside a luminous mint portal, with
expanding rings, orbiting tracers and light streaks. It follows the existing
USB preparation and AR startup stages; it adds no artificial loading delay or
estimated progress. Success settles the scene as the existing launcher takes
over. SDK initialization and the glasses renderer keep their existing timing.

The existing `OTAActivity` uses the black startup theme, no longer reports a
half window, and allows phone rotation. Its USB status callback forwards to
the connection session. Temporary USB re-enumeration remains a waiting state;
real failures and the existing 45-second deadline show recovery. Retry and
Close remain pinned while long messages scroll, including in landscape with
large text. Back and Cancel retain the existing cancellation and stale-callback
protection. The animation stops on failure, success, invisibility, detach and
session disposal; disabled Android animations and touch exploration use a
static scene.

Validation uses the actual view in the isolated Android preview and the
existing connection adapter regression harness. DEX patch/restore checks now
cover twelve scoped methods, and the manifest test limits changes to the
connection activity's theme and orientation. The preview is not packaged into
the launcher. Physical glasses testing of build 2909 remains pending; no phone
was connected to ADB during implementation.

## Original connection recovery — build 2899

The reported symptom is a connection sheet stuck at “Connecting to XREAL…”
after the phone switches to its external desktop. On the inspected Pixel 8 Pro
(Android 17, build 2893, XREAL One), reopening the launcher and starting AR
manually recovered the session. A subsequent physical reconnect also worked.
The original failed callback trace had already rolled out of Android's main
log buffer, so it does not establish the original failure's exact cause or
prove the remote Pixel 10 Pro / One Pro report has the same cause.

## Corrected startup paths

The code audit identified dependencies that are broken by the port's removal
of the stock USB activity handler:

- `XREALManager.startSdk` returns result 2 after changing the glasses' USB
  configuration or enabling their storage connection. The presenter then
  waits for `OTAActivity.onNewIntent` to continue. With that activity's USB
  intent handler removed, re-enumeration alone does not deliver this intent.
- The presenter's reconnect listener treats a temporary DisplayPort removal
  during that USB change as an SDK failure and clears the reconnect flag.
- The normal stereo transition waits for a specific display removal/addition
  callback. A change to an existing display can leave that wait unfinished.

`GlassesConnectionCompat` resumes the original reconnect continuation when the
SDK reports a live connection and the actual XREAL USB device has permission.
Retries are spaced and bounded by the connection deadline. Temporary display
loss during USB reconfiguration retains the reconnect state. Display readiness
is also checked against the native SDK's requested mode and an identified,
enabled XREAL display; stereo waits require physical 3840 × 1080 output.
The existing helper still selects and restores the Pixel's temporary mode policy.

AR startup bypasses the cloud/built-in firmware probes, since firmware updates
and boot mode are already disabled in the port. The One Pro and model 20 SKU
identity previously supplied as a firmware-check side effect is retained before
every SDK start, including after USB re-enumeration resets the base model ID.
The native SDK, device-specific display selector and calibration values are
not replaced.

The connection sheet has a 45-second deadline and a recovery action. Retry
cleans up a completed failed SDK session rather than killing the whole app.
Back closes the sheet. Cancelled/expired native callbacks are rejected before
the original result handler changes display state, and an unfinished native
request cannot overlap a replacement session. Activity destruction unregisters
the old callbacks and releases the presenter's worker.

After setup, a private manifest receiver handles USB attach/detach even when
Unbound is not in the foreground. Android explicitly exempts USB device
broadcasts from the [implicit background broadcast restriction](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions).
The paired helper accepts requests only from the installed launcher UID,
verifies the live XREAL device with its isolated USB worker, and launches only
the fixed launcher component. It deduplicates connections, cancels on detach,
and waits while the phone is locked. SDK re-enumeration during an active
connection does not launch a second session. Helper startup resumes a pending
physical attachment; the existing Wireless debugging/pairing prerequisites apply.

The removed Android USB handler Activity stays absent, preserving the fix for
the external desktop's “Open Unbound to handle…” prompt. Manual Connect / Enter
AR Space remains a fallback, with the original mirroring choice and active-session
restrictions preserved.

Hardware testing of the first candidate exposed an additional stale UI state:
plugging the glasses in while Settings was already open left Connect disabled.
A foreground-only USB observer now re-runs the original launch-button policy
when the identified glasses' USB presence changes. It stops when Settings leaves
the foreground and preserves the original mirroring/active-session restrictions.

## Validation

### Captured reconnect dock defect

The wearer confirmed automatic AR entry from Home on build 2896, then reported
the Android desktop dock on a subsequent connection. The retained window dump
for external display 19 shows a native stereo Presentation at 3840 × 1080,
visible navigation-bar insets, and `NRFakeActivity` as the system-bar control
target. The intended `ArBackdropActivity` has no window and remains
`INITIALIZING` with `app=null`.

Two manifest errors prevent the backdrop from fulfilling its existing contract.
It inherited Nebula's translucent activity theme until `onCreate`, after Android
had already calculated its initial visibility. It also defaulted to the phone
UI process; the live lease registry it validates belongs to the `:space`
renderer process. Even if launched, the UI process cannot validate that lease.
Build 2897 declares Android's opaque fullscreen Material theme in the manifest
and runs the private backdrop in the same `:space` process as `LaunchSpaceAcrivity`.
The native Presentation and backdrop retain their non-focusable, non-touchable
flags so the phone controller and apps keep receiving input.

Manifest regression tests and the final APK audit verify the actual renderer
process match, theme resource, private activity, and Android attribute ordering.
The existing lifecycle tests cover stale leases, root detach, owner teardown,
display selection and input flags. After charging, build 2897 was installed on
the Pixel with its full APK checksum verified on-device. Android confirms
versionCode 2897, and the updated helper reaches `BEAM_LAUNCH_SERVER_READY`.
The wearer initially confirmed automatic AR entry on both physical connections
and that the desktop dock stayed hidden on the subsequent connection. The log
records two helper launches and stereo renderers at 3840 × 1080 / 60 Hz on
external displays 3 and 9. The final window dump was taken after unplugging,
so it cannot establish the live backdrop's window or inset-control state;
that initial result was superseded by the build 2898 repeat test below.

### Android 17 suppresses the backdrop

On build 2898, the wearer saw the dock on the second connection again. A live
dump on display 15 confirms both bars visible and `ArBackdropActivity` still
`INITIALIZING`, without a process or window, despite its corrected manifest.
The first backdrop only receives `onCreate` after the display is unplugged and
Android moves its task to the phone, where the stale-lease checks close it.

The Pixel's own `PresentationController` explains this behavior:
`shouldOccludeActivities(displayId)` returns true while a public Presentation
is visible. A separate Activity cannot supply bar-control requests in that state.
The Pixel's `InsetsPolicy` selects the focused window on that display; the SDK
marks its Presentation non-focusable. With no running fullscreen Activity and
no focusable Presentation, both bars remain visible.

Build 2899 lets the identified stereo Presentation take window focus on its display on
Android 17 and later. `FLAG_NOT_TOUCHABLE` keeps renderer touches excluded and
[`FLAG_ALT_FOCUSABLE_IM`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_ALT_FOCUSABLE_IM)
excludes it from app input-method targeting. The existing
helper still injects app input into explicitly managed virtual displays. The
tracker reconciles a later native flag rewrite without repeatedly relaying out
an unchanged window. Android 16 and earlier retain their prior input flags and
backdrop path. No native library or SDK display selector is changed by this
correction. The wearer confirmed the second connection worked. Live window
dumps from the second and third connections (displays 20 and 22) show the AR
Presentation controlling both hidden bars while the phone controller retains
focus on display 0. Pointer/keyboard coverage remains incomplete.

### Known bug: audio distortion and temporary freeze after reconnect

**Status: under investigation; paused at the user's request on 2026-10-27.**
The user accepts installed build 2899 for now with this known bug.

On the third physical connection, the wearer reported an apparent complete AR
freeze that recovered after a while, muffled audio and an ongoing high-pitched
sound from the left speaker. The exact freeze duration was not established.
The third connection reached its stereo renderer in about eight seconds, and
its system bars were hidden in the live window dump; those results do not
establish why the later freeze or audio fault occurred.

The audio capture shows two active Unbound MiniAudio/OpenSL ES tracks at
48 kHz stereo routed to the XREAL USB headset. No cause has been established.
The wearer believed the noise stopped when media was temporarily muted.
Unbound was then restarted to test stale session state, but the resulting audio
was not revalidated before the investigation was paused. The app restored its
saved media volume during startup: the last readback was 6/25, matching the
pre-diagnostic level. No further phone changes or audio tests are planned until
the user resumes the investigation.

Evidence is retained in the private `artifacts/connection-recovery-host/`
files `pixel-2899-live.txt`, `pixel-2899-third-windows.txt`,
`pixel-2899-bad-audio.txt`, `pixel-2899-bad-audioflinger.txt`, and
`pixel-2899-bad-audiopolicy.txt`. Live log capture has been stopped.

### Disconnect service crash

The same two reconnect tests revealed an additional deterministic unplug crash
in `com.xreal.evapro.nebula:service`. `JobService$1.run` attempts the protected
`android.media.AUDIO_BECOMING_NOISY` broadcast twice after detach, and Android
throws `SecurityException` because this port is an ordinary installed app.
The process also hosts the notification and sensor calibration services.

Build 2898 removes only this one forbidden broadcast invocation from the
original task. Equal-length NOP instructions preserve every code address,
register, debug entry and exception range. The scheduler and all other service
and DEX methods remain unchanged; Android retains control of audio routing.
The patch requires the exact protected-action string and broadcast signature,
round-trips to the byte-identical input, rejects repeat application and rejects
unrelated DEX changes. A full APK audit reverses this patch before the existing
USB, color and icon patches. On the build 2898 test, the background service PID
remains 16456 across unplug and reconnect, with no fatal exception. Android's
own AudioDeviceBroker emits the normal noisy-audio broadcast on detach.

### Automated and hardware coverage

The host tests execute the production Java adapter across missing activity
intents, delayed USB authorization, DisplayPort loss during USB reconfiguration,
missing display-removal events, mismatched native/physical modes, unrelated
displays and USB devices, timeout, cancellation, late callbacks, safe retry,
and both SKU-specific model identities. Mono defaults remain supported.
The connection adapter passes 39 host assertions, and automatic entry passes
18 caller/hardware/lock/deduplication assertions. Manifest/version checks and the
existing USB/display/setup regressions pass. Ten scoped DEX hooks round-trip to
the exact baseline; double application is
rejected. A separate APK audit checks every production recovery call, including
the native callback guard and lifecycle installation.

Build 2897 passes the full APK audit, signing/alignment checks, native display
default audit, and bundled-source comparison (126 files). The Presentation
lifecycle suite adds 39 assertions to the 57 connection/automatic-entry
assertions. The APK SHA-256 is
`665f19006ece113b19c5ab3fcb4f3a4702ff1f610736ea9b907f7cfcae05073e`.

Build 2898 also passes the full APK, native-default, signing and alignment
audits. All 128 bundled source files match its build inputs. The disconnect
regression test and five source-archive tests pass. Its APK SHA-256 is
`5f56c5e24692f75b31799c832430c83f73e4e6424bfb95a377fd234e24d751c0`,
including the complete APK reconstructed and checked on the Pixel before
installation.

Build 2899 passes the full APK, native-default, signing/alignment and 128-file
source audit, and is installed with its helper Ready. The updated Presentation
suite passes 46 assertions, covering both the legacy path and Android 17 focus,
touch/IME exclusions, native flag rewrites and stable-window idempotence. Its
APK SHA-256 is
`74685b5cb4c2d4d288af490abe6f8fd11a6f9a0dc09cbcbf220329875ea05c38`;
the complete on-device reconstructed APK matches this hash. Repeated physical
connections confirm automatic entry and hidden system bars. The third-session
audio/freeze issue remains under investigation, and app-input coverage is
incomplete. The user accepts this build for now with that known bug.

Build artifacts and raw device evidence remain private under
`artifacts/unbound-launcher-connection-2899/`,
`artifacts/unbound-launcher-connection-final-2898/`,
`artifacts/unbound-launcher-auto-entry-2897/`,
`artifacts/connection-recovery-host/` and
`artifacts/pixel-connection-live-20261026/`. Builds 2894–2896 were installed on
the Pixel: the wearer confirmed AR Space, Settings hotplug refresh, and then
automatic entry from Home. Build 2897's initial dock result was superseded by a
failed second-connection test on build 2898. Build 2898 fixes the separate
disconnect crash; build 2899 addresses the Android 17 bar-control policy. One Pro and
Pixel 10 Pro hardware validation remains pending.
