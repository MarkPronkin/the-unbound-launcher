# Samsung SM-G990B / XREAL One: shell readiness ping denied on 0.2.5

`unbound-debug-20261108-173317-f2c835b2.zip` identifies Samsung **SM-G990B,
Android 16 / API 36**, Unbound **0.2.5 alpha, build 2967**, and XREAL One
(SDK type 7). Startup stops at the Ethernet readiness check, before native
control initialization or Unity rendering. This is an additional failure path
to the [earlier Samsung startup reports](samsung-android17-startup.md).

## Evidence

Line references are to the original extracted bundle, retained locally under
`artifacts/debug-samsung-g990b-20261108/`. Times use the phone's local log time.

- `glasses-startup.txt:495`: the USB firmware reader detects
  `1.9.1.20260914175625`, matching this build's recommended stable XREAL One
  firmware. USB access succeeds before startup.
- `network-diagnostics.txt:61`: `eth0` is up with `169.254.2.10/24`.
  `eth1` also has its glasses address. Lines 136 and 151–152 show Ethernet
  enabled and both interfaces provisioned, with directly connected routes.
- `glasses-startup.txt:518` and subsequent polls: the readiness transaction
  returns `exit=2; timedOut=false; ready=false`, followed by
  `ping: SO_BINDTODEVICE: Operation not permitted`.
- `network-diagnostics.txt:183`: the independently collected **ADB shell
  helper** ping fails with the same denial. App-UID pings also fail. Moving
  the original ping from the app to shell therefore does not resolve this case.
- `glasses-startup.txt:566`: the last attempt fails approximately 5.1 seconds
  after SDK startup, with `nativeStarted=false`. The six retained SDK-result
  events all report failure. Attempt 5 ends during a detach before SDK startup.
- `glasses-control-trace.txt`: the three samples contain only system DHCP
  sockets to the glasses peers, and no app-owned native control connection.
  These samples do not prove that no packet ever crossed the interface.
- `glasses-control.txt`: the unbound shell route lookup selects Wi-Fi for the
  control peer. Removing `-I eth0` would therefore not establish glasses-link
  reachability.

The initial pinned failure context includes a USB permission error during
unplugging. Subsequent attempts regain USB access and pass firmware validation;
the repeated connected failure is the shell ping denial. The export contains
no renderer sample, consistent with startup stopping before that stage.

## Wearer verification and current correction

The wearer subsequently reported several days of successful connections on a
**Samsung S21 FE (SM-G990B), One UI 8.0 / Android 16**, with XREAL One glasses,
a USB-C cable and the on-device ADB helper. Their local change allows startup
when shell ping fails with the exact message
`SO_BINDTODEVICE: Operation not permitted`. They report fully working AR Space,
including stereo rendering and head tracking. This is wearer-reported hardware
verification of their patch.

Current `EthernetReadinessCompat.Reply.ready()` applies that change: exit zero
still succeeds, and a non-timed-out reply containing the exact message also
satisfies readiness. This error occurs before a probe is sent, so it does not
establish that the glasses link is unreachable. `AndroidProbe` returns an
accepted reply directly, without invoking the earlier ICMP fallback.

`GlassesNetworkCompat` still validates and selects the glasses control network
before native startup. Missing helpers, timed-out replies, ordinary packet loss,
missing routes, USB detach and cancellation retain their failure behavior.
Other interface-binding denials retain the existing ICMP fallback and require
a successful echo.

The new regression failed before this change and passes afterward. Nine focused
host tests pass, including 93 client-readiness assertions covering the exact
denial without an ICMP reply, recovery-note prefixes, timeouts, invalid helper
output, ordinary failures, USB detach and cancellation. The control-network,
ICMP, helper, Ethernet-recovery and connection-startup checks also pass.

## Earlier ICMP fallback in development build 2969

Before the wearer's follow-up, development build 2969 retained the working shell
ping path and sent completed failures explicitly naming `SO_BINDTODEVICE` and
a permission denial to a new `GlassesIcmpProbe` fallback. A timed-out helper,
an absent helper, ordinary packet loss and missing routes retained their
existing failure behavior.

The fallback uses the existing glasses network selector: an unrestricted
Ethernet Android `Network`, the exact `169.254.2.10/24` address, and its directly
connected route to `169.254.2.1`. The current authorized glasses USB attachment
is checked before the request and again before accepting its result.

The probe binds one unprivileged IPv4 ICMP datagram socket through
[`Network.bindSocket(FileDescriptor)`](https://developer.android.com/reference/android/net/Network#bindSocket(java.io.FileDescriptor)),
binds the fixed local address, and sends one echo request to the fixed glasses
peer. This API routes that socket through the chosen Android network without
changing process routing. The kernel supplies the ICMP identifier and checksum.
Acceptance requires the correct peer, echo-reply type/code, sequence, packet
length and a fresh random payload. The network is revalidated before sending
and after a matching reply. A one-second receive deadline, short receive slices,
interruption checks and descriptor cleanup bound failure and cancellation.

A successful echo still goes through the existing scoped native startup
network selection and the original SDK handshake. That earlier build required
an echo reply after any recognized interface-binding denial. It introduced no
additional Android permission or helper transaction.

## Validation of the earlier ICMP build

Focused host checks pass: 81 client-readiness assertions, 42 ICMP assertions,
44 control-network assertions, 26 helper-ping assertions and 42 Ethernet
recovery assertions. The readiness fixture reproduces the exact shell denial
and rejects a failed fallback, a detached device, unrelated permission errors,
packet loss and timed-out helper work.

The signed APK's ICMP implementation passes 23 assertions under Android ART as
ordinary app UID 10094. A real kernel echo succeeds in 2 ms; dropping the echo
replies produces failure after 1007 ms. Repeated probes do not leak descriptors,
and process routing stays unchanged. This isolated emulator fixture supplies
its active Android network and temporary loopback peer addresses; USB/Ethernet
selection is tested separately. It does not reproduce Samsung's driver or
firmware. Fixture addresses and firewall rules are removed after the check.

The signed test APK is
`artifacts/unbound-launcher-samsung-icmp-2969/the-unbound-launcher.apk`, SHA-256
`2273f87f220bc14fe2d699da9e6e5c4d2a67244e21b6720f76cf02dd7036a1c1`.
Its signing certificate matches public build 2967. Local validation evidence
is retained beside the extracted bundle, including `icmp-runtime/verification.json`.
All 253 host regression tests pass, with no failures or skips. The full
signed-APK audit passes, including class uniqueness, permissions,
corresponding sources, and scoped DEX/native restoration.

## Retest

Build an APK from the current sources and install it as an update. Start the helper under
**Settings → On-device ADB → Connect and start**, enable **Help → Debug mode →
Collect debug data**, and reconnect the glasses. On this device, the expected
readiness log is `exit=2; timedOut=false; ready=true` with the exact
`SO_BINDTODEVICE: Operation not permitted` message, followed by control-network
selection and native initialization. Export while connected if startup still
fails. The earlier build 2969 APK documented above does not include this direct
readiness change.

The wearer has verified their local patch on physical SM-G990B / XREAL One
hardware. A fresh APK built from these sources has not yet been checked on
that device.
