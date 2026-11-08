# VPN-aware on-device ADB recovery — development build 2965

This change improves startup when a VPN is already enabled, is disabled, or is
replaced during pairing or connection. It preserves the live-Binder readiness
check: once the detached helper is running, changing networks does not trigger
ADB reconnection or restart the helper.

## Connection behavior

Both the TLS pairing socket and the normal ADB transport explicitly bind their
source to `127.0.0.1` before connecting to that same loopback address. This keeps
the source local rather than allowing a VPN or changing default interface to
select it. Failed TCP sockets close immediately. No remote address is accepted
by the embedded bootstrap, and no process-wide network binding is changed.

Manual and remembered same-boot connection ports are tried first. After a failure,
startup keeps reading discovery results rather than consuming one snapshot.
It retries a port up to three times with increasing delays. A change in network
identity, transport, interface addresses, routes, or process binding makes those
ports eligible again. Enumeration order and signal-strength changes do not.
The recovery loop has a 20-second window and at most 12 connection attempts;
an in-flight attempt retains the existing bounded TCP/authentication timeouts.
Continuous VPN switching cannot extend that window indefinitely.

Pairing and shell startup each allow one additional attempt when an I/O failure
coincides with a changed network signature. Stable-network pairing rejection
is returned for user correction. Existing pairing identity, shell-UID checks,
USB-debugging retention checks and payload verification are preserved. A live
helper Binder wins over a lost shell reply, avoiding a duplicate helper launch.

## Discovery lifecycle

On Android 13+, discovery uses a Wi-Fi `NetworkRequest` with `NOT_VPN` and no
internet requirement. This follows physical Wi-Fi while a VPN is the default
network. Earlier Android versions use the supported legacy discovery API.
Denied or unavailable scoped discovery falls back to that API too.

A network callback observes physical networks and VPNs, debounces changes, and
restarts discovery when its network signature changes. Port reads also check
that signature in case vendor callbacks are suppressed. A VPN reporting Wi-Fi
as an underlying transport cannot by itself satisfy the physical-Wi-Fi check.

Discovery generations reject callbacks from retired listeners. Service keys
include service type and network, so pairing and connection advertisements with
the same name have independent lifetimes. A service lost during resolution
cannot reappear through a late result. Queued work and resolved services are
bounded; a resolution deadline prevents one stalled result from blocking the
queue. Android 14+ also cancels timed-out or retired platform resolutions.
Closing discovery clears callbacks, timers and results. Startup replaces a
caller-owned discovery instance if its setup screen closes it while connecting.

The Android API contracts are documented in
[NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager).

## Limits

A VPN's enforced policy may still prohibit local access. If startup exhausts
its recovery window and a VPN was observed, the error explains how to allow
Unbound or local network access in the VPN settings. This change does not alter
VPN policy or promise to bypass lockdown. See Android's
[VPN bypass policy](https://developer.android.com/reference/android/net/VpnService.Builder#allowBypass()).
A reboot or stopped helper still requires a usable Wireless-debugging listener;
VPN recovery cannot start an absent Android ADB daemon.

## Validation

The focused host suite executes the production bootstrap, discovery adapter,
network-state adapter and socket helper against deterministic platform boundaries.
It passes **55 assertions** covering VPN enable/disable/replacement, route changes,
rapid switching, interrupted pairing, interrupted shell startup, a lost shell
reply after readiness, late listener discovery, retry limits, callback races,
resolution cancellation, API fallback and discovery ownership. The socket test
also opens a real TCP listener and verifies that both endpoints use loopback.
These simulations do not establish a real VPN tunnel.

The existing mobile-ADB, manifest, source-archive, glasses-network, automatic-AR,
setup-navigation, network-diagnostic and app-version suites also pass. Together
with the three new tests, **28 host tests pass**. The full launcher is built and
checked with the commands below; the audit verifies packaged source matching
for the discovery and modified ADB transport code as well as the existing
signature, manifest and payload checks.

```sh
python3 -m unittest discover -s tools -p 'test_vpn_adb.py' -v
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-vpn-adb-2965
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-vpn-adb-2965/the-unbound-launcher.apk --report artifacts/unbound-launcher-vpn-adb-2965/verification.json
```

No Android device was connected during this change. Real phone/VPN testing is
pending. The development APK has not been installed on a phone or published.
For a device check, exercise VPN off → on → off, switch between VPN profiles,
and repeat during Connect and start. Verify that Ready persists without a helper
restart after startup. Check the separate local-access error with a VPN policy
which deliberately blocks localhost.
