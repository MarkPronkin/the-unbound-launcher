# Three-finger trackpad gestures on OxygenOS / ColorOS — build 2932

OnePlus 15 (CPH2747, Android 16) has an OEM input monitor which steals
three-finger streams from applications. This affects the trackpad before its
existing Unity gesture recognizer receives the complete touch stream.

Inspection of the phone's `OplusGestureObserver` and `OplusMultiGesturePolicy`
shows that screenshots, partial screenshots, split screen, Mind Space and
touchpad screenshots all keep the three-finger monitor registered. Disabling
only three-finger screenshots is insufficient. Partial screenshots and touchpad
screenshots default to enabled even when their settings rows are absent.

`TrackpadGestureCompat` watches the two native trackpad fragments on the phone
controller. It leases suppression through the existing authenticated shell
helper while either trackpad is visible and the phone is unlocked and interactive.
It releases when the controller stops, the phone locks or sleeps, or the user
switches to Spatial Mouse. The original touch forwarding and Unity recognizer
are unchanged. A visible phone controller may be paused when an app in the
glasses has focus, so pause alone does not end the lease.

The helper temporarily writes `0` to these five settings:

| Table | Setting |
| --- | --- |
| system | `oplus_customize_smart_apperceive_screen_capture` |
| system | `oplus_customize_screenshot_enable_area_screenshot` |
| secure | `double_finger_split_screen_enable` |
| secure | `three_finger_flash_memory_enable` |
| global | `touchpad_gesture_threefinger_fling_to_screenshot` |

The adapter is enabled by the presence of `android.os.OplusGestureMonitorManager`
in both the application and helper. It never checks `Build.MANUFACTURER`,
`Build.BRAND`, a OnePlus model name, or an OxygenOS version. An Oppo/ColorOS or
Realme build exposing this shared framework therefore takes the same suppression
and restoration path automatically, including in the already-installed build
2932. Physical testing here is OnePlus only; this does not establish coverage of
every ColorOS version or of older firmware with different framework classes or
setting names.

Oppo's own [Split View documentation](https://support.oppo.com/en/answer/?aid=2201343)
describes the three-finger upward swipe, and its [partial screenshot documentation](https://support.oppo.com/en/answer/?aid=2174375)
describes the three-finger hold. These document the relevant ColorOS conflicts;
they do not verify private framework classes or setting keys on an Oppo device.

It does not grant the application write access to system settings. The new
Binder endpoint (`0x00FFFFF1`) accepts only the installed launcher UID and a
session owner token; callers cannot choose arbitrary settings or values.

Before each write, the previous value is saved and synced to a recovery journal.
Release restores exact values, including removing rows that were originally
absent. Already-disabled settings are preserved. A subsequent external value
change is retained. A partial acquisition failure rolls back successful writes;
failed restorations remain journaled for retry.

A separate shell watchdog restores settings when its helper pipe closes or its
heartbeat expires after eight seconds. The owner Binder also releases on app
death. File locking prevents overlapping watchdogs from replacing a snapshot.
After a reboot or simultaneous process loss, the next helper startup recovers
the journal; settings can remain temporarily disabled until that startup.

Validation and artifacts are under `artifacts/three-finger-gestures/`:

- Host policy checks pass for all five gates, absent/default values, repeat
  acquisition/release, interrupted-session recovery, later external changes,
  partial write failure, and failed-restoration retry.
- Capability regression checks execute the unmodified production app and helper
  checks with isolated Android runtime fixtures. With the Oplus framework
  present, `OPPO`, `OnePlus`, `realme` and other branding all enable protection;
  without it, even `OPPO` branding does not enable settings changes. App
  lifecycle registration also stays idempotent. All six capability scenarios
  and the policy/recovery test pass. These are framework simulations, not Oppo
  hardware results; logs are in `oppo-capability-tests.log`.
- The existing five trackpad-input/tap regressions and eight phone-screen/Binder
  regressions pass. The shipped DEX audit checks installation of the new lifecycle
  hook, matching Binder routes, and distinct helper transaction IDs.
- Physical OnePlus checks pass for suppression of all five gates and exact
  restoration after normal release, helper pipe EOF, and a missing heartbeat.
  Results: `oneplus-watchdog.json` and `device/verification.json`. The phone's
  system log confirms `gesture = 0x0` and successful unregistration of the OEM
  monitor during protection.
- The physical input fixture tests both swipe directions, three-finger hold,
  portrait/landscape fragment selection and leaving the controller. It uses the
  production compatibility client and a recording Binder; the separate watchdog
  settings test uses the helper extracted from the production build. On the
  OnePlus, the unprotected baseline had one cancellation and zero three-finger
  moves. With protection, all 29 three-finger moves arrived, with no cancellation.
  All 53 input/lifecycle assertions pass, including both trackpad orientations
  and restoration requests when switching out of the trackpad or closing it.
- The full signed-APK audit passes, including the original DEX restoration
  chain, class uniqueness, package identity, helper Binder routes and bundled
  corresponding sources. The running on-phone helper's SHA-256 matches the
  helper bundled in build 2932.

The APK is `artifacts/unbound-launcher-2932/the-unbound-launcher.apk`. It is
installed on the USB-connected OnePlus as version code 2932, updating version
2908 without clearing app data. The fixture was removed and all five phone
preferences restored. The phone was attached to the computer rather than the
glasses for these automated tests. The user subsequently confirmed that the
gestures work in their OnePlus session. Oppo/Realme hardware has not been tested.

Reproduce:

```sh
python3 -m unittest discover -s tools -p 'test_trackpad_gestures.py' -v
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-2932
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-2932/the-unbound-launcher.apk
python3 tools/verify_trackpad_gestures.py --serial DEVICE_SERIAL --server-port 5039 \
  --apk artifacts/unbound-launcher-2932/the-unbound-launcher.apk
```
