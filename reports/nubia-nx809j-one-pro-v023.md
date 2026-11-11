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
