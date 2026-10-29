# Unbound Launcher 0.2.3 alpha

Android build **2957**. Includes all committed and local changes since
**0.2.2 alpha (build 2953)**, including development builds 2954–2956.

## Changes since 0.2.2 alpha

- **XREAL Eye and AR recordings have no fixed launcher time limit.** The
  recording indicator shows elapsed time instead of a countdown, and the
  launcher no longer stops recording at its former 120-second deadline.
  Manual Stop, encoding, saving and interruption cleanup are preserved.
  The Eye recording-limit change is wearer-confirmed on development build
  2954; specific recording durations and gallery playback were not separately
  reported. Storage, battery, temperature and camera/encoder failures can
  still end a recording. [Details and checks](reports/eye-recording.md).
- **Adaptive brightness no longer triggers repeated Spatial Mouse resets.**
  The stock brightness observer treated every non-zero brightness change as
  a screen unlock, which recentered the controller. The new filter forwards
  only actual zero/non-zero lock transitions, retaining normal screen-lock
  behavior. Host tests and the signed release APK's DEX verify the fix;
  wearer testing with adaptive brightness and glasses is still pending.
  [Investigation](reports/spatial-mouse-brightness-reset.md).
- **Native startup selects the glasses' Ethernet network.** After helper
  readiness succeeds, Ethernet-series glasses now use a verified Android
  Ethernet network during the SDK's synchronous native-startup callback.
  This targets control connections that otherwise leave through Wi-Fi when
  Android denies interface binding. The adapter checks the authorized USB
  attachment, glasses address and direct peer route, then restores the prior
  process network in a `finally` block. Missing or ambiguous networks stop
  startup rather than falling back to Wi-Fi. Air/H-series startup keeps its
  existing path. New internet sockets and DNS lookups in the launcher process
  temporarily use the glasses network during this callback. Host and Android
  runtime checks verify selection, restoration and native socket binding;
  affected-phone AR startup and reconnect/resume still need hardware testing.
  [Implementation and validation](reports/pixel10-fold-one-pro-v020.md#candidate-fix--build-2956).
- **Updated compatibility evidence.** XREAL Eye 6DoF tracking is wearer-confirmed
  as working on 2026-10-29. The Pixel 10 Pro Fold / One Pro still
  fails native startup on released 0.2.2 with the exact recommended stable
  firmware. A new Galaxy S24 Ultra (SM-S928B) / One Pro report shows the same
  app-UID bind restriction and 20-second startup stall. Together with the
  Galaxy Z Fold 8 / One investigation, these are validation targets for the
  network-selection fix above; successful AR on these phones is not yet
  established. [Pixel Fold](reports/pixel10-fold-one-pro-v020.md),
  [Galaxy Z Fold 8](reports/samsung-fold8-one-v021.md),
  [Galaxy S24 Ultra](reports/samsung-s24-ultra-one-pro-v022.md).

All earlier features remain, including immersive-window and background-playback
fixes, optional All files access, screen-off gamepad focus handling, mirroring
startup preferences, per-model firmware checks, app updates and experimental 3D.

## Installing or upgrading

Install **`the-unbound-launcher-0.2.3-alpha.apk`**. It keeps package
**`dev.markpronkin.unbound`** and the existing signing certificate. Build 2957
updates public 0.1.1, 0.1.2, 0.2.0, 0.2.1 and 0.2.2 alpha, plus development
builds through 2956, in place while preserving app data, pairing and settings.

After installation, start the updated helper under
**Settings → On-device ADB → Connect and start**, then reconnect the glasses.
**Settings → About → Check for updates** can find 0.2.3 once published.
For an affected-phone network retest, enable debug collection before the first
connection attempt after reopening the app, leave Wi-Fi on, and export a new
debug ZIP if startup fails.

A clean install is required from 0.1.0 alpha or any build using
`com.xreal.evapro.nebula`. Android does not transfer data between package names.
Requirements remain an arm64 Android 11+ phone with USB-C DisplayPort Alt Mode,
compatible XREAL glasses, Wi-Fi for Wireless debugging setup, and about 1 GB for
the APK. Setup requires Developer options and Wireless debugging; restart the
helper after reboot. If Android asks how to use the glasses display, choose
**Mirror**. The existing firmware gate checks the model's pinned stable release
before AR initialization.

## Validation and known limitations

All **228 host regression tests pass**, with no failures or skips. The separate
signed-APK audit passes package/version checks, class uniqueness, scoped
DEX/native patch restoration and Unity shader loading. The release build also
passes APK Signature Scheme v3 verification and 16 KB ZIP alignment, and its
signing certificate matches public 0.2.2 alpha. Full verification receipts are
recorded in the local release package's `validation.json`. The exact signed APK
also passes **49 Android emulator assertions**: 21 for brightness handling,
14 for network/socket binding, and 14 for the original SDK startup callback.
The exact 0.2.3 alpha APK has not been tested with physical glasses. Wearer
confirmations above refer to the named development builds.

- The network-selection fix targets the leading routing diagnosis. Successful
  native startup and usable AR on the Pixel Fold, Galaxy Z Fold 8 and SM-S928B
  remain unverified. Later control sockets opened outside synchronous startup
  may require further work.
- Adaptive-brightness filtering still needs wearer validation; other causes of
  Spatial Mouse recentering are not claimed fixed.
- Pixel 10 Pro / One Pro's VGA-only stereo link, Samsung / 1S control-channel
  timeouts, and OPPO CPH2791 / Air 2 Pro's stretched experimental 3D remain
  unresolved.
- Screen-off gamepad behavior has Android fixture coverage; physical panel-off
  play with glasses remains unverified. Volume recovery requires readable
  hardware input nodes and the updated helper.
- Earlier reconnect-related audio distortion and a temporary freeze, remaining
  app-color differences, hardware-dependent input/Bluetooth issues and
  experimental ROG XREAL R1 support are not claimed fixed.
- Protected/DRM video in spatial windows and Miracast receiving remain unsupported.
  Display, GPU and firmware behavior vary by device.

See the [README](https://github.com/MarkPronkin/the-unbound-launcher#readme) for
setup and compatibility evidence, and the
[0.2.2 release](https://github.com/MarkPronkin/the-unbound-launcher/releases/tag/v0.2.2-alpha)
for earlier changes.

This is an independent, fan-made alpha based on XREAL's original software,
unaffiliated with XREAL. XREAL-origin code and artwork remain XREAL's property.
The author's original code is licensed under the MIT License; third-party
components retain their own licenses. Notices and pairing-library sources are
bundled in the APK.
