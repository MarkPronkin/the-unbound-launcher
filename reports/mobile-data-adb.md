# Mobile use after on-device ADB startup — development build 2963

Unbound uses ADB to start its bundled shell helper on the same phone. Once that
helper publishes a live Binder, app-window operations use Binder and do not need
Wi-Fi, an internet connection, or an ongoing ADB socket. Internet traffic can use
mobile data while supported glasses use their separate USB Ethernet control link.

## Implementation

The setup screen now asks for both **USB debugging** and **Wireless debugging**.
After authenticating to `127.0.0.1` and verifying UID 2000, startup reads
`settings get global adb_enabled` under shell identity. Only an exact `1` allows
helper extraction and launch. A missing or disabled setting produces an
instruction to enable USB debugging. Unbound does not change Developer options.
The existing payload hash verification, private ADB identity, detached launch,
signature checks and live-Binder gate remain in place.

This check addresses Android's daemon lifecycle: when Wireless debugging stops,
`AdbService.stopAdbd()` retains the daemon if USB debugging remains enabled.
Detaching a subprocess with `nohup` and `setsid` does not by itself protect it
against its init service's process-group termination. See
[Android's AdbService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/adb/AdbService.java).

**Use without Wi-Fi** reports the current Binder and USB-debugging state. A live
Binder remains Ready when connectivity or debugging settings change; a saved
pairing record never substitutes for that Binder. If USB debugging is turned off
after startup, the screen tells the wearer to turn it back on before leaving
Wi-Fi. An unavailable app-side settings readback is reported as unknown; the
authoritative startup check still runs under shell identity.

Connection attempts try manual and remembered local ports before waiting for mDNS.
Remembered ports from a previous known boot are skipped. If no known listener
works and Wi-Fi is disconnected, startup reports how to recover immediately.
When Wi-Fi is present, stale ports fall back to fresh discovery. Network-state
read failures still permit discovery. Wi-Fi may have no internet and need not be
the phone's default network. The setup screen closes discovery when the helper
is Ready or Wi-Fi is absent and resumes it when needed.

Read-only helper network diagnostics now include USB/Wireless-debugging flags,
`adbd` service state and its TLS listener port. These probes have fixed arguments,
bounded execution and no pairing codes or keys.

## Limits

- A phone reboot, app update, force-stop, provider death or vendor process policy
  can stop the helper. A normal recovery requires Wi-Fi and Wireless debugging
  again. Pairing is repeated only if Android has forgotten the app's identity.
- The glasses' Ethernet interface does not satisfy Android's Wi-Fi check for
  enabling Wireless debugging. There is no stock, unrooted cold-start guarantee
  with only mobile data after a reboot.
- No legacy TCP ADB listener is enabled. All embedded ADB connections retain
  the existing same-phone loopback destination.
- Physical phone/glasses testing on mobile data, lock/background behavior and
  vendor-specific daemon retention remain required. An Android emulator has no
  physical glasses or real cellular subscription.

## Validation

All **21 focused host tests pass**, including **35 mobile-bootstrap assertions**,
14 bounded network-diagnostic assertions and the existing setup, manifest,
automatic-entry and glasses-network checks. The Android 13 emulator passes
**33 setup checks** with the mobile-use section present. Its isolated test APK
now includes the theme resource required by the existing production theme lookup;
the production resource selection is unchanged.

The host regression executes the production bootstrap and network-state adapter
against deterministic Android and ADB boundaries. It covers live-Binder reuse,
USB-debugging prerequisites before helper launch, denied app-side readback,
cellular/VPN/Ethernet-only connectivity, local-port recovery without mDNS, stale
ports, fresh discovery, reboot detection, invalid/duplicate ports and discovery
ownership. The related setup, automatic-entry, manifest and glasses-network
regressions are also exercised.

`verify_mobile_adb_runtime.py` is restricted to emulator serials. It starts the
real bundled helper under shell UID using the emulator's external ADB transport,
disables Wi-Fi, and checks that `adbd` and the exact same helper Binder remain
alive with Wireless debugging off. It also checks offline reconnect, stable
pairing state, installation identity, SPAKE2 encryption and native TLS support.
It restores emulator settings. The installed Android 13 image did not provide a
usable Wireless-debugging TLS listener; real on-device pairing and TLS-started
helper survival therefore remain unverified by this emulator check. The test
report explicitly distinguishes external ADB startup from on-device TLS startup.
The Wi-Fi-off lifecycle check passes: `adbd` remains running, the exact same
helper Binder responds, offline reconnect reuses it and pairing state is stable.

The signed build 2963 passes the full APK audit, including packaged runtime-source
matching, manifest/signature checks, scoped DEX/native restoration and exclusion
of device-test instrumentation. The APK and machine-readable audit report are
retained under `artifacts/unbound-launcher-mobile-adb-2963/`.

Build and verification commands:

```sh
python3 -m unittest discover -s tools -p 'test_mobile_adb.py' -v
python3 tools/build_embedded_adb.py --test-only --output artifacts/adb-mobile-design/isolated
python3 tools/verify_mobile_adb_runtime.py --build artifacts/adb-mobile-design/isolated
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-mobile-adb-2963
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-mobile-adb-2963/the-unbound-launcher.apk --report artifacts/unbound-launcher-mobile-adb-2963/verification.json
```

The development APK is not published or installed on a physical device by this
change. The then-public 0.2.4 alpha was build 2962 and was later withdrawn.
These mobile-use changes are included in 0.2.5 alpha (build 2967).
