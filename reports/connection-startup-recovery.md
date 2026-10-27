# Glasses connection recovery and automatic entry — build 2899

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
