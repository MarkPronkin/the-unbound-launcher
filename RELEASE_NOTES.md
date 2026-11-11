# Unbound Launcher 0.2.6 alpha

Android build **2972**, released **2026-11-11**.
This update includes all changes since **0.2.5 alpha (build 2967)**, including
local development builds 2968–2971 and the app-launch crash correction described
below. Earlier fixes, including those from withdrawn 0.2.4 alpha, remain included.

## Changes since 0.2.5 alpha

- **Fixed the build 2971 crash when opening an app in AR Space.** The revised
  immersive 3D menu could pass an unresolved Unity string literal to localization
  while creating an app window, terminating the renderer. Label metadata is now
  initialized before use. The OnePlus 15 crash logs identify this exact failure;
  a new ARM64 regression reproduces it before the fix and passes afterward.
  The wearer confirms that opening an app in this exact build keeps AR Space
  running on OnePlus 15 / XREAL One. Fresh crash logs show no renderer crash.
  [Investigation](reports/app-launch-crash-v026.md).
- **Guided display tests under Settings → Help.** Check the glasses connection,
  test **1920 × 1080 2D at 60 Hz**, or test **3840 × 1080 SBS at 60 Hz** without
  launching AR Space. The 3D test captures a 2D baseline first and shows a grid
  with one centered circle per eye for 20 seconds when the requested timing is
  available. Tests request a return to 2D when finished, stopped or left; unplug
  and reconnect if recovery fails. Automatic AR entry is paused while the page
  is open. Test history, wearer feedback and bounded display/USB snapshots are
  included in debug exports even with log collection off. Observed timing and
  picture feedback are recorded separately. Physical switching and recovery
  still need hardware validation; these tests do not restore missing modes.
- **Samsung S21 FE readiness correction.** A completed shell ping with the exact
  `SO_BINDTODEVICE: Operation not permitted` error now allows the existing
  control-network validation and native startup to proceed. The wearer reports
  repeated successful AR sessions, stereo rendering and head tracking with their
  equivalent local patch on SM-G990B / One UI 8.0. Other recognized interface
  binding denials use a bounded ICMP probe bound to the Android glasses network.
  Ordinary packet loss, helper timeouts, missing routes and USB cancellation
  retain their failure behavior. This exact release needs an affected-device
  retest. [Evidence and checks](reports/samsung-g990b-one-v025.md).
- **Firmware read failures have their own warning.** Empty, failed or timed-out
  reads display **Firmware check paused**, explaining that the firmware may
  already be current. Exit remains available; continuation stays disabled while
  a read is active and becomes **Continue without verification** after a failed
  read finishes. A confirmed mismatch retains its separate warning. Late results
  update the warning or resume startup appropriately. Diagnostics record each
  component's start, completion duration and errors. This corrects the misleading
  S26 Ultra warning; its USB-read stalls and native control failure remain
  unresolved. [Investigation](reports/samsung-s26-ultra-one-pro-v025.md).
- **Three direct immersive 3D choices.** The picker shows **Half SBS**,
  **Full SBS** and **Half Top/Bottom** together; **Close** turns 3D off. Half
  top/bottom sends the top image to the left eye and the bottom image to the
  right eye, restoring vertically compressed eye images. It was wearer-confirmed
  in development build 2970. Full top/bottom was removed after broken output was
  reported; saved selections migrate to half top/bottom. The revised picker
  still needs a visual glasses retest; the app-launch correction is
  wearer-confirmed on OnePlus 15 / XREAL One with this release APK.
- **Optional quiet Eye photos and immediate screen-off.** Two switches under
  **Settings → Experimental** are off by default and apply immediately.
  **Disable XREAL Eye shutter sound** silences the photo cue while other app
  sounds keep playing. **Skip screen-off confirmation** immediately switches off
  the phone panel when the controller button is pressed. Touches and the pointer
  remain blocked; either volume button restores the screen. The phone remains
  unlocked. Turning a switch off restores the original behavior. Emulator checks
  cover persistence, reads by the separate renderer process, sound selection and
  controller behavior; physical Eye sound and panel-power checks remain pending.
- **More precise compatibility evidence.** A connected Nubia NX809J / One Pro
  retest on 0.2.5 confirms that the hardware composer exposes only 640 × 480
  after the stereo switch. The visible wallpaper belongs to the OEM secondary
  launcher; it does not establish a successful AR launch. New Samsung reports
  distinguish the S21 FE ping denial from the S26 Ultra's USB and control
  transport failures. [Nubia investigation](reports/nubia-nx809j-one-pro-v023.md).

Previous features remain, including VPN/ADB recovery, setup for continued use on
mobile data after starting the helper on Wi-Fi, controller touch routing,
DRM-readiness rendering fallback, retained startup display diagnostics, optional
2D 60 Hz, anchored-window preservation, concurrent high performance apps,
logo-free video recording and XREAL Eye recording without a fixed launcher time
limit. See the [0.2.5 changelog](https://github.com/MarkPronkin/the-unbound-launcher/releases/tag/v0.2.5-alpha)
for the earlier changes, including all fixes from withdrawn 0.2.4.

## Installing or upgrading

Install **`the-unbound-launcher-0.2.6-alpha.apk`**. Build **2972** keeps package
**`dev.markpronkin.unbound`** and the existing signing certificate registered
with the owner's Android developer account. It updates public releases from
0.1.1 through 0.2.5 alpha, including withdrawn 0.2.4, and local development builds
through 2971 in place, preserving app data, pairing and settings.
[Package registration](reports/package-identity.md).

Enable **USB debugging** and **Wireless debugging**, then restart the helper
under **Settings → On-device ADB → Connect and start** on Wi-Fi and reconnect the
glasses. **Settings → About → Check for updates** can find 0.2.6.
Once the helper is Ready, keep USB debugging enabled when leaving Wi-Fi to use
mobile data. If the helper stops or the phone reboots, return to Wi-Fi and start
it again.

A clean install is required from 0.1.0 alpha or a build using
`com.xreal.evapro.nebula`; Android does not transfer data between package names.
Requirements remain an arm64 Android 11+ phone with USB-C DisplayPort Alt Mode,
compatible glasses, Wi-Fi for Wireless debugging setup and about 1 GB for the
APK. If Android asks how to use the glasses display, choose **Mirror**. The
firmware gate checks the model's pinned stable release before AR startup.

## Validation and known limitations

All **269 host regression tests** pass, with no failures or skips. The separate
APK audit passes package/version, class uniqueness, current bundled sources,
scoped DEX/native patch restoration and Unity shader-loading checks. The APK
passes Signature Scheme v3 verification and 16 KB ZIP alignment, with application
debugging disabled. All 258 bundled source files match the workspace. Signing
certificate comparison and full receipts are retained in the local release
package's `validation.json` and companion files.

The exact release APK was installed in place on OnePlus 15 / XREAL One. The
wearer confirmed that opening an app keeps AR Space running, and no new crash
appeared in the collected crash buffer. This focused check does not validate
every new feature or hardware combination.

- The exact release APK's app-launch correction is wearer-confirmed on
  OnePlus 15 / XREAL One. This focused retest does not establish that all three
  immersive formats or the other new features have passed physical testing.
- Guided display timing, pattern appearance and automatic 2D recovery need
  physical-glasses checks. A successful SDK command alone does not establish
  native timing or a clear picture.
- The Samsung S21 FE correction needs a retest of this APK. S26 Ultra USB-read
  stalls and native control transport failures remain unresolved.
- Pixel 10 Pro and Nubia NX809J / One Pro's VGA-only stereo links, Samsung / 1S
  control-channel timeouts and OPPO / Air 2 Pro's experimental 3D remain unresolved.
- VPN transitions, mobile-data helper retention, the Honor touch correction,
  Moto rendering fallback and restored 2D 60 Hz need affected-device retests.
- Renderer network selection, anchored windows, concurrent high performance
  apps, logo-free recordings, Retroid geometry and the R1 firmware reader retain
  their previously documented hardware-validation limits.
- Adaptive brightness, screen-off gamepad behavior, Eye shutter muting and the
  immediate screen-off option still need their respective wearer checks. Volume
  recovery requires readable hardware input nodes and the updated helper.
- Earlier reconnect-related audio distortion and a temporary freeze, remaining
  app-color differences and hardware-dependent input/Bluetooth issues are not
  claimed fixed. Protected/DRM video in spatial windows and Miracast receiving
  remain unsupported.

See the [README](https://github.com/MarkPronkin/the-unbound-launcher#readme) for
setup and compatibility evidence. This is an independent, fan-made alpha based
on XREAL's original software, unaffiliated with XREAL. XREAL-origin code and
artwork remain XREAL's property. The author's original code uses the MIT License;
third-party components retain their licenses. Notices and pairing-library
sources are bundled in the APK.
