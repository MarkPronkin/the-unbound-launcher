# Samsung SM-F956U1 Bluetooth and stability report

**October 28 follow-up:** [Mouse reconnection during AR Space](samsung-mouse-reconnect.md)
adds two captures with repeated pointer-service `WRITE_SECURE_SETTINGS`
crashes and Mouse/Touchpad switching. The analysis below describes the
earlier October 27 capture, which did not contain the failure.

The October 27 report describes mouse/keyboard problems and occasional app
stops on **Samsung SM-F956U1, Android 16 / API 36, Unbound 0.1.2 alpha build
2924**, with **Air 2 Pro** glasses. The supplied export does not capture either
failure. Source inspection found a separate exception-handling defect in
optional Bluetooth enumeration, now corrected; its involvement in this user's
symptoms remains unconfirmed.

## Captured evidence

`unbound-debug-20261027-193653-535aff08.zip` contains five files. Collection
starts at **15:36:51 EDT**, and the export is created at **15:36:53 EDT**.
`app.log` has only seven timestamped events, all ScrollView or Settings/Help
touch events. There is no crash stack, ANR, renderer lifecycle history or
Bluetooth/input-device event. The report has no input-device inventory,
Nearby devices permission state or historical process-exit reasons.

The display dump shows external display 23 on at **1920 × 1080 / 90 Hz** and
only three logical displays: the two built-in panels and the glasses.
SurfaceFlinger shows the phone's Settings/Help content mirrored to the glasses
(`surfaceflinger.txt:1570`), with no active AR Presentation or spatial app
virtual display in this snapshot. This establishes the export-time state;
it cannot show whether AR previously stopped, crashed or was closed normally.
Both dumps completed successfully without a timeout.

The network report's `eth0` ping fails because that interface is absent.
That result alone does not diagnose Bluetooth or establish why the app
occasionally stops. The device still exposes the glasses display at native
1080p in this snapshot.

## Bluetooth enumeration correction

The bundled `com.xreal.evapro.BluetoothHelper.getConnectedDevices()` first
collects bonded devices whose connection check succeeds, then supplements that
list through `BluetoothCompat.getConnectedDevices(manager, 7)`.
Profile 7 is the HID host profile. Android documents only GATT and GATT_SERVER
for this `BluetoothManager` method, and AOSP throws `IllegalArgumentException`
for unsupported profiles. See the
[Android API contract](https://developer.android.com/reference/android/bluetooth/BluetoothManager#getConnectedDevices(int))
and [AOSP implementation](https://android.googlesource.com/platform/frameworks/base/+/73f511e471d11ce650a574e9e67ee30b56d1ba4e/core/java/android/bluetooth/BluetoothManager.java).

The adapter previously caught only `SecurityException`, so an unsupported
profile aborted enumeration. It now also catches `IllegalArgumentException`
from this supplementary query and returns an empty supplementary list. The
original helper retains its preceding bonded-device results. Phones that
accept the query still receive their original profile and return their results.
Unrelated runtime failures continue to propagate.

This guards enumeration when that method runs. Mouse movement, clicks,
scrolling, keyboard focus and key delivery need a reproduction on the reported
hardware; the guard does not establish that those symptoms are resolved.

Validation:

- Inspection of the public v0.1.2 APK confirms profile 7 is passed to the
  adapter in `classes2.dex`, and its `classes4.dex` adapter catches only
  `SecurityException`. The DEX audit is retained with the local evidence.
- The host regression fails against the prior adapter with
  `IllegalArgumentException: Profile not supported: 7` and passes after the fix.
  Its 16 checks cover unsupported profiles, retained bonded devices, vendor HID
  results, GATT/GATT_SERVER forwarding, denied permission, absent services and
  propagation of unrelated failures. Framework responses are controlled host
  stubs; this is not a Samsung hardware test.
- The production adapter compiles against Android API 34, matching the APK
  builder's compile platform.
- `python3 -m unittest discover -s tools -p 'test_bluetooth_compat.py' -v`
  reproduces the host check. No new APK was built, installed or published.

## Existing fixes after the reported build

Build 2924 predates two relevant corrections already in the working tree:

- [Build 2928 background rendering](direct-glasses-display.md#background-trackpad-lifecycle--build-2928)
  keeps a live renderer running when the phone controller goes into the
  background. The prior behavior returned the glasses to phone mirroring.
- [Build 2929 controller mode switching](controller-mode-switch.md) fixes a
  `WRITE_SECURE_SETTINGS` crash in the service handling the Beam pointer flag.

Those problems were reproduced and validated separately on Pixel hardware.
They are candidates for comparison if this user sees a stop while leaving the
controller or changing controller modes. The supplied export does not identify
either as this user's failure.

## Next capture

Enable **Debug mode → Collect debug data before starting AR**, then reproduce
the input problem and occasional stop. Export immediately afterward, reopening
Unbound first if it closed, and keep the glasses connected while the export
finishes. Include:

1. Mouse and keyboard models, and whether they work in ordinary phone apps.
2. Whether Android loses the Bluetooth connection, or the devices remain
   connected but fail to move, click, scroll or type in AR.
3. Whether **Nearby devices** is allowed under Unbound's **App permissions**.
4. The approximate failure time and trigger: connecting a device, changing
   controller mode, leaving the controller, folding/unfolding, or normal use.
   Specify whether the phone shows a crash dialog, the glasses return to
   mirroring, or one spatial app closes.

Raw export files and the archive hash are retained locally under
`artifacts/samsung-fold6-bluetooth-2924/`; private network addresses and display
identifiers are omitted here.
