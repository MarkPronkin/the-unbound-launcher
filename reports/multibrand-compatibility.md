# Broader Android phone compatibility — 2026-10-26

Candidate: **0.1.14 alpha / 2888**, one APK for all brands:
[the-unbound-launcher.apk](../artifacts/unbound-launcher-multibrand/the-unbound-launcher.apk).

Samsung, Honor, Oppo, Realme and other brands use the same capability-based
runtime. Brand detection customizes setup instructions only. There is no phone
allowlist, separate brand APK, guessed vendor Binder transaction, or automatic
change to a manufacturer's security, battery, DeX or desktop settings.

## Changes

- **Android 11 helper publication:** the helper previously directly loaded
  Android 12's `AttributionSource` when constructing its shell context and
  publishing its Binder. `ProviderCallCompat` now discovers attribution support
  and selects the actual `IContentProvider.call` signature. Android 11 receives
  the shell package and attribution tag; newer contracts receive the shell
  attribution object. A rejected publication is never retried with a different
  identity or through an older contract. The provider's UID/signature checks
  and the helper's controlled-display/input restrictions are unchanged.
- **Independent display capabilities:** Desktop preferences require both getter
  and setter, temporary modes require a readable preference plus the
  non-persistent setter, and global mode overrides require both read and write
  APIs. A partial OEM backport no longer prevents the older connected-display
  enable path. Frameworks with no enable API keep their normal display flow.
  A failed optional callback registration leaves direct polling available.
- **More fallback resolutions:** the renderer's window request can recover an
  identified XREAL stereo display from non-VGA sizes, including 720p, 1080p and
  1440p. It requires an advertised 3840 × 1080 / approximately 60 Hz mode and an
  unambiguous stereo-only EDID. Working stereo output, mixed mono/stereo mode
  lists, other monitors, the phone screen and explicit SDK preferences remain
  unchanged. Requests are restored when the renderer window stops.
- **Phone setup tips:** Samsung, Honor/Huawei and Oppo/Realme/OnePlus get relevant
  Developer options guidance. Xiaomi/Redmi/Poco, Vivo/iQOO, Motorola, Sony and
  unknown brands retain generic Settings search instructions and the same
  runtime. Tips explain manual notification pairing (`code:pairing-port`), the
  separate connection port, background permissions and missing Wireless
  debugging. Missing or restricted Developer options intents fall back to
  Settings, then a manual instruction instead of crashing.

## Requirements and manufacturer guidance

All models still need ARM64, Android 11+, Wireless debugging with pairing codes,
USB-C DisplayPort video output, suitable XREAL glasses and compatible firmware.
Charging through USB-C does not establish video support. Phone model, region,
OS build and glasses firmware matter; a brand name cannot establish compatibility.

| Family | Setup route / limitation | Validation |
| --- | --- | --- |
| Samsung | About phone → Software information → Build number. Review Settings' own explanation if installation or debugging is blocked; Auto Blocker can block outside-store APK installation and USB commands. No automatic disabling. | Hardware pending |
| Honor / Huawei Android devices | Search Settings for Build number, then Developer options and Wireless debugging. If the firmware lacks pairing-code Wireless debugging, on-phone setup is unavailable. This does not claim HarmonyOS-only support. | Hardware pending |
| Oppo / Realme / OnePlus | Look under About device / About phone → Version for Version number or Build number; Developer options can be under System & Update, System settings or Additional settings. | Earlier OnePlus 15 build wearer-confirmed; Oppo/Realme and this revision pending |
| Xiaomi / Redmi / Poco, Vivo / iQOO, Motorola, Sony, others | Search Settings for the relevant options; use manual ports if discovery fails. Check this app's background permissions if setup is interrupted. | Hardware pending |
| Pixel | Existing direct display snapshots, Desktop preference, temporary stereo policy and GL capability selection are retained. | Earlier builds wearer-confirmed; this revision pending |

Manufacturer menu names vary by firmware. The app does not infer DisplayPort
support from manufacturer, USB host support or a desktop-mode setting. It also
does not spoof a Pixel/OnePlus identity or guess settings needed to bypass an OEM
restriction. If a phone refuses an operation, manual Android consent/settings or
further device-specific investigation can still be necessary.

## Validation

Host regression tests cover 22 synthetic combinations of complete, partial and
absent display APIs, including both display-ID enumeration signatures, and four
provider contracts (Android 11, modern, older contract with attribution present,
and rejected modern publication). These are API simulations, not Samsung,
Honor or Oppo hardware runs. The existing OnePlus contract, Pixel platform and
watchdog tests also pass, as do the 52 renderer startup assertions, Presentation
lifecycle/DEX checks and embedded-ADB manifest regressions.

The signed APK's complete payload audit verifies production wiring of the
provider adapter, absence of direct Android 12 attribution type references in
that adapter and LaunchServer, packaged setup help, display watchdog wiring,
DEX patch reversibility, original permissions, helper checksum, unchanged
resources/native libraries outside the existing audited color patch, and no
duplicate classes. See [APK audit](../artifacts/unbound-launcher-multibrand/verification.json).

An isolated Android 13 emulator passes **80 assertions**: 32 setup checks, 11
Ready/continuation checks using a real shell UID 2000 Binder, and 37 layout and
capture checks across portrait/landscape at 100% and 200% text. Setup and help
screens were visually inspected. The test package uses the same provider
adapter with its own authority; its display watchdog is disabled.

Runtime UI results are recorded in
[the validation record](../artifacts/unbound-launcher-multibrand/runtime-verification.json).
No physical phone was connected or modified during this work. End-to-end AR,
GPU output, manufacturer desktop interactions, background process survival,
reboot recovery, USB permissions and glasses hotplug still need real hardware.

## Hardware acceptance checklist

For each exact phone/OS/glasses combination, record:

1. Install the same APK; complete disclaimer, overlay permission and pairing.
   Exercise notification entry and manual ports if mDNS is unavailable.
2. Confirm Ready with the live shell Binder, then enter AR Space with glasses
   attached. Confirm native stereo geometry and correct colors in both eyes.
3. Launch multiple apps, click/scroll/type, inspect headset icons and Recents.
   Protected-content playback is outside the port's supported capture path.
4. Unplug/replug, transition mono → stereo, lock/unlock, and return from the
   manufacturer's desktop UI. Unrelated displays must retain normal behavior.
5. Reboot, enable Wireless debugging and reconnect. Check recovery after an app
   update and background interruption. Record any remaining manual prompts.
6. Confirm temporary display preferences restore after stopping, disconnecting
   and failed startup. Keep the exact OS/build number and helper capability log.

Do not label an entire phone brand supported after one model passes.

## Primary references

- [AOSP Android 11 provider contract](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/core/java/android/content/IContentProvider.java)
  and [Android 12 contract](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-12.0.0_r1/core/java/android/content/IContentProvider.java)
  establish the change in provider-call parameters.
- [Android Developer options](https://developer.android.com/studio/debug/dev-options)
  documents manufacturer menu differences, including Samsung's Software information path.
- [Android ADB documentation](https://developer.android.com/tools/adb)
  documents Android 11+ pairing and manual connection when discovery fails.
- [Oppo Developer options](https://support.oppo.com/en/answer/?aid=neu7554)
  documents Version number and the ColorOS menu variations.
- [Honor support](https://www.honor.com/global/support/content/en-us00410323/)
  documents finding Build number through Settings search.
- [Samsung Auto Blocker](https://www.samsung.com/us/support/answer/ANS10003636/)
  documents outside-store installation and USB-command restrictions. This is
  not evidence that every One UI version blocks wireless pairing.
