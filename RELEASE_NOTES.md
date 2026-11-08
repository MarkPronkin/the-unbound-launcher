# Unbound Launcher 0.2.5 alpha

Android build **2967**, released **2026-11-08**. This release replaces the
withdrawn **0.2.4 alpha (build 2962)** and includes **all changes since the last
available release, 0.2.3 alpha (build 2957)**, including development builds
2958–2966. The complete 0.2.4 changelog is carried forward below; its fixes are
included in this APK.

## New in 0.2.5 alpha

- **On-device ADB recovers across VPN and network changes.** Pairing and ADB
  sockets explicitly use loopback, discovery follows physical Wi-Fi even when
  a VPN is the default network, and bounded retries handle changed routes,
  late discovery results and interrupted startup. Stale discovery callbacks
  are discarded. A running helper is reused without restarting it. VPN policies
  that block local access still require a settings change; real phone/VPN
  validation is pending. [Implementation and checks](reports/vpn-adb.md).
- **Clearer setup and recovery for use on mobile data.** Setup now requires
  USB debugging alongside Wireless debugging, checks that USB debugging is
  enabled before launching the helper, and shows live **Use without Wi-Fi**
  guidance. Once the helper is Ready, it can continue through local Binder
  calls after Wi-Fi disconnects. Known local ports are tried before discovery,
  and offline failures explain recovery. A reboot or stopped helper still
  requires Wi-Fi to start again. Physical cellular and vendor-specific helper
  retention checks remain pending. [Details](reports/mobile-data-adb.md).
- **The phone controller's invisible keyboard mask allows touches through.**
  Both creation and reattachment preserve the mask's keyboard role while
  allowing taps and drags to reach the controller. Android emulator checks
  reproduce the old interception and verify the correction. This targets the
  Honor Magic 6 Pro controller report; its cause and the fix still need an
  affected-device retest. [Investigation](reports/honor-magic6-touch-v023.md).
- **App windows use the copy-rendering fallback when DRM readiness fails.**
  Optional protected overlays now require both GPU support and a successful
  local Widevine session check. Failure, interruption or timeout keeps the copy
  route, and late probe completion cannot change a running session. This targets
  the protected-rendering crashes captured on Moto Edge 2024; it does not enable
  protected video or claim to fix every app failure or lag report. Hardware
  validation remains pending. [Investigation](reports/moto-edge-2024-one-pro-v023.md).
- **Restored Settings → Experimental → Use 60 Hz in 2D.** The original option
  requests 1920 × 1080 mono at 60 Hz for models whose normal startup is 2D.
  Existing stereo defaults are preserved. It is off by default, mutually
  exclusive with Force 3D, and requires reconnecting. Earlier saved preferences
  work again in both the Java SDK and Unity selectors. [Policy and checks](reports/experimental-stereo.md).
- **Debug exports retain connected startup display evidence.** Display changes
  are recorded for 90 seconds after SDK startup independently of logcat.
  `display-startup.txt` keeps the first non-native-output snapshot and the latest
  startup snapshot, so unplugging before export does not erase the connected
  mode lists. Captures include bounded display/compositor dumps, USB port state,
  preceding system/vendor link logs and available connector/EDID data.
  Permission-denied sources remain visible. This improves investigation of
  VGA-only output; it does not restore missing hardware display modes.
- **Expanded compatibility evidence.** New Honor, Moto and Nubia reports separate
  confirmed startup/rendering behavior from suspected causes and pending fixes.
  Nubia NX809J / One Pro repeatedly falls back to 640 × 480 after successful SDK
  startup; that missing-native-mode failure remains unresolved.
  [Nubia investigation](reports/nubia-nx809j-one-pro-v023.md).

## Included from withdrawn 0.2.4 alpha

All of the following changes from 0.2.4 are included in 0.2.5:

- **Glasses network selection now covers the renderer process.** The main SDK
  callback and Unity renderer share scoped Ethernet network leases. The renderer
  selects the glasses network before startup and again after display switches,
  then restores routing one second after the native Presentation attaches, or
  immediately when startup fails or the session closes. This addresses the
  Pixel 10 Pro Fold's second control connection leaving through Wi-Fi after the
  main controller had already started. The reporter confirms working AR Space
  with his implementation of this design; this exact release APK still needs a
  hardware retest. New internet sockets and DNS in the renderer temporarily use
  the glasses network during startup. [Evidence and implementation](reports/pixel10-fold-one-pro-v020.md).
- **AR notifications distinguish startup from output.** The foreground service
  shows **Starting AR Space** while waiting for a glasses Presentation, then
  **AR Space is running** once it attaches. Failed startup retains its timeout
  and cleanup instead of leaving a misleading running notification.
- **Opening apps preserves existing anchored windows.** Existing window
  positions, rotations and sizes remain in place when another app opens in
  anchored 3/6DoF mode. Smooth-follow layout and manual recentering retain their
  existing behavior. Host ARM64 checks and signed-APK inspection verify the
  patch; physical glasses validation is pending. [Details](reports/anchor-windows.md).
- **Removed the separate high performance app restriction.** Opening another
  app no longer asks to close the current high performance app. The overall
  ten-window limit remains. Device performance and usable app combinations
  still depend on the phone; hardware validation is pending.
- **New Eye and AR videos omit the branded recording logo.** This changes only
  the video overlay; photo logos and previously saved videos are unchanged.
  Encoding, audio, resolution, blending, elapsed-time display and saving retain
  their existing paths. Physical recording and gallery playback validation are
  pending. [Implementation and tests](reports/recording-logo.md).
- **Guarded app-window rotation correction.** The helper reconciles logical and
  fixed device rotation when a launcher virtual display reports reversed
  dimensions and its own metadata confirms the offset. This targets compressed
  or letterboxed Files content on the Retroid Pocket Nova. Missing or ambiguous
  metadata keeps the original behavior. The Retroid/glasses visual retest is
  pending. Restart the updated helper after installation. [Investigation](reports/retroid-window-aspect-ratio.md).
- **ROG XREAL R1 has its own firmware policy.** Its exact ASUS USB identities
  select the MCM103 glasses release, Pilot `1.9.0.20260709183133` and DSP
  `15.A.01.097_20250430`, with the ASUS support link in firmware warnings.
  Startup diagnostics record each returned firmware component. A wearer has
  confirmed R1 operation on compatible firmware; this new reader/policy path
  still needs an R1 hardware retest. [Firmware evidence](reports/rog-xreal-r1.md#firmware-detection).
- **Updated compatibility reports and website.** The Pixel Fold report now
  separates successful main-controller startup from the renderer failure and
  records the reporter's working fix. The OPPO Air 2 Pro follow-up confirms
  two-eye Unity rendering while the reported stereo problem remains unresolved.
  The website highlights Eye recording and wearer-confirmed R1 operation.


Earlier features remain, including recording without the fixed launcher time
limit, adaptive-brightness filtering, immersive-window and background-playback
fixes, optional All files access, screen-off gamepad handling, mirroring startup
preferences, app updates and experimental 3D.

## Installing or upgrading

Install **`the-unbound-launcher-0.2.5-alpha.apk`**. It keeps package
**`dev.markpronkin.unbound`** and the existing signing certificate registered
with the owner's Android developer account. Build **2967** updates public
releases from 0.1.1 through 0.2.3 alpha, the withdrawn 0.2.4 alpha, and local
development builds through 2966 in place, preserving app data, pairing and
settings. The signer is checked against public 0.2.3 and the recorded developer
registration fingerprint. [Package registration](reports/package-identity.md).

After installation, enable **USB debugging** and **Wireless debugging**, then
restart the updated helper under **Settings → On-device ADB → Connect and start**
on Wi-Fi. Reconnect the glasses. **Settings → About → Check for updates** can
find 0.2.5 once published. Once the helper is Ready, keep USB debugging enabled
when leaving Wi-Fi to use mobile data. If the helper stops, return to Wi-Fi and
start it again. For a startup retest, enable debug collection before connecting
and leave the glasses connected through export if startup fails.

A clean install is required from 0.1.0 alpha or any build using
`com.xreal.evapro.nebula`. Android does not transfer data between package names.
Requirements remain an arm64 Android 11+ phone with USB-C DisplayPort Alt Mode,
compatible glasses, Wi-Fi for Wireless debugging setup, and about 1 GB for the
APK. Setup requires Developer options; restart the helper after reboot. If
Android asks how to use the glasses display, choose **Mirror**. The firmware
gate checks the model's pinned stable release before AR startup.

## Validation and known limitations

All **250 host regression tests pass**, with no failures or skips. The separate
signed-APK audit verifies package/version, class uniqueness, scoped DEX/native
patch restoration, current bundled sources and Unity shader loading, including
the restored 2D 60 Hz policy. The APK passes APK Signature Scheme v3 verification
and 16 KB ZIP alignment, with application debugging disabled. Its certificate
matches public 0.2.3, withdrawn 0.2.4 and the recorded Android developer
registration. The local release package's `validation.json` records these checks
and source correspondence. No new Android runtime or physical-glasses test was
performed for this version-only release rebuild.

The exact 0.2.5 alpha APK has not been tested with physical glasses. The linked
reports describe earlier development-build Android/emulator checks and wearer
confirmations; those are not new physical-device tests of this release.

- VPN transitions, mobile-data helper retention, the Honor touch correction,
  Moto app-window fallback and restored 2D 60 Hz need affected-device retests.
- Renderer network selection still needs affected-phone reconnect/resume and
  later native reconnection checks. Samsung startup fixes remain pending
  hardware validation.
- Anchored-window placement, concurrent high performance apps, logo-free video,
  the Retroid geometry correction and the new R1 firmware reader need their
  respective physical-device retests.
- Adaptive-brightness filtering and screen-off gamepad behavior still need
  wearer validation. Volume recovery requires readable hardware input nodes
  and the updated helper.
- Pixel 10 Pro and Nubia NX809J / One Pro's VGA-only stereo links, Samsung / 1S
  control-channel timeouts, and OPPO CPH2791 / Air 2 Pro's experimental 3D remain
  unresolved.
- Earlier reconnect-related audio distortion and a temporary freeze, remaining
  app-color differences and hardware-dependent input/Bluetooth issues are not
  claimed fixed.
- Protected/DRM video in spatial windows and Miracast receiving remain
  unsupported. Display, GPU and firmware behavior vary by device.

See the [README](https://github.com/MarkPronkin/the-unbound-launcher#readme) for
setup and compatibility evidence, and the
[0.2.3 release](https://github.com/MarkPronkin/the-unbound-launcher/releases/tag/v0.2.3-alpha)
for earlier changes.

This is an independent, fan-made alpha based on XREAL's original software,
unaffiliated with XREAL. XREAL-origin code and artwork remain XREAL's property.
The author's original code is licensed under the MIT License; third-party
components retain their own licenses. Notices and pairing-library sources are
bundled in the APK.
