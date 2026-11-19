# Unbound Launcher 0.3.1 beta

November 19, 2026 · Android build **2978**

## Changes since 0.3.0 beta

- **Choose when to enter AR Space.** Turn on **Settings → Disable auto connect**
  to leave your glasses in the phone’s default display mode when you plug them
  in. Open Unbound and tap **Enter AR Space** when you want to start a session.
  Automatic entry is the default on new installs. If you previously selected
  **Start in screen mirroring**, the update carries that preference forward
  by enabling **Disable auto connect**.
- **Mute the XREAL Eye recording-start cue.** **Settings → Experimental →
  Disable XREAL Eye shutter sound** now mutes both the photo shutter and the
  cue at the start of a video. The recording-stop cue still plays. The setting
  takes effect immediately.

## Installing or upgrading

Download **the-unbound-launcher-0.3.1-beta.apk** from the release assets, or
use **Settings → About → Check for updates**. The APK is about 1 GB. You can
upgrade in place from 0.3.0 beta or 0.1.1–0.2.6 alpha and keep your app data,
pairing and settings.

After updating, connect to Wi-Fi, open **Settings → On-device ADB → Connect
and start** to restart the helper, then reconnect your glasses. For first-time
setup or migration from 0.1.0 alpha and older My Glasses ports, see the
[installation guide](https://github.com/MarkPronkin/the-unbound-launcher/blob/v0.3.1-beta/README.md#install-and-set-up).

## Validation

All 274 host regression tests passed. The APK passed v3 signature verification
and 16 KB ZIP alignment checks. Its signing certificate matches 0.3.0 beta.

On an Android 13 emulator, the signed APK upgraded 0.3.0 beta in place,
retained the app’s UID and original install time, and opened successfully.
The old mirroring preference migrated to **Disable auto connect**. All 19
settings UI checks and 47 capture-option checks passed.

Physical-glasses testing of build 2978 is pending. Earlier results are listed
by phone, glasses and build in the
[compatibility table](https://github.com/MarkPronkin/the-unbound-launcher/blob/v0.3.1-beta/README.md#status).

Unbound Launcher is an independent, fan-made project based on XREAL’s Beam Pro
software. XREAL retains the rights to its code and artwork. The APK includes
third-party notices and pairing-library sources.
