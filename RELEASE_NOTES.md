# Unbound Launcher 0.2.1 alpha

Android build **2949**. This release includes all changes since **0.2.0 alpha
(build 2946)**, including the diagnostics and firmware work from development
builds 2947–2948.

## Changes since 0.2.0 alpha

- **Firmware compatibility check before AR startup.** After USB permission and
  model identification, the launcher reads the glasses firmware before native
  SDK initialization or display-mode commands. It compares the result with the
  exact stable release pinned for that model, checked against XREAL's official
  updater on October 29, 2026. Older, newer, beta, unknown or unreadable firmware
  pauses startup on a full-screen warning showing the model, mismatching
  component, recommended version, official updater link and **Exit**. The small
  **I know it won’t work; continue regardless** action overrides the check for
  that connection only; reconnecting checks again. The SDK's temporary USB
  reconfiguration preserves the decision for the same glasses. The check works
  offline, reads firmware only, and never flashes firmware. Different models
  have separate recommendations; version 2.0.0 is not treated as beta for every
  model. Experimental models without a pinned recommendation require an override.
- **Five-second grace period for temporary glasses disconnects.** The helper
  retains its display settings during short reconnects, including when Android
  assigns a new display ID, to avoid repeated cleanup and reapplication during
  a mode switch. A reconnect cancels cleanup and resets the mode-switch timeout.
  A replacement monitor, phone lock, severe heat or helper watchdog shutdown
  still restores the previous settings immediately. This addresses a regression
  candidate found in Samsung Android 17 reports; resolution of the reported
  reconnect loop still needs testing on those phones.
- **More useful startup evidence.** A failed retry with empty firmware strings
  no longer replaces the last real firmware response. Startup summaries also
  retain the external displays present when the SDK starts, helping distinguish
  control-channel failure from a missing glasses display.
- **Control-channel traces in debug exports.** `glasses-control-trace.txt` adds
  a bounded 12-second observation of socket states, send/receive queues,
  retransmission timers and interface counters for the fixed glasses peers.
  The first trace and two latest traces are retained. These read-only traces
  help investigate a stalled handshake; denied Android sources are reported
  as unavailable rather than treated as successful communication.
- **Renderer geometry diagnostics independent of logcat.** While debug
  collection is enabled, `renderer-geometry.txt` samples Unity's actual eye
  layout, texture sizes, render viewports and projection matrices every two
  seconds. Bounded evidence survives log throttling and later disconnects.
  This helps investigate stretched experimental Air 2 Pro stereo output;
  it does not change the renderer's geometry or establish a stereo fix.
- **Glasses-window and system-inset diagnostics.** On Android 17,
  `immersive-windows.txt` retains the first and latest connected Presentation
  state, including window flags, focus, inset requests and visible system bars.
  `display.txt` also collects the system's live window focus and inset state.
  Client requests alone do not prove that Android accepted them.
- **Updated troubleshooting and compatibility records.** Documents the Samsung
  Android 17 display loop, Pixel 10 Pro Fold / One Pro startup failure with
  confirmed beta enrollment, and OPPO Air 2 Pro stretched experimental 3D.
  Help explains the additional files and how to capture the issue.

Debug collection remains opt-in. No screenshots, rendered app content or head
poses are added by the new geometry/window collectors. The export retains its
15 MB cap, and nothing uploads automatically. Review logs before sharing.

All features from 0.2.0 alpha remain, including direct glasses output, continued
AR while using other phone apps, controller and screen-off fixes, the accepted
app-color baseline, native phone menus, offline automatic sleep, the app-menu
editor, app updates and optional experimental 3D.

## Installing or upgrading

Install **`the-unbound-launcher-0.2.1-alpha.apk`**. It keeps the package
**`dev.markpronkin.unbound`** and the existing signing certificate. Build 2949
updates 0.1.1, 0.1.2 and 0.2.0 alpha, plus development builds through 2948, in
place while preserving app data, pairing and settings. After installation,
start the updated helper under **Settings → On-device ADB → Connect and start**,
then reconnect the glasses. **Settings → About → Check for updates** can find
this version once it is published.

A clean install is required from 0.1.0 alpha or any build using
`com.xreal.evapro.nebula`. Android does not transfer data between package names.

Requirements remain an arm64 Android 11+ phone with USB-C DisplayPort Alt Mode,
compatible XREAL glasses, Wi-Fi for Wireless debugging setup, and about 1 GB for
the APK. Setup requires Developer options and Wireless debugging. Restart the
helper after reboot. If Android asks how to use the glasses display, choose
**Mirror**. The launcher now checks the model's pinned stable firmware before
AR initialization; use XREAL's official updater when the warning recommends it.

## Validation and known limitations

All **216 host regression tests pass**, with no failures or skips. The package
retains the public 0.2.0 signing certificate, verifies with APK Signature Scheme
v3, and passes 16 KB ZIP alignment. All **228 bundled source files** match the
workspace, including every current runtime source. The 87 existing native
libraries are byte-identical to 0.2.0; the only added native library is the
read-only renderer diagnostics collector. The independent signed-APK audit
also passes package/version checks, class uniqueness, unchanged permissions,
scoped DEX/native patch restoration and Unity shader loading. Full receipts
are in the local review package. Physical testing of this exact 0.2.1 alpha APK with glasses has not
been performed.

- The reconnect grace period and firmware reader still need confirmation on
  affected physical phone/glasses combinations. The firmware gate can warn or
  pause startup; it does not repair an incompatible native handshake.
- Samsung Android 17 reconnect loops and control-channel timeouts remain under
  investigation. Stable firmware does not explain every Samsung report.
- Pixel 10 Pro / One Pro's VGA-only link and Pixel 10 Pro Fold / One Pro's native
  startup failure remain unresolved. The Fold's beta enrollment is confirmed;
  successful recovery on stable firmware has not been established for that phone.
- OPPO CPH2791 / Air 2 Pro can show content, but experimental 3D remains stretched
  and unusable in the report. Correct 3840 × 1080 output alone does not prove
  correct per-eye rendering.
- Earlier Pixel reconnect-related audio distortion and a temporary freeze,
  remaining app-color differences, and hardware-dependent input/Bluetooth
  issues are not claimed fixed. Experimental ROG XREAL R1 support remains unverified.
- Protected/DRM video in spatial windows and Miracast receiving remain unsupported.
  Display, GPU and firmware behavior vary by device.

See the [README](https://github.com/MarkPronkin/the-unbound-launcher#readme) and
its linked investigations for setup and the current compatibility evidence.

This is an independent, fan-made alpha based on XREAL's original software,
unaffiliated with XREAL. XREAL-origin code and artwork remain XREAL's property.
The author's original code is licensed under the MIT License; third-party
components retain their own licenses. Notices and pairing-library sources are
bundled in the APK.
