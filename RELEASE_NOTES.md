# The Unbound Launcher 0.1.2 alpha

This release collects launcher changes since 0.1.1 alpha. The app and About
screen display **0.1.2 alpha**, with Android version code **2924**.

## Installing or upgrading

**Install this over 0.1.1 alpha.** The package stays
**`dev.markpronkin.unbound`** and the signing certificate is unchanged. Android
version code 2924 is higher than the public 0.1.1 alpha (2908) and the later
development builds through 2923, so an in-place update keeps app data, pairing
and settings. **Settings → About → Check for updates** can download this
release after it is published. You can also install
`the-unbound-launcher-0.1.2-alpha.apk` directly.

**A clean install is still required from 0.1.0 alpha** or any build that used
`com.xreal.evapro.nebula`, including older ports shown as My Glasses. Android
treats that package as a different app and does not transfer its data. Uninstall
those builds first, then grant permissions, pair Wireless debugging and complete
setup again.

Use an arm64 Android 11+ phone with wired USB-C DisplayPort Alt Mode, compatible
XREAL glasses, Wi-Fi for Wireless debugging setup, and approximately 1 GB for
the APK. Open Unbound, accept the disclaimer, grant Display over other apps and
follow on-phone ADB pairing. Once Ready, tap Continue, review the terms and
privacy policy, and tap Agree and finish. Plug in the glasses. If Android asks
how to use the display, choose **Mirror**. After a reboot, enable Wireless
debugging again and use Connect and start.

## Changes since 0.1.1 alpha

- **Direct glasses display.** AR runs through the SDK's native glasses
  Presentation from the phone. Unbound does not put a Desktop or DeX task on
  the glasses. The helper routes XREAL displays to Mirror, remembers the
  previous connection choice, and restores it when the glasses disconnect, the
  phone locks, or the helper stops. A temporary stereo mode still corrects a
  640 × 480 fallback and is restored on disconnect.
- **Phone connection screen.** Connecting shows a dark full-screen dot-matrix
  sequence for USB, glasses and the trackpad pointer. Only a successful
  controller handoff waits for the short transition. Reduced motion, retry and
  cancel are included. The launcher startup theme is opaque black.
- **Startup on more phones.** USB type 11 glasses, seen on Sony Xperia, can
  finish startup when the SDK's startup query is unsupported. Android 12 no
  longer aborts the renderer because `Display.Mode.getSupportedHdrTypes` is
  missing. The glasses ethernet ping runs through the paired helper, and stale
  ping callbacks are cancelled, so startup does not stall when the app itself
  cannot ping the glasses network.
- **Debug exports.** With Debug mode on, creating a debug file also captures
  `display.txt` and `surfaceflinger.txt` through the on-device helper, plus
  connection network diagnostics. Keep the glasses connected until the file is
  ready. Each display dump keeps up to 1 MB. Nothing is uploaded automatically.
- **App-window color.** GPUs that reject the external sRGB override, including
  a virtual-display video path used by apps such as YouTube, get another
  software decode. This exact color change has not been confirmed on glasses.
  YouTube can still look washed out.

Everything from 0.1.1 alpha remains, including automatic AR Space entry,
connection recovery, skip-OTA, direct glasses-launcher startup, inline terms,
GitHub app updates, experimental app/game detection, optional 3D SBS, and Help.

## Validation and known limitations

The direct-display development builds 2916–2919 were exercised on a Pixel 8 Pro
with XREAL One. This 0.1.2 alpha rebuild has not had a separate glasses test.
The YouTube color path in this tree was not installed before this release; on
build 2923 the picture was still washed out.

The release APK passes all **171 host regression tests**, the packaged-code and
resource audit, bundled-source comparison, signing-certificate verification and
16 KB native-library alignment checks. Android application debugging is disabled.

- Other phones and glasses, experimental SBS, manufacturer gaming dashboards,
  and the latest app-window color path still need testing.
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
