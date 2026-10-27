# The Unbound Launcher 0.1.1 alpha

This release collects all launcher changes since 0.1.0 alpha, including the
latest user-confirmed working build. It adds automatic glasses entry, connection
recovery, app updates, experimental settings and debug exports, and moves Unbound
to its own registered Android package. The app and About screen display
**0.1.1 alpha**, with Android version code **2908**.

## Installing or upgrading

**A clean install is recommended: completely uninstall all previous Unbound
Launcher builds before installing 0.1.1 alpha**, including the public 0.1.0 alpha
and development builds, even if an older port appears as My Glasses. Uninstalling
clears those apps' local data. Install the new APK, grant permissions, pair
Wireless debugging and complete setup again; preferences and app-menu settings
must be configured again.

The Android package has changed from `com.xreal.evapro.nebula` to
**`dev.markpronkin.unbound`**. Android treats this as a separate app and does not
transfer data or replace the old package automatically. Removing previous builds
also avoids having two launchers and helpers installed together. Builds 2906 and
2907 already use the new package and technically support an update, but a clean
install is recommended for this alpha. The signing certificate is unchanged;
the new package is registered and its signing key verified in the Android
Developer Console.

Download `the-unbound-launcher-0.1.1-alpha.apk` below. Use an arm64 Android 11+
phone with wired USB-C DisplayPort Alt Mode, compatible XREAL glasses, Wi-Fi
for Wireless debugging setup, and approximately 1 GB for the APK. Open Unbound,
accept the disclaimer, grant Display over other apps and follow on-phone ADB
pairing. Once Ready, tap Continue, review the terms and privacy policy, and tap
Agree and finish. After a reboot, enable Wireless debugging again and use
Connect and start.

## Changes since 0.1.0 alpha

- **Automatic AR Space entry.** Connecting recognized XREAL glasses starts AR
  Space after setup, including when Unbound is in the background. Entry waits
  for the phone to be unlocked and for the paired helper to be ready.
- **Connection and reconnect recovery.** Startup tolerates missing USB launch
  intents, delayed authorization and display changes during mode switching.
  It waits for the requested physical stereo mode and cancels stale connection
  callbacks. Settings refreshes when glasses connect or disconnect.
- **Improved Android 17 connectivity.** App connectivity is improved on Android
  17, including Pixel-series devices and Samsung One UI 9.
- **Skip stuck glasses updates.** Unbound bypasses the glasses firmware update
  flow, including the screen that could remain at 0%. Update glasses firmware
  separately through XREAL's updater.
- **Direct glasses-launcher startup.** The first-use tutorial no longer opens
  automatically. Tutorial remains available from the glasses app menu.
- **Inline setup agreement.** Terms of service and Privacy policy are presented
  after on-device ADB setup, with an explicit Agree and finish action and
  support for interrupted setup, landscape, tablets and large text.
- **GitHub app updater.** Settings → About can check releases, show release
  notes, download with progress and cancellation, and open Android's installer.
  App updates and Check on startup default on; downloads require a tap. The app
  checks the release digest when supplied, package identity, signing certificate,
  Android requirement and newer version before installation.
- **Experimental app/game detection.** Settings → Experimental → Detect as game
  changes Unbound's Android category and shows the confirmed system value.
  Phone gaming-dashboard behavior varies by manufacturer.
- **Experimental 3D SBS.** Force 3D on unsupported glasses optionally requests
  3840 × 1080 side-by-side output at 60 Hz for XREAL models that normally start
  in 2D. It defaults off and applies after reconnecting. Existing stereo
  defaults remain intact; additional glasses still need physical testing.
- **Help and debug exports.** Settings → Help links to the Discord community
  and offers optional debug-data collection, ZIP creation, sharing and saving
  to Downloads. Collection defaults off, nothing uploads automatically, and
  each ZIP is capped at 15 MB. Review logs before sharing personal information.
- **Unbound package identity.** Manifest components, providers, storage, helper
  wiring, updater and native preference paths use `dev.markpronkin.unbound`.
  Original XREAL Java/JNI bindings remain compatible.

All first-alpha features remain included: up to ten spatial app windows,
anchored and smooth-follow modes, phone pointer/trackpad controls, on-phone ADB,
app-menu editing, Recents, phone screen-off and keep-awake controls, adaptive
OLED layouts, automatic app-window colors and sharper headset icons.

## Validation and known limitations

The latest development build was confirmed working by the user. This release
rebuilds it with the new public version name and a higher Android version code.
The release APK passes all **145 host regression tests**, the complete
packaged-code/resource audit, bundled-source comparison, signing-certificate
verification and 16 KB native-library alignment checks. Android application
debugging is disabled. Build 2907 also passed 144 production-package checks and
normal startup in an isolated Android 13 ARM64 emulator; feature-level runtime
evidence is recorded in the linked repository reports.

- XREAL One with OnePlus 15 and Pixel 8 Pro has the recorded physical coverage.
  Other phones and glasses, experimental SBS, manufacturer gaming dashboards,
  and complete pointer/keyboard and newer Recents-preview coverage still need
  testing. The release version rebuild has not had a separate glasses test.
- Developer options and Wireless debugging remain required. Restart the helper
  after reboot; pairing may need to be repeated.
- Protected/DRM video in spatial windows and Miracast receiving are unsupported.
- The APK is approximately 1 GB. Display and GPU behavior vary by phone.

See the [README](https://github.com/MarkPronkin/the-unbound-launcher#readme) for
setup and the full compatibility notes.

This is an independent, fan-made alpha based on XREAL's original software,
unaffiliated with XREAL. All XREAL-origin code and artwork remain XREAL's
property. The author's original code is licensed under the MIT License in
`LICENSE`; third-party components retain their own licenses. Notices and
pairing-library sources are bundled in the APK.
