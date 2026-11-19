# Unbound Launcher 0.3.0 beta

November 19, 2026 · Android build **2977**

Unbound Launcher's first public beta includes the changes developed in builds
2973–2976 since [0.2.6 alpha](https://github.com/MarkPronkin/the-unbound-launcher/releases/tag/v0.2.6-alpha).

## Changes since 0.2.6 alpha

- **Moving apps in AR Space.** Picking up an app now initializes the floating
  icon used while moving it. An ARM64 regression test checks the icon passed
  back to the AR menu.
- **Phone notifications stay on the phone.** Unbound no longer requests access
  to other apps' notifications or mirrors them into AR Space. It still posts
  its own notifications for Wireless debugging pairing and AR session controls.
  [Notification behavior](reports/launcher-notifications.md).
- **Android 15 support.** The launcher now targets Android 15 (API 35). Setup
  keeps pairing inputs and buttons clear of the system bars, display cutouts
  and keyboard. The pairing service and broadcast receivers in the bundled SDK
  now use the declarations required on newer Android versions.
- **Display tests restore the original 2D mode.** Under **Settings → Help →
  Display tests**, the launcher saves the connected display mode and refresh
  rate before switching. The side-by-side (SBS) test can start from an existing
  1080p connection, including 120 Hz. After a test the launcher tries to
  restore the saved 2D timing. On Nubia NX809J with XREAL One Pro, a reporter
  confirmed that development build 2973 restored 1080p120 after both 2D and
  stereo tests. AR and stereo on that combination are still blocked at 640 × 480.
  [Nubia test results](reports/nubia-nx809j-one-pro-v023.md).
- **More useful display failure reports.** Debug exports retain the first
  failed display test alongside the latest test. With log collection enabled,
  the report includes the results of individual glasses mode commands. AR
  startup also reports when the phone continues to offer only 640 × 480.

## Installing or upgrading

Setup requires an arm64 Android 11+ phone with USB-C DisplayPort Alt Mode,
compatible glasses and Wi-Fi for Wireless debugging. The APK is about 1 GB.

Download **`the-unbound-launcher-0.3.0-beta.apk`** from the release assets, or
open **Settings → About → Check for updates** on a supported alpha install.
The beta uses the same Android package (`dev.markpronkin.unbound`) and signing
certificate as releases 0.1.1–0.2.6 alpha. It upgrades those releases and local
builds through 2976 in place, preserving app data, pairing and settings.

After updating, connect to Wi-Fi, open **Settings → On-device ADB → Connect
and start**, then reconnect the glasses. Version 0.1.0 alpha and older My
Glasses ports used a different Android package and require a clean install.
For first-time setup, see the [installation guide](https://github.com/MarkPronkin/the-unbound-launcher/blob/v0.3.0-beta/README.md#install-and-set-up).

## Validation and hardware coverage

All **273 host regression tests** passed. On an Android 13 emulator, the
signed beta upgraded from public 0.2.6 alpha in place, retained the app UID and
original install time, and opened successfully. Android registered zero
notification listeners. The setup and pairing notification checks passed.

The APK passed v3 signature verification and 16 KB ZIP alignment checks.
The signing certificate matches the public alpha. All 267 files in the
bundled source archive match the release workspace.

Physical-glasses testing of build 2977 is pending. Earlier hardware results
are listed by phone, glasses and build in the [compatibility table](https://github.com/MarkPronkin/the-unbound-launcher/blob/v0.3.0-beta/README.md#status).

Unbound Launcher is an independent, fan-made project based on XREAL's Beam Pro
software. XREAL retains the rights to its code and artwork. The APK includes
third-party notices and pairing-library sources.
