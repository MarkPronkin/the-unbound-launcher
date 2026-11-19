# Nubia NX809J / XREAL One Pro: native display modes lost during startup

`unbound-debug-20261106-211405-528f9149.zip` identifies **nubia NX809J,
Android 16 / API 36, Unbound 0.2.3 alpha build 2957**, with **XREAL One Pro
(SDK model 6)**. The wearer reports being stuck on *Starting AR Space*, with
the phone mirror split between the two eyes after the glasses enter 3D mode.

**Four distinct attempts return SDK success, then time out with only
640 × 480 / 60 Hz advertised by the external display.** Before each SDK
start, the glasses expose 1920 × 1080 / 120 Hz. The replacement display has
no advertised native 1080p mode. This is the same observed failure pattern as
the [Pixel 10 Pro / One Pro investigation](pixel10-pro-one-pro-v012.md),
without establishing that the two phones have the same underlying cause.

A connected retest on **0.2.5 alpha build 2967** confirms that Qualcomm's
hardware composer also exposes only VGA after the switch. It identifies
Nubia's secondary-display launcher as the visible desktop. The connected
evidence and its implications are recorded below.

## Retained evidence

Times below are November 6 UTC. The phone logs use UTC+5, so these attempts
appear as November 7 locally. Line numbers refer to the unmodified extraction
under `artifacts/nubia-nx809j-onepro-2957/export/`.

- `glasses-startup.txt:26–30`: at 20:31:35, display 7 exposes only
  1920 × 1080 / 120 Hz. SDK result 0 arrives while no external physical
  display is visible. At 20:32:20, the 45-second deadline expires with
  replacement display 8 exposing only 640 × 480 / 60 Hz.
- `glasses-startup.txt:63–67,81–85,98–102`: three further attempts repeat
  the same transition, through displays 9→10, 12→13 and 14→15. The latter
  two run in a new app process. Every attempt ends in the same timeout.
- `glasses-startup.txt:22,59,77,94`: firmware
  **1.9.1.20260914175625** matches this build's pinned recommendation;
  startup proceeds without an override.
- `glasses-control-trace.txt:214–219`: the final attempt's app-owned TCP
  socket uses the glasses Ethernet source address and reaches `ESTABLISHED`
  against the fixed control peer, `169.254.2.1:52999`. Queued bytes drain;
  later samples include received bytes. This establishes a connected
  transport, without identifying individual firmware commands or replies.
- `network-diagnostics.txt`: the app-UID interface-bound ping receives
  three replies with zero packet loss. A shell route lookup via Wi-Fi does
  not describe the already established app socket's route.
- `display-link.txt:551–639,1565–1653`: snapshots retained at the last two
  failures show external displays 13 and 15 with no focused app/window.
  Android reports 769 × 480 logical bounds and a **640 × 480 physical
  display shape**; these are not native stereo output.
- `renderer-geometry.txt` contains no renderer sample, and
  `immersive-windows.txt` records no capture attempt. These alone cannot
  prove that no renderer code executed, but they provide no evidence of a
  working XR session or native glasses Presentation.

## Interpretation and limits

The renderer's direct-display waiter accepts native 1920/3840 × 1080
physical modes, then starts Unity once output settles. VGA cannot pass that
readiness check. The helper can select an advertised stereo timing, but cannot
create a hardware timing absent from `Display.getSupportedModes()`.
Relaxing readiness to accept VGA would not restore native output.

The reported split phone mirror is consistent with glasses interpreting
fallback mirrored output as side-by-side stereo while the AR Presentation
never becomes ready. The optical image and the exact EDID-setting command
are not captured by this export. `app.log` contains collection headers only,
with no timestamped logcat records; Java startup events and control traces
are the useful retained startup evidence.

The glasses were disconnected before final export: `device-and-session.txt`,
`display.txt` and `surfaceflinger.txt` show only the internal phone display.
Consequently, this bundle lacks a connected SurfaceFlinger hardware-mode
list or framework mode-vote dump. Its earlier failure snapshots still
establish the VGA-only result. Do not transfer the Pixel report's GPU,
driver, pixel-clock or framework-cap conclusions to this phone.

The retained USB snapshots reach `port_manager` and show an enabled,
connected host port, but expose no DP lane, link-rate or training result.
Selected system logs are empty, kernel-buffer filtering finds no relevant
messages, `dmesg` is denied, and the DRM connector provides no readable
attributes or EDID. The bundle therefore cannot distinguish phone display
driver behavior, link negotiation, cable/adapter limitations, or a glasses
firmware interaction.

This differs from the [Pixel 10 Pro Fold renderer network
failure](pixel10-fold-one-pro-v020.md): that report reaches 3840 × 1080
before the renderer's second control connection fails. Build 2962's renderer
network changes do not directly address this earlier missing-mode stage;
their effect on the NX809J still requires a hardware retest.

## Focused reporter test and next implementation work

1. Retest with the original XREAL cable directly connected to the phone,
   without a hub, extension or charging adapter. Confirm that ordinary 2D
   phone mirroring works before entering AR.
2. Retest the current launcher with the helper running. Keep the glasses
   connected through debug export, including after a timeout, so the final
   display/compositor dumps preserve the failing mode list.
3. If VGA-only output persists, test the glasses' own full-SBS mode with
   Unbound closed and capture the connected display/compositor mode list.
   This distinguishes failure to expose 3840 × 1080 outside Unbound from
   an AR-startup-specific transition. A normal phone mirror can look split
   even at the correct SBS resolution: judge this test by the physical
   timing, or use correctly formatted SBS test content. Return to 2D after
   the test.
4. App-side follow-up should detect persistent fallback-only output after
   the stereo transition and explain the native-resolution failure. A
   bounded return to 2D, or explicit mono AR fallback, needs separate SDK
   lifecycle implementation and hardware validation. Neither is implemented
   by this investigation.

The initial 0.2.3 investigation changed only this note. Raw captures remain
in ignored local artifacts.
The four distinct attempt sequences were checked programmatically. Existing
direct-routing tests pass **13 assertions** and SDK-derived output-readiness
tests pass **103 checks**:

```sh
cd tools
python3 -m unittest \
  test_presentation_only.PresentationOnlyTests.test_direct_routing_and_restoration \
  test_presentation_only.PresentationOnlyTests.test_mode_readiness -v
```

## Connected retest on 0.2.5 alpha

`unbound-debug-20261108-171805-1ae31e9e.zip` contains a fresh attempt from the
same NX809J, Android 16, One Pro combination on **0.2.5 alpha build 2967**.
The reporter sees a large screen with the standard wallpaper and a round
button on its right, followed a few seconds later by the broken image.
Firmware verification accepts software **1.9.1.20260914175625** without an
override. The control connection reaches `ESTABLISHED` over glasses Ethernet.

The new capture records the transition at its onset, then retains a second
snapshot at the timeout. The glasses remain connected through export, so
the final display and compositor dumps also describe the failure. Evidence
is retained locally under `artifacts/nubia-nx809j-onepro-2967/export/`.
Times below are November 8 UTC; device-local times are five hours later.

- **17:17:02.429:** SDK startup begins with display 11 at
  **1920 × 1080 / 120 Hz**, its only advertised mode.
- **17:17:02.798:** the physical external display disappears. SDK startup
  returns result 0 while the display is absent.
- **17:17:04.813:** replacement display 12 appears at
  **640 × 480 / 60 Hz**, initially off. It turns on at 17:17:05.047 and
  advertises no other physical mode. This is about 2.4 seconds after SDK
  startup, consistent with the reported delay before the picture breaks.
- **17:17:04.816:** automatic startup capture is requested, three
  milliseconds after fallback output is first observed. DisplayManager is
  sampled from 04.818 and SurfaceFlinger from 05.119.
- **17:17:47.899:** startup times out with the same VGA-only display.

### The hardware composer exposes only VGA

The first retained snapshot's external `mSfDisplayModes` contains only
640 × 480 (`display-startup.txt:219–227`). SurfaceFlinger independently
lists one external mode at 640 × 480 (`display-startup.txt:1464–1468`).
The final Qualcomm SDM dump reports **one config**, 640 × 480 display and
mixer dimensions, and the standard VGA timing of 800 × 525 total pixels
at a **25.175 MHz** pixel clock (`surfaceflinger.txt:1615–1623`). The GPU
is identified as **Qualcomm Adreno 840**.

No external size-limit vote appears in the captured `DisplayModeDirector`
section, and no user-preferred mode is set. This establishes a missing
native mode below the app/framework preference layer. The helper cannot
select 3840 × 1080 when the composer and DisplayManager offer only VGA.
The phone's working 1920 × 1080 / 120 Hz input does not establish that it
can accept the different stereo timing.

### The wallpaper belongs to the secondary display launcher

The external display's focused app and visible composition identify
`com.zte.mifavor.launcher/com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher`
alongside wallpaper and navigation-bar layers (`display.txt:1382–1421`,
`surfaceflinger.txt:1591–1595`). Android's upstream
[secondary-display launcher](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/src/com/android/launcher3/secondarydisplay/SecondaryDisplayLauncher.java)
uses an apps button to open its app drawer. This supports interpreting the
reported wallpaper/button as Nubia's external home screen. The dump does
not identify every visual detail of Nubia's implementation or capture the
earlier 1080p screen.

AR never reaches its ready handoff. There is no renderer-geometry sample
or immersive Presentation capture. Seeing this desktop therefore does
not establish that AR Space briefly worked before failing.

The secondary launcher could require separate routing handling on this
OEM, but its presence does not identify the cause of the missing hardware
modes. Nubia's `DisplayMirrorCtrl.mIs3DMode=false` is an OEM mirroring
state field; it does not read the glasses' firmware SBS state. Disabling
desktop UI is not a demonstrated remedy for the VGA-only mode list.

### Driver rejection remains inaccessible

Both startup snapshots have empty selected system-log output and no vendor
keyword matches. Kernel-buffer filtering finds no display/link messages.
`dmesg` and DRM `status`, `enabled`, `modes`, `dpms` and `edid` reads return
permission errors. The port dump confirms an enabled USB host connection
but exposes no DisplayPort lane count, negotiated link rate or training
result. The exact EDID-setting command is also absent from app logcat.
The capture confirms the failure layer without identifying the driver's
reason for dropping native modes.

The useful isolation test is to enable the glasses' full-SBS mode outside
Unbound AR and capture the connected physical/compositor mode list.
Correctly formatted SBS content can help check the picture, but an ordinary
mirror looking split is not a resolution test. If standalone SBS also
offers only VGA, the failure is independent of AR rendering. If it exposes
3840 × 1080, the SDK's requested timing and transition sequence become the
next target. Record any OEM Desktop/Mirror choice or forced-desktop setting
with that test; the launcher component alone does not establish its value.

This retest changes the evidence, not the supported display modes. No runtime
patch or new APK is produced from it. A mono AR fallback or return to 2D on
failure remains separate implementation work requiring hardware validation.

## Guided display retest on 0.2.6 alpha, build 2972

Two November 11 exports reproduce the failure on **Nubia NX809J, Android 16,
XREAL One Pro**, with the same accepted firmware
**1.9.1.20260914175625**:

- `unbound-debug-20261111-195135-1b98ac5e.zip`
- `unbound-debug-20261111-195501-5a95e52b.zip`

The archives and unmodified extractions are retained under
`artifacts/nubia-nx809j-onepro-2972/`, with archive hashes and checked event
counts in `evidence.json`. Times below are UTC on November 11; the device
renders these as November 12 in UTC+5.

**The new finding is that the independent 2D EDID switch also loses native
output. The guided SBS test never reaches its stereo command.** This narrows
the problem to display-mode negotiation before AR rendering, without proving
whether the phone driver, glasses firmware, EDID contents or physical link
causes the fallback.

### One AR attempt retained in both exports

`app.log`, `glasses-startup.txt`, `glasses-control-trace.txt` and
`display-startup.txt` are byte-identical between the archives. The first-failure
and recent-timeline sections also repeat the same startup events. There is
**one distinct AR attempt**, `1a1cc295ac9-1`, rather than two independent
attempts:

- `export-1/glasses-startup.txt:28–32`: recommended firmware is accepted;
  SDK startup begins at **19:50:26.256**, with display 45 offering only
  **1920 × 1080 / 120 Hz**.
- `:35–39`: the external display disappears at **19:50:26.626**; SDK result
  0 follows. Display 46 returns at **19:50:28.642** with only
  **640 × 480 / 60 Hz**, then turns on.
- `:40`: startup times out at **19:51:11.692** with the same VGA-only
  physical mode list. The export contains no renderer geometry sample or
  immersive Presentation capture.

USB access is granted. The app-owned control socket reaches `ESTABLISHED`
from `169.254.2.10` to `169.254.2.1:52999` and its send queue drains
(`export-1/glasses-control-trace.txt:17–29`). App-UID interface-bound pings
receive all three replies at each retained network snapshot. These observations
support a working control transport; they do not identify individual firmware
requests or establish display-link correctness.

### A 2D request independently causes the same fallback

The second export adds guided-test history (`export-2/display-tests.txt`):

- `:4–8`: a connection check at **19:53:37.546** and the start of the 2D
  test at **19:53:43.559** observe a new display 47 at
  **1920 × 1080 / 120 Hz**. The history does not record how this native
  output was restored between checks.
- `:9–15`: control preparation succeeds. At **19:53:43.803**, the test
  requests **1920 × 1080 / 60 Hz**, and **Ethernet EDID 1 returns true**.
  The display disappears at **19:53:44.079** and is replaced by display 48
  at **640 × 480 / 60 Hz** at **19:53:45.851**. No native mode is advertised.
- `:16–19`: recovery repeats mode 1 and also returns true, but the
  observed output stays VGA. At **19:54:16.118**, recovery completes with
  `restored=false` and requests a physical reconnect.
- `:20–30`: the subsequent **3D SBS test** starts from VGA, requests the
  same 2D baseline, and is stopped before reaching stereo. Its recovery
  again returns true without restoring native output. There is **no
  `REQUEST SBS` or mode-5 command** in the guided-test history, and no test
  pattern or wearer feedback is recorded.

`DisplayTestCompat` prepares the background control client without selecting
an EDID or launching Unity, then sends `set2D3D(1)` for the baseline.
`DisplayTestRun` requires stable 1080p60 before capturing that baseline and
requesting SBS. Thus this export demonstrates a 2D-switch failure independent
of AR rendering; it does **not** measure the outcome of the guided stereo
request. Ordinary AR startup still fails separately as described above.

### The missing modes remain below app mode selection

The final connected SurfaceFlinger dump lists only VGA for the external
display. Qualcomm SDM reports **one configuration**, a **640 × 480** display
and mixer, and an **800 × 525** total timing at **25.175 MHz**
(`export-2/surfaceflinger.txt:1733–1745`). The retained startup snapshot likewise
lists only VGA in DisplayManager and SurfaceFlinger. Selecting a preferred
mode or relaxing the AR readiness check cannot recover a native timing that
the hardware composer does not expose.

Selected system logs remain empty; kernel filtering yields no useful link
messages. Android denies `dmesg` and DRM connector/EDID reads. Consequently,
the evidence does not establish why native modes disappear or that 60 Hz
itself is unsupported. An EDID change, hotplug sequence or link/firmware
interaction could produce the same observation.

The next isolation test should start with a fresh connection at the observed
1080p120 timing and test the glasses' own full-SBS switch outside Unbound AR,
capturing the connected physical mode list. A future app diagnostic should
also allow testing SBS from that existing native baseline without first
requiring the failing EDID-1 transition. Recovery to the original working
timing requires separate implementation and hardware validation; repeating
mode 1 is demonstrably insufficient in this capture.

This investigation updates the report only. No runtime fix or APK is produced:
the logs establish the failure stage, but do not establish a working replacement
mode or switch sequence. Existing guided-display host tests validate the
command selection and baseline gate; they cannot validate this phone's
display negotiation.

## Additional fresh-connection capture and diagnostic candidate, build 2973

`unbound-debug-20261111-200434-d2160715.zip` extends the same build-2972
session. The retained AR startup evidence is still byte-identical to the first
export. Its guided history contains 16 checks/tests, nine 2D-baseline requests,
nine unconfirmed recoveries and no SBS request. The final SBS-labelled run
starts from **1920 × 1080 / 120 Hz** at **20:02:46.311 UTC**, sends EDID 1
at **20:02:46.588**, and observes only VGA at **20:02:48.599**. This repeats
the baseline failure on a fresh native connection.

The supplied `Screenrecorder-20261112-010243.mp4` starts with the phone's
Current output field showing **1920 × 1080 / 120 Hz**. The older failure
message remains visible beneath it. Starting the old test removes the display,
then shows VGA and an unsuccessful recovery. This corroborates the phone's
reported physical timing; the recording does not capture the optical glasses
image or establish working stereo. Both the third export and its provenance
are retained alongside the earlier local evidence.

The local **0.2.7 alpha / build 2973** candidate changes the diagnostic path:

- SBS testing saves the existing connected display snapshot, accepts stable
  native 1080p 2D at its existing refresh rate, and requests SBS directly. It
  does not first force the failing 1080p60 baseline. The separate 2D60 test
  remains available and also captures its original output before switching.
- Control preparation reads the original SDK mode on the SDK worker. When
  that mode is a known mono code and the physical output is active native
  1080p, test cleanup requests the saved mode and verifies its original refresh
  rate. Unknown/non-native initial output retains the existing mode-1 fallback.
  Cancelling a test before any write leaves the original mode untouched;
  the explicit Return to 2D action still issues a recovery request.
- Test history records original mode/output, recovery target, command timing,
  SDK mode readbacks, firmware input/working state and the supported-mode mask
  when those native getters are available. Getter failures are retained as
  unavailable data rather than treated as a successful read.
- With debug collection enabled, three scoped native-write observers inside
  the SDK's `set2D3D` retain EDID, input-mode and legacy commands, arguments,
  original/read-back state, individual boolean results and elapsed time in
  `glasses-startup.txt`, independently of app logcat. Input-mode failure is
  therefore visible even when the SDK returns EDID-setting success. The
  observers preserve native write order, arguments, results and exceptions;
  they query state before writes so a failed write's native error remains
  available to the SDK's own error check. Request events precede those getters
  so a stalled diagnostic read still leaves evidence.
- The first failed guided test's before/switched/recovery snapshots are pinned
  beside the latest test's snapshots, within the 15 MB export cap. The existing
  `stereo` slot also holds the post-switch snapshot for a 2D test; each capture
  now records its actual test kind and phase. A failed original snapshot aborts
  the test instead of issuing a switch with no baseline evidence.
- AR startup reports persistent VGA-only output after ten seconds of continuous
  observation when no native timing is advertised. Transient hotplug, a native
  advertised mode or a successful handoff keeps the original readiness path.
  The report also includes the Android build fingerprint and security patch.

These changes improve isolation and recovery; they do not establish a working
AR switch on the NX809J. The automatic AR selector and native libraries are
unchanged. Android's restrictions on raw connector EDID and driver logs still
apply, so the next capture may narrow the cause without exposing the driver's
exact rejection reason.

### Reporter retest with this candidate

1. Install build 2973. Enable Debug mode and log collection before reproducing
   the problem, and open Help → Display tests before reconnecting the glasses.
2. Reconnect directly and wait until Current output shows native 1080p. Run
   **Test 3D · SBS 60 Hz first**, keep the page open through automatic recovery,
   and record pattern feedback if a pattern appears.
3. Create the debug file while the glasses remain connected. This captures
   the saved original mode, direct stereo request, individual native command
   results, physical mode changes and the attempt to restore the original mode.
4. If testing 2D60 too, reconnect to native output first and export again after
   that test. The pinned first failure remains available alongside the latest
   test. A separate normal AR attempt with collection enabled also records the
   SDK's individual EDID/input commands without relying on app logcat.

At delivery, the candidate was local only and had not been tested on the
reporter's hardware. The subsequent reporter retest is recorded below.

Candidate validation passes **16 targeted host regression tests** plus the final
archive-capacity checks, including six full guided snapshots within the 15 MB
export limit and a late recovery capture updating the pinned failed run.
The full signed-APK audit passes: signature/alignment, no duplicate classes,
matching bundled sources, and exact reversal of the scoped SDK hooks. All
**88 native libraries** are byte-identical to released 0.2.6, and its signing
certificate matches, allowing an in-place update. The APK, checksum, audit logs
and validation receipt are in `artifacts/unbound-launcher-nubia-modes-2973/`.
No APK was installed on a phone or published by this task.

## Reporter retest on build 2973: recovery confirmed, stereo still blocked

The reporter supplied `unbound-debug-20261111-214528-52d5521d.zip` and
`unbound-debug-20261111-214605-86e0f501.zip`, both from **0.2.7 alpha / 2973**.
The Android fingerprint identifies the NX809J's REDMAGIC Android 16 build
`20261021.232226`, with security patch **2026-10-01**. Firmware remains
**1.9.1.20260914175625**.

Unmodified exports and archive hashes are retained locally under
`artifacts/nubia-nx809j-onepro-2973/`. Its `evidence.json` checks the event counts,
snapshot modes and deduplicated attempt IDs. Line references below use
`export-1/`; all times are November 11 UTC (November 12 in device-local UTC+5).

The two ZIPs contain byte-identical startup events, guided-test history, control
trace and six guided snapshots. They describe **one normal AR attempt, two
2D tests, one explicit recovery and one direct SBS test**, rather than two
independent retests. Only final export-time diagnostics differ.

### Original-mode recovery now works on this phone

Every prepared guided session reports **original EDID 9**, physical
**1920 × 1080 / 120 Hz**, firmware input mode **0**, and recovery target **9**.
Both 2D60 tests still fall back to VGA, but restoring EDID 9 returns physical
1080p120 without an intervening recorded disconnect. The SBS test recovers in
the same way. All four recovery completions report `restored=true`, including
the separate Return to 2D action.

The retained first-failure snapshots show the first 2D test's native output,
VGA failure and recovered native output. The latest snapshots independently
show the same three states for the direct SBS test. DisplayManager,
SurfaceFlinger and Qualcomm SDM agree: native/recovered output uses one
1920 × 1080 configuration at 120 Hz, with 2200 × 1125 total timing and a
297 MHz pixel clock; each failed switch exposes one VGA configuration at
60 Hz, with 800 × 525 totals and a 25.175 MHz clock. This validates the
original-mode recovery against connected physical/compositor evidence; it
does not establish AR rendering or optical correctness.

### Direct SBS also fails after both native commands succeed

Normal AR at **21:43:12** requests EDID **5**, then input mode **1** with
flags **0**. Both native setters return true. SDK result 0 follows while the
external display is absent. Persistent VGA-only output then triggers the new
specific failure at **21:43:25.403**, about thirteen seconds after SDK startup.

The guided SBS run (`1a1cc923763-5`) confirms this independently of Unity:

- **21:44:57.193:** EDID 9 and active 1080p120 are recorded before switching.
- **21:45:00.255:** the test requests **3840 × 1080 / 60 Hz** directly from
  the existing native baseline; no 2D60 request precedes it.
- The EDID-5 write returns true in **38 ms**, and the separate input-mode
  `(1, 0)` write returns true in **23 ms**. The immediate readback reports
  input **1** and working state **1**.
- **21:45:02.540:** display 95 appears with only **640 × 480 / 60 Hz**.
  Its connected compositor snapshot likewise contains no native timing.
- **21:45:12.453:** immediately before recovery, firmware readback reports
  **EDID 5 / input 1**. This narrows the failure beyond a rejected or omitted
  input-mode command: the requested firmware state is observable while the
  phone still exposes only VGA.
- **21:45:12.436–15.739:** restoring EDID 9 returns display 96 to native
  **1080p120**, and physical recovery is confirmed after a stable interval.

The immediate EDID readback is **0** during several transitions, including
successful recovery to mode 9. Later reads return the intended mode (1 after
2D60, 5 after SBS, 9 at subsequent native preparation). Zero therefore cannot
be treated as proof of a wrong mode number or rejected command. Similarly,
the raw supported-mode-mask getter returns **−1**; it supplies no reliable
enumeration of alternative timings for this investigation.

### What this establishes and what remains unknown

The new diagnostics and recovery behave as intended on the reporter's phone.
The test no longer blocks stereo behind a forced 2D60 baseline, and the
individual command results rule out the suspected hidden input-setter failure
in these attempts. The control trace's app-owned socket establishes and its
queues drain; interface-bound pings again receive all three replies.

AR and direct SBS remain blocked by missing native physical modes. The
compositor offers only VGA after either requested mode 1 or mode 5, despite
accepted control writes and the later matching firmware mode readback. Raw
connector EDID reads and `dmesg` are still denied, and selected driver logs
remain empty, so the capture cannot identify malformed/different EDID data,
phone-driver timing rejection or a link/firmware interaction as the exact cause.

EDID 9 / native 1080p120 is now a hardware-validated recovery target. A 120 Hz
mono AR compatibility path is a concrete workaround to investigate, but it
would also need a matching Unity rendering mode and lifecycle handling. The
working phone-output recovery does not establish that such an AR path already
works. Another useful discriminator remains the glasses' full-SBS output on
the same phone outside Unbound's SDK-controlled transition, with its connected
physical mode list retained.

This follow-up records the new evidence and hardware recovery result. It does
not produce another APK or claim a stereo/AR fix; repeating the same 2D60 or
SBS60 tests on build 2973 would not resolve the remaining EDID/driver question.
