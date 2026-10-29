# Unbound Launcher 0.2.0 alpha

This release collects all launcher changes since **0.1.2 alpha (build 2924)**,
including the development work through build 2945. The app and About screen
display **0.2.0 alpha**, with Android build number **2946**.

## Installing or upgrading

**Update in place from 0.1.1 alpha or 0.1.2 alpha.** The package remains
**`dev.markpronkin.unbound`**, with the same signing certificate. Build 2946 is
higher than the public releases and development builds through 2945, so Android
can preserve app data, pairing and settings. Install
`the-unbound-launcher-0.2.0-alpha.apk`, or use **Settings → About → Check for
updates** after the release is published. Start the updated helper under
**Settings → On-device ADB → Connect and start**, then reconnect the glasses.

**A clean install is still required from 0.1.0 alpha** or any build using
`com.xreal.evapro.nebula`, including older ports shown as My Glasses. Uninstall
those builds first, then grant permissions, pair Wireless debugging and complete
setup again. Android does not transfer data between these package names.

Requirements remain an arm64 Android 11+ phone with wired USB-C DisplayPort Alt
Mode, compatible XREAL glasses, Wi-Fi for Wireless debugging setup, and about
1 GB for the APK. No root or computer is required for setup. If Android asks
how to use the glasses display, choose **Mirror**. After a reboot, enable
Wireless debugging again and use **Connect and start**.

## Changes since 0.1.2 alpha

### AR startup and phone use

- **Smoother connection handoff.** The connection animation stays visible until
  the phone controls and native-resolution glasses Presentation have drawn.
  The status changes to **Starting AR Space** while those windows become ready.
  This fixes an early transition and a later startup stall caused by checking
  optional pointer visuals. Retry, cancellation and reduced motion are retained.
- **AR Space keeps running when you use another phone app.** An ongoing
  **AR Space is running** notification returns to the controller and provides
  **Stop AR**. The session ends on glasses removal, phone lock or explicit exit.
- **Phone screen-off works with the embedded helper.** Corrects a conflicting
  helper command and suspends phone-motion pointing, the laser, reticle and
  touch input while the panel is off. Either volume button wakes the phone and
  restores the previous pointer state while glasses output continues.
- **Better separation from ordinary external monitors.** Glasses-only display
  overrides are released on unplug, including when Android reuses a display ID
  or another pair of glasses remains connected. Ordinary monitors keep their
  mirroring/desktop choices and cannot authorize glasses-only screen-off or
  keep-awake behavior.

### Controller, keyboard and notifications

- **Controller-mode crash fixes.** Spatial Mouse and both trackpad modes keep
  their Beam-specific pointer visibility state inside the app. Both the service
  and direct input helper stop attempting protected system-settings writes,
  including requests triggered by mouse device changes.
- **Interrupted Home holds no longer recenter the pointer later.** Canceling a
  touch, moving outside Home, adding another finger, leaving the controller or
  destroying its view cancels the pending reset. An intentional two-second Home
  hold still works.
- **Keyboard visibility follows the actual IME.** System bars and display
  cutouts no longer count as an open keyboard on Android 11+. This addresses
  the persistent Enter prompt and incorrect keyboard state in the OPPO report.
- **Three-finger trackpad gesture protection.** On phones exposing the shared
  Oplus gesture framework, conflicting screenshot and split-screen gestures are
  temporarily suspended during trackpad use and restored afterward. This is
  wearer-confirmed on OnePlus; OPPO and Realme hardware testing is pending.
- **Bluetooth enumeration tolerates unsupported profiles.** An optional profile
  rejected by the phone no longer aborts connected-device enumeration. This does
  not establish that every reported Samsung mouse or stability issue is fixed.
- **Notifications can reach the AR renderer without restricted intent
  inspection.** Notifications with tap actions no longer fail during conversion.
  Taps use the renderer's app-opening fallback; opening the exact conversation
  or notification deep link is not supported by this change. Visual appearance
  and tap behavior in the glasses still need wearer confirmation.

### App-window colors and graphics

- **Reworked app-window color handling.** Removes the experimental compositor
  gamma corrections, uses precise software sRGB decoding where needed, and
  preserves the working hardware path on supported GPUs. On GPUs without the
  required override, the SDK's app-window routing hint selects the copy path.
  Actual secure-window restrictions remain in force; this does not enable DRM
  capture.
- **Correctly packaged Unity shaders.** Preserves shader metadata and refreshes
  cache identifiers so Unity can load the updated programs. Build checks now
  execute Unity's native shader reader. OnePlus colors were wearer-confirmed;
  Pixel colors improved substantially, with a slight remaining difference.
  This release preserves that accepted color baseline without further tuning.
- **Mali graphics compatibility correction.** Fixes an incorrect EGL colorspace
  attribute in one glasses profile, addressing the graphics defect found while
  investigating blank Air 2 Pro output on OPPO. The defect and correction were
  reproduced on a physical Pixel Mali GPU; OPPO/glasses confirmation is pending.

### Glasses compatibility

- **Experimental ROG XREAL R1 recognition.** Recognizes its specific ASUS USB
  and display identities, adds its name and artwork, and tries the existing
  One Pro Ethernet/stereo profile. Physical R1 stereo output, tracking and
  optical alignment remain unverified.
- **Recovery when Android Ethernet is disabled.** After a failed glasses ping,
  the paired helper can attempt to enable an explicitly disabled Ethernet
  service once per attachment. It rechecks the live glasses identity and still
  requires a real successful ping and native startup handshake. It does not
  alter IP configuration. Recovery on the affected Samsung hardware is pending.
- **Experimental stereo selector correction.** Passes the Android context to
  the Force 3D selector correctly for G-series glasses. This removes the hook
  error; it does not fix the Pixel 10 Pro / One Pro report whose display link
  exposes only 640 × 480 output.

### Phone menus and diagnostics

- **Unbound Launcher naming and About details.** The Android app label and
  phone UI consistently use **Unbound Launcher**, without “The”. About shows
  the app version, original MyGlasses version and Android build number.
  The package ID and APK filename prefix are unchanged.
- **Native menus with fade transitions.** About, Help and the other phone
  menus use consistent Android Activity navigation, preserve page state across
  recreation, and use an explicit fade without an upward slide. Completed debug
  exports remain available when Help is reopened.
- **Automatic sleep can be configured offline.** Its OLED-style menu is
  available under Experience with the glasses disconnected. It retains the
  original timeout choices, saves an offline selection, and applies it after a
  supported connection accepts the value. Failed or unsupported writes retain
  the pending choice.
- **More useful debug exports.** Adds USB/display identity and DisplayPort link
  evidence in `display-link.txt`, a persistent first-failure startup timeline in
  `glasses-startup.txt`, and bounded control-channel snapshots in
  `glasses-control.txt`. Attempts, native timeouts, routes and interface counters
  help distinguish display-link, Ethernet and SDK failures. Missing or denied
  sources are recorded. The ZIP stays capped at 15 MB; collection is opt-in and
  nothing is uploaded automatically. Review logs before sharing them.

Everything from 0.1.2 alpha remains, including direct glasses display, ten app
windows, the app-menu editor, GitHub updates, automatic USB entry, inline setup,
skip-OTA and optional 3D SBS on otherwise unsupported glasses.

## Validation and known limitations

All **210 host regression tests pass**. The signed release APK also passes the
independent package audit: version and package identity, unchanged permission
set, class uniqueness, scoped DEX/native patch restoration, shader loading,
bundled-source correspondence, signing and 16 KB native-library alignment.
All 220 bundled source files match the workspace. The signing certificate
matches public 0.1.2 alpha, and Android application debugging is disabled.

The exact 0.2.0 alpha APK has not had a separate physical glasses test. Earlier
development builds were tested on Pixel 8 Pro / XREAL One for connection
handoff, screen-off/pointer restoration and continued AR while using phone apps,
and on OnePlus 15 for three-finger trackpad gestures and app-window colors.
The linked investigation reports distinguish wearer checks from emulator,
host and diagnostic checks.

- Pixel 10 Pro / One Pro's VGA-only display-link failure and Samsung / XREAL
  1S control-channel timeouts remain unresolved. Enabling Ethernet does not
  bypass a failed native handshake.
- OPPO input and Air 2 Pro graphics fixes, Samsung Ethernet recovery and mouse
  reconnect behavior, R1 support, automatic sleep and external-monitor handoff
  still need confirmation on the affected hardware.
- Pixel app colors retain a slight reported difference from OnePlus. The older
  reconnect-related audio distortion and temporary freeze remain unresolved.
- Developer options and Wireless debugging remain required. Restart the helper
  after reboot; pairing may need to be repeated.
- Protected/DRM video in spatial windows and Miracast receiving are unsupported.
- The APK is about 1 GB. Display, GPU and firmware behavior vary by device.

See the [README](https://github.com/MarkPronkin/the-unbound-launcher#readme) for
setup, compatibility and links to the detailed investigations.

This is an independent, fan-made alpha based on XREAL's original software,
unaffiliated with XREAL. All XREAL-origin code and artwork remain XREAL's
property. The author's original code is licensed under the MIT License in
`LICENSE`; third-party components retain their own licenses. Notices and
pairing-library sources are bundled in the APK.
