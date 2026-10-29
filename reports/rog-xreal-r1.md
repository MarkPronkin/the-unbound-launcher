# ROG XREAL R1 compatibility

Implemented as experimental support, with physical R1 validation still pending.
This changes The Unbound Launcher; it does not update stock Beam Pro software
or the glasses' firmware.

## Evidence

The user's `unbound-debug-20261027-180229-f5ff1ff7.zip` was collected on a
Samsung SM-S918B running Android 16, with public build 2924. Its display and
SurfaceFlinger dumps identify the physical glasses as `R1`, manufacturer PNP
`AUS`, EDID product `43593` (`0xaa49`). The active output was 1920 × 1080 at
60 Hz, with 90/120 Hz also advertised. Neither dump advertises stereo output
in that captured configuration. Both app and shell ping snapshots reached
169.254.2.1 through eth0 successfully. The archive contains no USB descriptor
inventory and no connection attempt covering native glasses initialization.
The sensor-bias file exception alone does not establish why startup failed.

XREAL's [R1 announcement](https://www.xreal.com/blog/rog-xreal-r1-ar-gaming-glasses-announce-en)
confirms the X1 chip and 57° field of view. That supports trying the existing
One Pro transport/profile, but does not establish identical calibration or
complete Beam SDK protocol compatibility. The upstream
[OBS driver registry](https://github.com/8796n/obs-nyan-real-3dof/blob/main/src/core/device_registry.cpp)
lists R1 USB configurations `0b05:1d9c` and `0b05:1d9d`, using its One-family
TCP transport and One Pro optical profile. Those USB IDs come from upstream
driver source, not this user's archive.

## Implementation

- Shared hardware identity code accepts exactly those two ASUS USB pairs and
  the captured `AUS`/`43593` EDID pair. Other ASUS devices remain ineligible.
- Six audited SDK method edits add the R1 at USB model detection, EDID model
  detection, two permission-selection loops, the USB permission polling
  selector, and the glasses input-device exclusion. Every existing model path
  is retained. A full reverse audit restores the exact original DEX and rejects
  unrelated changes or double application.
- Internally the R1 uses SDK model 6, retaining the existing One Pro SKU lookup,
  Ethernet initialization, native error checks, and 3840 × 1080 / 60 Hz stereo
  request. Success still requires the real SDK and display readiness checks.
  Neither a ping nor a 1920 × 1080 display is promoted to successful AR startup.
- Automatic USB entry, temporary shell USB grants, connection recovery and
  Ethernet readiness all accept the exact R1 USB identities. Grants still
  validate the installed launcher UID and the live device's name, actual vendor,
  product and device ID. Vendor aliases apply only inside SDK identity checks;
  the actual USB descriptors passed through Android and Binder stay intact.
- Display management recognizes the exact R1 EDID for mirroring and native
  stereo selection, with the existing disconnect/failure restoration policy.
- The settings UI identifies connected R1 glasses by name instead of showing
  One Pro. Its UI-only type 1003 never reaches native initialization.
- New debug exports include USB vendor/product IDs, permission state and
  interface counts, plus EDID manufacturer/product/name. USB serial numbers
  are not queried.

## Validation

Run the focused regressions:

```sh
PYTHONPATH=tools python3 -m unittest test_rog_r1 test_usb_permission \
  test_auto_ar_entry test_ethernet_readiness test_connection_startup \
  test_managed_display test_managed_display_platform test_multibrand_compat \
  test_legacy_display_startup
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-rog-r1
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-rog-r1/the-unbound-launcher.apk
python3 tools/verify_rog_r1_runtime.py \
  artifacts/unbound-launcher-rog-r1/the-unbound-launcher.apk --serial emulator-5580
```

The focused host regressions passed (46 tests across the identity, permission,
connection, display, embedded-ADB, phone-screen and debug-archive suites).
The Android identity fixture passed 21 assertions after correcting an incoming
argument register that the original SDK reuses later in its method body.
The Android settings fixture also passed 183 artwork assertions and 38 Help/debug
assertions, including export creation.
The final signed APK passed the full embedded-ADB package verifier, signature/
alignment checks, and all 21 shipped-SDK Android assertions. Local build and
validation receipts are in `artifacts/unbound-launcher-rog-r1/`.

The isolated Android fixture executes the built APK's actual USB and EDID
identity classes and Ethernet-series selector under ART, with simulated USB
descriptors. It also exercises the production automatic-entry predicate.
It does not exercise R1 firmware, stereo negotiation, IMU packets or optics.

## Physical acceptance

Install the test APK over public 0.1.2 alpha, restart its updated on-device ADB
helper, and connect the R1 directly to the phone. Verify entry from both a
closed app and Settings, actual stereo output, comfortable eye alignment,
head tracking, pointer control and audio. Verify disconnect during startup,
reconnect after AR opens, and returning to normal mirroring after exit.

If startup fails, enable Debug mode and Collect debug data before reconnecting,
then export with the R1 still connected. The new USB inventory will confirm
whether this unit uses the upstream identifiers; native errors and display
dumps will distinguish protocol rejection from stereo negotiation failure.
Firmware differences or a native SDK model check may require further work.
Do not mark R1 as hardware-supported until the wearer confirms these checks.
