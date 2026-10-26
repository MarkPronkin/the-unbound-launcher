# The Unbound Launcher 0.1.0 alpha

The first public alpha brings the XREAL Beam Pro spatial desktop to compatible
Android phones with wired USB-C DisplayPort output. Install one APK and pair it
with the phone's own Wireless debugging service; setup needs no computer or root.

This launch build includes all current work through the 0.1.15 development build,
the corrected XREAL USB attach/permission handling and the ADB setup navigation fix.
The app and About screen display **0.1.0 alpha**. Android version code **2893**
allows upgrades from earlier development builds signed with the same key.

## Included

- Up to ten floating Android app windows, anchored and smooth-follow modes,
  spatial mouse and vertical/horizontal trackpad controls.
- On-phone ADB pairing, a bundled shell helper, setup guidance for different
  phone manufacturers, and compatible helper publication on Android 11+.
- Fixed Continue to ADB and Finish setup when opening the app from its home-screen
  icon. Setup now opens the next screen reliably instead of closing the task.
- Removal of Android's USB “Open Unbound to handle XREAL One?” confirmation,
  which Pixel places on the glasses' desktop. Open Unbound on the phone; USB
  attach/detach detection remains active.
- Automatic XREAL USB access after on-device pairing, including the lower-level
  SDK and its retry timer. A separate shell worker avoids Android 17's USB
  parcel-decoding failure. Grants are per connection, with Android privacy
  checks and both SDK callback paths preserved.
- Automatic output on identified XREAL displays where supported, stereo startup
  and reconnect recovery, and a watchdog that restores temporary display policy.
- App-window color handling selected from GPU capabilities, higher-resolution
  headset icons, and Recents capture compatibility with newer Android versions.
- App-menu search, drag to reorder, hide/show controls and a headset preview;
  drafts and Undo survive layout changes.
- Black OLED interface, foldable/tablet and landscape layouts, large-text
  handling, original glasses artwork and clearer Authority management controls.
- Phone screen-off control, AR Space keep-awake behavior, buffered presentation,
  medium default window size, Wi-Fi/Bluetooth shortcuts, and fixes for trackpad
  taps, icon placement, Chrome spatial launches and slow app launches.
- XREAL analytics removal and disabled Beam-only firmware/boot entry points.
- The public APK disables Android application debugging; on-phone ADB pairing
  and the bundled shell helper remain available.
- The bundled pairing sources exclude local Finder metadata, caches and
  credentials, and include the scoped project license.

## Requirements and setup

Use an arm64 Android 11+ phone with wired USB-C DisplayPort Alt Mode, compatible
XREAL glasses, Wi-Fi, and approximately 1 GB of storage for the APK. These are
prerequisites; each phone/glasses combination still needs testing.

Open the launcher, accept the disclaimer, grant Display over other apps, and
follow the on-phone Wireless debugging pairing flow. Once Ready, finish setup,
connect the glasses and enter AR Space. If Android asks, choose Desktop.
After rebooting, enable Wireless debugging again and use Connect and start.

## Known limitations and validation

- Physical validation covers XREAL One with OnePlus 15 and Pixel 8 Pro at the
  feature revisions recorded in the README. OnePlus automatic output is
  wearer-confirmed. This exact launch APK still needs a physical-device smoke
  test; broader manufacturer compatibility and other glasses remain untested.
- Pixel Recents previews, automatic color selection, sharper icons and desktop
  bar behavior still need wearer confirmation on the combined build.
- Developer options and Wireless debugging are required. Restart the bundled
  helper after a reboot; pairing may need to be repeated.
- Protected/DRM video in spatial windows and Miracast receiving are unsupported.
- The download is approximately 1 GB. Display and GPU behavior vary by phone.

The signed APK passes the full packaged-code/resource audit and native display
checks. Signature, alignment, versions and upload checksum are verified. The
setup correction passes 10 host tests and 53 Android emulator checks, including
launcher-icon startup and finishing setup with a live shell helper. The Pixel's
failed handoff was traced and the user confirmed that setup works after installing
build 2893. USB reconnect and fresh-install behavior still need separate testing.

The prior USB correction passed 31 focused tests, with its corrected helper
probe validated on the Pixel. That helper's DEX payloads are unchanged in this
build. The earlier launch baseline's two known native-emulator SIGILL failures
remain recorded separately in the validation report.

This is an independent, fan-made alpha based on XREAL's original software.
It is not affiliated with or endorsed by XREAL. Original software rights remain
with XREAL. All code originating from XREAL belongs to XREAL; the project's
author licenses only their original code under the MIT License in `LICENSE`.
Third-party components retain their own licenses. Third-party notices and
pairing-library sources are bundled in the APK.

## GitHub release assets

Use tag **v0.1.0-alpha**, title **The Unbound Launcher 0.1.0 alpha**, and mark the
release as a prerelease. Copy the release text above into the release description.
The local package is in `artifacts/releases/v0.1.0-alpha/`:

- `the-unbound-launcher-0.1.0-alpha.apk`
- `the-unbound-launcher-0.1.0-alpha.apk.sha256`
- `validation.json`

Upload the APK and checksum as release assets. Keep the signing key private and
retain it for future updates. After uploading, use the APK's download URL for the
website's `UNBOUND_DOWNLOAD_URL` setting described in `website/README.md`.
