# Unbound Launcher 0.2.2 alpha

Android build **2953**. Includes all committed and local changes since
**0.2.1 alpha (build 2949)**, including development builds 2950–2952.

## Changes since 0.2.1 alpha

- **Immersive windows work on the Pixel's Mali GPU.** Entering immersive mode
  now respects the existing GPU capability fallback instead of forcing a
  compositor overlay that cannot link its shader on the Pixel 8 Pro. Affected
  GPUs keep the app on the working copy surface, avoiding the frozen picture
  and washed-out colors after leaving immersive. GPUs supporting the required
  capability retain the original overlay path. Immersive entry and exit are
  wearer-confirmed on Pixel 8 Pro / XREAL One; the OnePlus retains its hardware
  overlay path. [Investigation](reports/universal-app-colors.md).
- **Video keeps playing when leaving the phone controller.** The launcher
  retains a live Unity render surface before Android destroys the phone's
  SurfaceView, waits for a real frame, and restores the phone surface on return.
  This fixes the case where audio and head tracking continued while the glasses
  reprojected a frozen image after pressing Home. Continued video playback is
  wearer-confirmed on Pixel 8 Pro / XREAL One with the revised development
  build 2951. [Investigation](reports/direct-glasses-display.md#frozen-content-after-leaving-the-controller--build-2951).
- **Optional All files access for local photos and videos.** The permission
  checklist now opens Android's **All files access** page so the bundled photo
  and video players can access local files beyond scoped media access. The
  permission remains optional; its status refreshes when returning to the app.
- **Screen-off controls preserve gamepad focus.** The confirmation and phone
  touch guard no longer take input focus away from the spatial game. The guard
  continues blocking phone touches. Either physical volume button restores the
  panel through the independent helper watchdog; the client then removes the
  guard and restores the pointer. If volume-button recovery is unavailable, the
  panel stays on. A wake press can also change Android's volume. Emulator checks
  cover global gamepad buttons, joystick input and hardware-key recovery;
  physical screen-off play with glasses and a gamepad still needs validation.
  Restart the updated helper after installing.
  [Implementation and checks](reports/screen-off-gamepad-focus.md).
- **Start in screen mirroring is respected on reconnect.** USB attachment,
  helper-ready recovery and queued automatic entry now read the same saved
  choice as Settings. Mirroring skips automatic AR entry and helper startup;
  a connection already in progress can complete without waiting for AR frames
  that will never arrive. Turning the setting off restores automatic AR entry.
  Physical unplug/reconnect is wearer-confirmed to open mirroring directly on
  Pixel 8 Pro / XREAL One with development build 2952.
  [Investigation](reports/connection-startup-recovery.md#respect-start-in-screen-mirroring--build-2952).
- **Updated compatibility investigations.** A Galaxy Z Fold 8 / XREAL One
  report on the recommended stable firmware shows native control traffic
  leaving through Wi-Fi after Android rejects interface binding. The Pixel 10
  Pro / One Pro remains limited to 640 × 480 after its stereo switch, with no
  native stereo mode advertised by the driver. These are documented findings,
  with proposed next steps; this release does not implement fixes for either
  blocker. [Fold 8 report](reports/samsung-fold8-one-v021.md),
  [Pixel 10 Pro report](reports/pixel10-pro-one-pro-v012.md),
  [Pixel 10 Pro Fold comparison](reports/pixel10-fold-one-pro-v020.md).

All earlier features remain, including direct glasses output, the per-model
firmware check, reconnect grace period, optional diagnostics, native phone
menus, app-menu editing, automatic sleep, app updates and experimental 3D.

## Installing or upgrading

Install **`the-unbound-launcher-0.2.2-alpha.apk`**. It keeps package
**`dev.markpronkin.unbound`** and the existing signing certificate. Build 2953
updates public 0.1.1, 0.1.2, 0.2.0 and 0.2.1 alpha, plus development builds
through 2952, in place while preserving app data, pairing and settings.

After installation, start the updated helper under
**Settings → On-device ADB → Connect and start**, then reconnect the glasses.
The screen-off client requires the updated helper. Optional local-media access
can be enabled under **App permissions → All files access**.
**Settings → About → Check for updates** can find 0.2.2 once it is published.

A clean install is required from 0.1.0 alpha or any build using
`com.xreal.evapro.nebula`. Android does not transfer data between package names.
Requirements remain an arm64 Android 11+ phone with USB-C DisplayPort Alt Mode,
compatible XREAL glasses, Wi-Fi for Wireless debugging setup, and about 1 GB for
the APK. Setup requires Developer options and Wireless debugging; restart the
helper after reboot. If Android asks how to use the glasses display, choose
**Mirror**. The existing firmware gate checks the model's pinned stable release
before AR initialization.

## Validation and known limitations

All **222 host regression tests pass**, with no failures or skips. The separate
signed-APK audit passes package/version checks, class uniqueness, scoped
DEX/native patch restoration and Unity shader loading. The release build also
passes APK Signature Scheme v3 verification and 16 KB ZIP alignment.
Full verification receipts are recorded in the local review package's
`validation.json`. The exact 0.2.2 alpha APK has not been tested with physical
glasses. Wearer confirmations above refer to the named development builds.

- Screen-off gamepad behavior is validated in an Android fixture with production
  code and controller resources; end-to-end physical panel-off play remains
  unverified. Volume recovery requires readable hardware input nodes.
- Galaxy Z Fold 8 / One and Pixel 10 Pro Fold / One Pro native startup failures,
  other Samsung control-channel/reconnect issues, and Pixel 10 Pro / One Pro's
  VGA-only stereo link remain unresolved. Stable firmware alone does not resolve
  every startup failure.
- OPPO CPH2791 / Air 2 Pro experimental 3D remains stretched and unusable in the
  reported session. Correct output resolution does not establish per-eye geometry.
- Earlier reconnect-related audio distortion and a temporary freeze, remaining
  app-color differences, hardware-dependent input/Bluetooth issues and
  experimental ROG XREAL R1 support are not claimed fixed.
- Protected/DRM video in spatial windows and Miracast receiving remain unsupported.
  Display, GPU and firmware behavior vary by device.

See the [README](https://github.com/MarkPronkin/the-unbound-launcher#readme) for
setup and compatibility evidence.

This is an independent, fan-made alpha based on XREAL's original software,
unaffiliated with XREAL. XREAL-origin code and artwork remain XREAL's property.
The author's original code is licensed under the MIT License; third-party
components retain their own licenses. Notices and pairing-library sources are
bundled in the APK.
