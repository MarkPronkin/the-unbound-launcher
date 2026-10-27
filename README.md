# The Unbound Launcher

![The Unbound Launcher logo](branding/logo-dark.svg#gh-dark-mode-only)
![The Unbound Launcher logo](branding/logo.png#gh-light-mode-only)

**Bringing the XREAL Beam Pro's NebulaOS spatial desktop to Android phones with
USB-C DisplayPort Alt Mode.**

Plug XREAL glasses into your own phone, install one APK, and get the Beam Pro
experience: regular Android apps floating in front of you as spatial windows,
the phone as your trackpad, and windows that stay anchored in place or follow
you smoothly.

> **Alpha software.** The Unbound Launcher is a fan-made project, independent of
> XREAL. It builds on XREAL's original Beam Pro software. All rights to that
> software remain with XREAL.

[Download 0.1.2 alpha](https://github.com/MarkPronkin/the-unbound-launcher/releases/tag/v0.1.2-alpha)
· [Website](https://markpronkin.github.io/the-unbound-launcher/)
· [Discord community](https://discord.gg/J7t64xupfQ)

## Why this exists

On the Beam Pro, NebulaOS's spatial desktop (AR Space) comes from the **My
Glasses** app (`com.xreal.evapro.nebula` 2.2.2). That app runs with Android's
*system* identity. It creates trusted virtual displays for each app window,
injects pointer input into them, and writes Beam-specific system settings.

Many phones can already drive XREAL glasses over USB-C, but none of them grant
those privileges to an installed app. The video path exists; the access to build
a spatial desktop on top of it does not. The Unbound Launcher supplies that
access, without root, so the original spatial desktop can run on an ordinary
phone.

## The goal

The finished product:

1. **One APK.** Install it on any Android phone that outputs video over USB-C
   (DisplayPort Alt Mode).
2. **Setup on the phone only.** No computer, terminal, root or companion app.
3. **The full spatial desktop.** Plug in the glasses and enter AR Space:
   several floating Android app windows, phone-pointer control, and anchored or
   smooth-follow viewing at the glasses' native stereo resolution.
4. **Keeps working.** Recovers after disconnects, app restarts and phone reboots
   without a computer.
5. **Clear about unsupported phones.** A phone that can't run it gets an
   explanation on the phone, not a black screen in the glasses.

DisplayPort Alt Mode gets the image to the glasses, but it doesn't guarantee the
rest will work. Android versions, manufacturer display policies and GPU drivers
all differ. A phone/glasses combination counts as supported only once it has
actually been tested.

## Status

As of 2026-10-27:

| Phone | Android | Glasses | Result |
| --- | --- | --- | --- |
| XREAL Beam Pro (reference) | 14 | XREAL One | Native NebulaOS. Measured at 3840 × 1080 stereo, 60 Hz. |
| OnePlus 15 | 16 | XREAL One | **Works.** User-confirmed on the ADB-assisted development build: AR Space at the same 3840 × 1080 / 60 Hz as the Beam Pro, several apps open together, pointer clicks and scrolling, anchored and smooth-follow modes, and reconnecting. Automatic glasses output without the two Cast prompts is also wearer-confirmed on the 0.1.13 update. |
| Pixel 8 Pro | 17 | XREAL One | **AR Space, app colors and automatic USB entry work.** Build 2899 hides the desktop bars on repeated connections; live window checks confirm the renderer controls both hidden bars. Accepted by the tester for now. Audio distortion, a high-pitched left-speaker sound and a temporary freeze after a third reconnect are a [known bug under investigation](reports/connection-startup-recovery.md#known-bug-audio-distortion-and-temporary-freeze-after-reconnect). Pointer/keyboard coverage and newer Recents capture still need further validation. |
| Samsung, Honor, Oppo / Realme | Android 11+ with Wireless debugging | Compatible XREAL model required | The same APK includes capability-based display handling and tailored setup guidance. Physical hardware testing pending; wired video output varies by model. |
| Xiaomi / Redmi / Poco, Vivo / iQOO, Motorola, Sony, other brands | Android 11+ with Wireless debugging | Compatible XREAL model required | Use the same generic compatibility path; no brand allowlist. Physical hardware testing pending. [Help test it](#help-test-it). |

Only XREAL One has been tested. The launcher recognizes other XREAL models
(One Pro, Air, Air 2, Air 2 Pro, Air 2 Ultra and others) and keeps XREAL's
original per-model display defaults, but none of them have been tried with the
port. An optional [experimental 3D switch](#experimental-3d-on-unsupported-glasses)
can try SBS output on models that normally start in 2D.

## How it works

```
XREAL glasses
     ▲  USB-C, DisplayPort Alt Mode
     │
Android phone
├── The Unbound Launcher  (one APK, ordinary app permissions)
│   ├── XREAL AR Space renderer      original scene and display provider
│   ├── Phone interface              setup, settings, app-menu editor
│   └── Wireless-debugging client ──► the phone's own adbd (127.0.0.1)
│                                          │ starts
└── Shell helper  (UID 2000, the ADB shell user; no root)
    ├── one virtual display per app window
    ├── launches apps onto those displays
    ├── pointer input, restricted to its own windows
    └── glasses display-mode setup and safety watchdog
```

The port retains XREAL's Unity scene and display provider. Audited native hooks
handle app-menu visibility, trackpad taps and app-window color selection; the
surrounding Android code supplies:

- **Repackaged app.** The system shared UID is removed, Beam-only system flags
  are kept in app-local storage instead, firmware-update and boot-mode entry
  points are disabled, and XREAL's analytics uploaders are removed.
- **Privileges from a shell helper.** The work My Glasses did with system
  privileges moves to a helper that runs as Android's shell user: creating
  trusted virtual displays, launching apps onto them and injecting input. Every
  phone gives that identity to ADB, so no root or bootloader unlock is needed.
  The one thing the shell user can't do is create secure displays for protected
  video.
- **ADB without a computer.** The APK pairs with the phone's own Wireless
  debugging over `127.0.0.1`, using the same SPAKE2 and TLS pairing as
  `adb pair`. It then starts the helper and receives its Binder through a
  signature-checked provider. You turn on Developer options once; no other
  device is involved.
- **Direct glasses display.** AR runs in the SDK's native glasses Presentation
  from the phone, without a Desktop/DeX task on the glasses. The helper routes
  XREAL displays to **Mirror**, remembers the previous connection choice, and
  requests the native stereo mode when a model needs it. Any temporary
  display-policy change is undone by a watchdog on disconnect, screen lock or
  failure.

See the [embedded ADB notes](embedded-adb/README.md) and the
[porting assessment](reports/porting-assessment.md) for details.

### Setup without Developer options

The ideal version of the goal wouldn't need Developer options at all. A
permissions-only prototype used Accessibility gestures for input and Android
screen sharing for capture. It controlled two real apps on the OnePlus 15, but
only in phone split screen: Android refuses to place other apps on displays that
an ordinary app creates. That prototype is shelved for now. See the
[prototype notes](standalone/README.md) and the
[feasibility audit](reports/standalone-feasibility.md).

## Requirements

- **An Android phone with USB-C DisplayPort Alt Mode** (wired video output). To
  check, plug in the glasses or a USB-C monitor: if the phone's screen appears,
  the phone has it. Phones without it can't work.
- 64-bit ARM (arm64-v8a) and Android 11 or newer. Wireless debugging first
  appeared in Android 11.
- XREAL glasses with their latest firmware, installed through
  [XREAL's updater](https://www.xreal.com/ota/).
- Wi-Fi during setup. Android only allows Wireless debugging on a Wi-Fi network,
  even though the connection never leaves the phone.
- About 1 GB of storage. The APK contains XREAL's full Unity scene.

## Install and set up

The current GitHub release is **0.1.2 alpha** (Android build **2924**). Download
`the-unbound-launcher-0.1.2-alpha.apk` from this repository's GitHub Releases.
It adds direct glasses display without a Desktop or DeX task, a phone connection
screen, startup fixes for more phones, richer debug exports, and a further
app-window color path, alongside everything in 0.1.1 alpha.

**Update in place from 0.1.1 alpha.** This release keeps the package
`dev.markpronkin.unbound` and the same signing certificate, with a higher
Android version code, so it installs over the public 0.1.1 alpha and later
development builds. App data, pairing and settings stay in place. After this
release is on GitHub, **Settings → About → Check for updates** can download it.

**A clean install is still required from 0.1.0 alpha** or any build that used
`com.xreal.evapro.nebula`, including older ports shown as My Glasses. Uninstall
those first. Android does not replace the old package or transfer its data.
Grant permissions, pair Wireless debugging and complete setup again, then
reconfigure preferences and the app menu. Removing those older builds avoids
having two launchers and helpers installed together.
See the [release notes](RELEASE_NOTES.md) for all changes, validation and known
limitations.

1. Install the APK and open **The Unbound Launcher**. Read the disclaimer;
   **I accept** becomes available after ten seconds.
2. Grant **Display over other apps**, which is required. Other permissions are
   optional and can be reviewed later under **App permissions**.
3. Connect to Wi-Fi and turn on **Settings → Developer options → Wireless
   debugging**.
4. Tap **Start pairing** and allow notifications. In Android Settings, open
   **Wireless debugging → Pair device with pairing code**.
5. Leave that dialog open. Pull down the notification shade and enter the
   six-digit code using **Enter pairing code** on the Unbound notification.
6. Wait for **Ready**, then tap **Continue**. Review the **Terms of service**
   and **Privacy policy** on the next setup screen, then tap **Agree and finish**.
7. Plug in the glasses. If Android asks how to use the display, choose
   **Mirror**; the helper normally selects it automatically. Then enter AR Space.

After a reboot, turn Wireless debugging back on and tap **Connect and start**
under **Settings → On-device ADB**. Android may ask you to pair again. To revoke
access, remove the Unbound entry under **Wireless debugging → Paired devices**.

**Settings → Help** opens a menu with **Get help** at the top, leading to the
[Discord community](https://discord.gg/J7t64xupfQ). Below it, enable **Debug mode**
and turn on **Collect debug data**, then reproduce the problem. Turn collection
off to stop recording, and tap **Create debug file** to prepare a ZIP. Use
**Share** to send it through another app, or **Save to Downloads** to keep a copy.
Both debug switches are off by default. Turning debug mode off also stops
collection; recorded data remains available to export. Collection resumes on
app startup if you left it enabled. The app keeps at most 12 MB of recent app
logs; every exported ZIP is capped at **15 MB (15,000,000 bytes)**, including ZIP
overhead. With Debug mode enabled, creating a file also captures fresh
`display.txt` and `surfaceflinger.txt` dumps through the on-device ADB helper.
Keep the glasses connected until the file is ready. Each dump retains up to
1 MB of command output and records timeouts, permission errors and truncation.
An unavailable or older helper adds an explanation to each file; restart the
updated helper under **On-device ADB** and create another export.
The report adds app, Android, phone-model and display information. Logs and dumps
may contain network addresses, display identifiers and app/window names, so review them before sharing. Nothing
is uploaded automatically. The latest three private exports are retained;
copies saved to Downloads remain until you delete them.

### App updates

Releases **0.1.1 alpha** and **0.1.2 alpha**, and development builds from version code **2906**, use Unbound's own Android package
name, **`dev.markpronkin.unbound`**, for developer registration. The original
XREAL Java/JNI class names remain intact. This is a separate installation from
the earlier `com.xreal.evapro.nebula` builds: grant permissions, pair Wireless
debugging and complete setup again. Existing app data stays with the earlier
installation; Android does not transfer it between package names. See the
[package migration and validation](reports/package-identity.md).

Open **Settings → About → App updates** to check GitHub releases, download a
newer version and install it. **App updates** and **Check on startup** are enabled
by default. Turn off startup checks to check manually, or turn off App updates
to disable both. A startup check runs once per app process after setup; it never
downloads an APK until you choose **Download and install**.

Downloads show progress and can be cancelled. The app checks GitHub's SHA-256
digest when provided, then verifies the APK's package, signing certificate,
Android requirement and newer version code. Android asks you to confirm the
installation and, if needed, to allow updates from this source. A downloaded
update remains available under About if you leave the app or cancel installation.
See the [updater implementation and validation](reports/app-updater.md).

### Experimental app detection

**Settings → Experimental → Detect as game** switches Unbound's Android app
category between **Normal app** (the default) and **Game**. It applies only to
Unbound. Reopen the app after changing it so phone gaming services can pick up
the category; gaming dashboards and optimizations vary by manufacturer.

The switch shows the category Android actually reports. Start the paired helper
under **On-device ADB** if prompted. Android can
reject the change for some installation sources, in which case the setting
shows an explanation. See [implementation and checks](reports/experimental-app-detection.md).

### Experimental 3D on unsupported glasses

**Settings → Experimental → Force 3D on unsupported glasses** is off by default.
Enable it and reconnect the glasses to try **3840 × 1080 side-by-side (SBS) at
60 Hz** on models whose original startup mode is 2D. It changes both the hardware
request and the renderer's home mode. Glasses that already default to 3D keep
their existing resolution and refresh rate.

Turn it off and reconnect to restore the original startup behavior. Compatibility
with additional glasses still needs physical testing; this option does not add
support for non-XREAL glasses. See [implementation and checks](reports/experimental-stereo.md).

## What you get

From NebulaOS:

- Floating Android app windows in AR Space
- Phone controller modes: Spatial Mouse, vertical trackpad and horizontal trackpad
- Anchored and smooth-follow viewing
- Launcher with app grid, recent apps, and quick access to Wi-Fi and Bluetooth
  settings
- Screen-mirroring mode, selected with **Start in screen mirroring**

Added by this port:

- **Ten app windows at once**; the original allows two
- **Direct startup into the glasses launcher**, without the first-use tutorial.
  Tutorial remains available from the glasses app menu.
- **GitHub app updates in About**, with optional startup checks, download
  progress, cancellation and Android-confirmed installation.
- **App-menu editor on the phone**, with search, drag to reorder, hide or show
  all, and a preview of the headset layout. It works without the glasses.
- **Recent apps** with window previews
- **Phone screen off** while AR Space keeps running; either volume button turns
  it back on
- **Stays awake** in AR Space without changing system timeouts
- **Tearing fix** using buffered presentation
- **XREAL analytics removed**
- **Black OLED phone interface** with pictures of the connected glasses model
- **Adaptive foldable layouts.** At 600dp of available window width, Settings
  shows glasses and launch controls beside independently scrolling preferences;
  the app-menu editor shows its list beside the live headset preview. Cover
  screens and narrow split-screen windows keep a single pane. Large text raises
  the breakpoint, and menu drafts, search, preview page and Undo survive resizing.
- **Landscape layouts for 16:9 and 16:10 screens.** Launch controls stay visible
  beside scrolling preferences. The app-menu editor puts search in the toolbar
  above the list and live preview, with a compact ten-app grid on short screens. Setup,
  permissions and About place their overview beside the details. Search
  stays accessible when the keyboard opens; large text and narrow windows
  retain a single-pane option.
- Fixes for app-icon placement, trackpad taps, Chrome opening on the phone
  instead of in AR Space, slow-launching apps, and a wireless-mirroring crash

## Known limitations

- **Audio distortion after repeated reconnects — under investigation.** On the
  Pixel 8 Pro / Android 17 with XREAL One, a third connection produced muffled
  audio, a high-pitched left-speaker sound and a temporary AR freeze. Build 2899
  is accepted for now with this known bug; investigation is paused at the user's
  request. See the [investigation record](reports/connection-startup-recovery.md#known-bug-audio-distortion-and-temporary-freeze-after-reconnect).
- **Setup needs Developer options and Wireless debugging.** The helper must be
  restarted from the app after every reboot.
- **No protected video in app windows.** DRM streaming apps require a secure
  display, which the shell user can't create. See the
  [DRM investigation](reports/crunchyroll-spatial-drm.md).
- **No Miracast receiving.** It requires a permission reserved for the phone
  vendor; the app shows a notice instead.
- **Display handling differs by manufacturer.** The Pixel's Android 17 behavior
  uses the 0.1.7 display recovery, now wearer-tested. While the glasses are connected,
  that workaround may temporarily change a display-mode policy that also applies
  to the phone; the watchdog restores it.
- **Large download.** The APK is about 1 GB.

## Roadmap

1. Test the launch APK on Pixel and OnePlus for Recents previews, automatic
   colors, icon sharpness, hidden desktop bars, pointer/keyboard focus and reconnects.
2. Test more compatible phones, including Samsung devices with different GPUs,
   and check reboot recovery.
3. Add more tested phones through community reports.
4. Build an on-phone compatibility check that explains unsupported devices,
   based on Beam Port Probe.
5. Make the build reproducible for other contributors.
6. Revisit setup that doesn't need Developer options.

## Help test it

Every newly tested phone brings the project closer to "any phone". Please
report:

- Phone model and Android version
- Glasses model and firmware version
- Launcher version, from **Settings → About**
- What happened at each step: setup, Ready, the Mirror/Desktop prompt, AR Space,
  app windows and pointer input

Post reports in the [Discord community](https://discord.gg/J7t64xupfQ). The
[compatibility probe](#compatibility-probe) collects useful device details
without changing anything on the phone.

## For developers

### Project website

The [project website](https://markpronkin.github.io/the-unbound-launcher/) follows the app's Signature OLED design
and includes interactive feature demonstrations, real interface captures, setup
instructions and requirements. It intentionally omits release notes and specific
phone names. See [website setup and GitHub Pages deployment](website/README.md).

Build with `python3 tools/build_website.py`. The `.github/workflows/pages.yml`
workflow publishes only the small generated `.site-build/` directory. APKs belong
in GitHub Releases; development artifacts and device reports are excluded.

### Repository layout

| Path | Contents |
| --- | --- |
| `tools/` | Builders, patchers, verifiers and host tests (Python and Java) |
| `bridge/` | Shell helper: launch server, managed displays, input, phone-screen power |
| `port-client/` | Launcher-side helper client and compatibility hooks injected into the APK |
| `embedded-adb/` | On-device wireless-debugging client and pairing UI, with vendored SPAKE2 and Conscrypt |
| `settings-ui/` | Phone interface: settings, permission setup, About, app-menu editor |
| `probe/` | Beam Port Probe, a separate compatibility checker |
| `standalone/` | Shelved permissions-only prototype |
| `reports/` | Investigations and device test results |
| `branding/` | Logo, icons and how they were generated |
| `archive/` | Retired-feature notes and manifests; binary snapshots stay local |
| `artifacts/` | Local build outputs and evidence (not version-controlled) |

### Preparing a public checkout

Keep `.gitignore` in place. Local builds, raw device captures and diagnostic JSON,
development logs, signing keys, credentials, and compressed research snapshots stay on the development machine
and are excluded from Git. The snapshots can contain screenshots and personal
filesystem metadata even when their filenames look harmless. References to
ignored artifacts in the documentation describe local development evidence;
those files are not included in a fresh checkout.

Use repository-relative paths and placeholders such as `DEVICE_SERIAL` in shared
reports. Put new raw captures under `reports/devices/` or `artifacts/`, and review
any screenshots before copying them into public documentation or website assets.
Check `git status --short` and `git diff --cached` before committing; do not
force-add ignored files. Ignore rules do not remove files already tracked by Git.

### Launch package

The local upload package is in `artifacts/releases/v0.1.2-alpha/`. Upload the
versioned APK and its SHA-256 checksum as GitHub Release assets under tag
`v0.1.2-alpha`, marked as a prerelease. Use the [release notes](RELEASE_NOTES.md)
for the description. APKs and signing keys stay outside Git history.

The public name is **0.1.2 alpha**, with Android version code **2924**. It keeps
the 0.1.1 alpha package `dev.markpronkin.unbound` and signing certificate, so it
updates that release and later development builds in place. Earlier
`com.xreal.evapro.nebula` builds remain a separate installation. Both the app
manifest and About screen read their version from `version.json`.

This release draws AR through the SDK's native glasses Presentation from the
phone, with no Desktop or DeX task on the glasses. See
[direct glasses display](reports/direct-glasses-display.md). The helper routes
XREAL displays to Mirror and restores the previous connection choice when the
glasses leave. It also includes the phone connection screen, startup recovery
for USB type 11 glasses and Android 12 renderers, helper-side glasses ping
readiness, display and network debug exports, and a further software color path
for app windows. YouTube washout on that color path is not yet confirmed on
glasses. See the [release notes](RELEASE_NOTES.md).

### Building

The builders run on macOS with Python 3, JDK 17, the Android SDK (platform 34
and build tools) and JADX 1.5.6 at `artifacts/tools/jadx-1.5.6/`. Gradle isn't
used.

The input is the original My Glasses 2.2.2 APK, pulled from your own Beam Pro;
it isn't included in this repository. Each stage checks the SHA-256 of its input
and refuses anything unexpected. Those hashes currently match this workspace's
local signing key, so a fresh checkout can't complete the chain yet (roadmap
item 5).

```sh
# Pull the reference APKs from a Beam Pro with USB debugging enabled
python3 tools/inspect_device.py --serial BEAM_PRO_SERIAL --label beam-pro --pull-apks

# Build, in order
python3 tools/build_probe.py          # Beam Port Probe and the local signing key
python3 tools/build_shell_probe.py    # shell helper and launcher client DEX
python3 tools/build_port_bootstrap.py --allow-network --buffered-presentation \
  --bridge-client-dex artifacts/port-client/classes.dex \
  --source reports/devices/beam-pro-TIMESTAMP/apks/com.xreal.evapro.nebula/0-base.apk \
  --output artifacts/port-default-m
python3 tools/build_settings_ui.py    # phone interface: artifacts/unbound-launcher-help
python3 tools/build_embedded_adb.py   # one APK: artifacts/unbound-launcher-0.1.2-alpha

# Verify and test
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-0.1.2-alpha/the-unbound-launcher.apk
python3 -m unittest discover -s tools -p 'test_app_updater.py' -v
python3 -m unittest discover -s tools -p 'test_*.py' -v
```

Tests of the native patches execute ARM64 code with Unicorn. Install it with
`python3 -m pip install --target artifacts/tools/python unicorn`. Run native tests
in an environment that permits Unicorn's JIT; a restricted sandbox can cause
SIGILL. All 171 host regressions pass for 0.1.2 alpha outside that sandbox.

To verify the adaptive phone interface on an isolated Android emulator:

```sh
python3 tools/build_settings_ui.py --preview-only --output artifacts/foldable-layout
python3 tools/verify_foldable_ui.py --serial emulator-5580 --output artifacts/foldable-layout
```

This runs the phone UI regressions, checks 360–1200dp windows, the 600dp
breakpoint, short landscape windows, 200% text, and live fold/unfold resizing,
then captures the compact and unfolded screens. It restores the emulator's
display and font settings. This is layout validation; physical foldable phones
and their glasses connections still need hardware testing.

To verify landscape phone and tablet layouts, including setup and the keyboard:

```sh
python3 tools/build_settings_ui.py --preview-only --output artifacts/landscape-layout
python3 tools/build_embedded_adb.py --test-only --output artifacts/landscape-layout/adb
python3 tools/verify_landscape_ui.py --serial emulator-5580 --output artifacts/landscape-layout
```

The verifier exercises 640 × 360 through 1280 × 800dp windows at both aspect
ratios, 200% text, search with the Android keyboard, draft restoration, and the
existing portrait regressions. It captures actual Android screens and restores
the emulator's display, rotation and font settings afterward.

### Compatibility probe

```sh
python3 tools/build_probe.py
adb install artifacts/probe/beam-port-probe.apk
```

**Beam Port Probe** reports the Android version, memory page size, CPU ABIs,
external displays, USB devices, and which kinds of virtual display an ordinary
app may create. Tap **Run checks**, then **Copy report**. The probe has no network
permission and changes no settings.

### Further reading

- [Embedded ADB](embedded-adb/README.md): pairing, the helper's lifecycle and
  display management
- [Porting assessment](reports/porting-assessment.md): what My Glasses needs
  from the system and how the port provides it
- [Pixel 8 Pro display investigation](reports/pixel-display-startup.md)
- [Standalone feasibility audit](reports/standalone-feasibility.md)
- [Tearing investigation](reports/rendering-tearing.md)
- [Research notes](reports/research.md)

## Licensing and attribution

The Unbound Launcher is a fan-made project, not affiliated with or endorsed by
XREAL. **All code originating from XREAL belongs to XREAL.** All rights to
XREAL's original code, software, artwork and other assets remain with XREAL,
including XREAL code retained or modified by this project. XREAL, Beam Pro,
NebulaOS and My Glasses are XREAL products.

The project's original code is authored by [@MarkPronkin](https://github.com/MarkPronkin).

**The original code written by this project's author is licensed under the
MIT License.** See [LICENSE](LICENSE). That license applies only to the author's
original contributions; it does not relicense or grant rights to XREAL code or
assets, or to third-party components.

Third-party components retain their own licenses and copyright notices. See
the embedded ADB [notices](embedded-adb/NOTICE.txt), the vendored SPAKE2
[license](embedded-adb/vendor/spake2/LICENSE), and the
[Conscrypt license](embedded-adb/conscrypt-LICENSE.txt).
