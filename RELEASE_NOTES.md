# Unbound Launcher 0.2.4 alpha

Android build **2962**. Includes all committed and local changes since
**0.2.3 alpha (build 2957)**, including development builds 2958–2961.

## Changes since 0.2.3 alpha

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

Install **`the-unbound-launcher-0.2.4-alpha.apk`**. It keeps package
**`dev.markpronkin.unbound`** and the same signing certificate as public 0.2.3.
Build 2962 updates public releases from 0.1.1 through 0.2.3 alpha and local
development builds through 2961 in place, preserving app data, pairing and
settings.

After installation, restart the updated helper under
**Settings → On-device ADB → Connect and start**, then reconnect the glasses.
**Settings → About → Check for updates** can find 0.2.4 once published.
For a network retest, enable debug collection before connecting, leave Wi-Fi
on, and export a fresh debug ZIP if startup fails.

A clean install is required from 0.1.0 alpha or any build using
`com.xreal.evapro.nebula`. Android does not transfer data between package names.
Requirements remain an arm64 Android 11+ phone with USB-C DisplayPort Alt Mode,
compatible glasses, Wi-Fi for Wireless debugging setup, and about 1 GB for the
APK. Setup requires Developer options and Wireless debugging; restart the helper
after reboot. If Android asks how to use the glasses display, choose **Mirror**.
The firmware gate checks the model's pinned stable release before AR startup.

## Validation and known limitations

All **244 host regression tests pass**, with no failures or skips. The separate
signed-APK audit verifies package/version, class uniqueness, scoped DEX/native
patch restoration, bundled current sources and Unity shader loading. The APK
passes APK Signature Scheme v3 verification and 16 KB ZIP alignment, and its
signing certificate matches public 0.2.3 alpha. The local release package's
`validation.json` records the verification receipts. The signed APK also passes
**54 Android runtime assertions**: 21 for brightness handling, 19 for network
selection/native socket binding and 14 for the original SDK startup callback.
A current-source Android
fixture also passes **70 Presentation/lifecycle assertions**, including startup
notification transitions, timeout cleanup and continued rendering. The current
helper geometry fixture passes **8 Android assertions** on normal virtual
displays; the fixed-offset device case remains covered by host tests.

The exact 0.2.4 alpha APK has not been tested with physical glasses. Wearer
confirmations refer to the implementations and earlier builds described above.

- Renderer network selection still needs affected-phone reconnect/resume and
  later native reconnection checks. Samsung startup fixes remain pending
  hardware validation.
- Anchored-window placement, concurrent high performance apps, logo-free video,
  the Retroid geometry correction and the new R1 firmware reader need their
  respective physical-device retests.
- Adaptive-brightness filtering and screen-off gamepad behavior still need
  wearer validation. Volume recovery requires readable hardware input nodes
  and the updated helper.
- Pixel 10 Pro / One Pro's VGA-only stereo link, Samsung / 1S control-channel
  timeouts, and OPPO CPH2791 / Air 2 Pro's experimental 3D remain unresolved.
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
