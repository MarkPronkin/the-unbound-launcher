# Pixel 10 Pro Fold / XREAL One Pro: native controller startup still fails on stable firmware

## Candidate fix — build 2956

Build 2956 adds a scoped Android network selection around the original SDK's
synchronous native-startup callback. The existing `PingCallbackCompat` guard
now calls `GlassesNetworkCompat` after successful Ethernet readiness and
before native initialization. The native SDK libraries and startup callback
body are unchanged by this fix.

For Ethernet-series glasses, the adapter requires one authorized, unchanged
USB attachment and one unrestricted Android Ethernet `Network` with
169.254.2.10/24 plus a direct route to 169.254.2.1. It does not select Wi-Fi,
cellular, the glasses' separate 169.254.1.x network, or an arbitrary Ethernet
interface by name. It waits up to three seconds for Android to publish the
network after USB reconfiguration, then uses
`ConnectivityManager.bindProcessToNetwork` for the SDK callback. Missing,
ambiguous or rejected networks stop native startup rather than silently
falling back to Wi-Fi.

The callback includes native service initialization, MCU startup, the SDK's
original retry, and the startup-state query. A `finally` block restores the
previous process binding on success, SDK failure or exception. If the prior
network disappeared, it clears the temporary binding. A binding changed by
another owner is preserved. Each subsequent startup resolves the live network
again, so a reconnect does not reuse an obsolete Android network ID.
Air/H-series startup and failed readiness callbacks bypass network binding.

Android documents that process binding affects subsequently created sockets;
the established control socket keeps its selected network after the process
binding is restored. This is why the binding is scoped to synchronous native
startup instead of retained throughout AR. New internet connections and DNS
lookups in the launcher's main process temporarily use the glasses network
during that callback; other apps and processes retain their own routing.
See the official [ConnectivityManager API](https://developer.android.com/reference/android/net/ConnectivityManager#bindProcessToNetwork(android.net.Network)).

Local validation for build 2956:

- 26 host assertions cover network identity, rejection of the wrong interface,
  delayed network publication, USB detach/replacement, reconnects, callback
  exceptions, prior-network loss and restoration, and the real ping guard's
  once-only delivery. Existing Ethernet readiness and connection recovery
  suites also pass.
- An isolated Android 13 / API 33 emulator runs the adapter from the signed APK's actual
  DEX. Fourteen assertions verify supported unprivileged process binding,
  restoration, absent-glasses rejection and non-Ethernet behavior. A native
  libc socket receives mark 65637 for Android network 101, retains that mark
  after the process binding is restored, and newly created sockets use normal
  routing again. This checks the mechanism, not the Fold's vendor restriction
  or an actual glasses handshake.
- All 88 native libraries are byte-identical to development build 2955.
  The APK signature and 16 KB alignment pass; its certificate matches public
  0.2.2, allowing an in-place update.
- A separate ART fixture executes the built APK's original SDK startup callback
  and passes 14 assertions for native initialization/retry, startup errors,
  ready/not-ready state and the existing type-11 compatibility behavior.
- The full `verify_embedded_adb.py` package audit passes, including the new
  guarded-callback wiring, source archive, original DEX restoration checks,
  manifest and helper checks. The final APK hash matches the runtime test.

The test APK is
`artifacts/unbound-launcher-glasses-network-2956/the-unbound-launcher.apk`,
SHA-256 `970a058a620ae416ae7e75a171d1550e20b080fc154a9e97d6e041085baadf45`.
The build and runtime checks are retained alongside that APK and under
`artifacts/glasses-network-runtime-2956/`.

The fix targets the leading routing hypothesis. It does not establish that
the latest Fold failure had that cause. Installing the APK ends the previous
app process, which also clears any native client left stuck by the old build;
no separate stale-client recovery mechanism is claimed. Validation on this
Pixel Fold and the affected Samsung Fold 8 is still required. A successful
hardware check must show a control socket sourced from 169.254.2.10 reaching
`ESTABLISHED`, MCU startup result 0 and usable AR Space. A failure after native
startup succeeds would require a separate display investigation.
Reconnect/resume testing must also check whether the native SDK opens later
control sockets outside the synchronous startup callback; those would not
inherit this temporary process binding.

For the hardware retest, install build 2956 over the current app, reopen it,
confirm the on-device helper is Ready, and reconnect the glasses with Wi-Fi
left on. Keep collection enabled before the first AR attempt and export a
fresh debug ZIP if startup still fails. Look for `Native startup network
selected` and `Native startup network restored` in the retained startup log,
then check the app-owned control socket and native MCU result as above.

## Stable-firmware retest — 0.2.2 alpha, build 2953

The follow-up `unbound-debug-20261029-183737-8284b7ea.zip` identifies the same
**Google Pixel 10 Pro Fold, Android 17 / API 37, XREAL One Pro (model 6)**,
now on **Unbound 0.2.2 alpha build 2953**. The reporter says they downgraded
to stable, and the app independently verifies the exact recommended firmware:
**1.9.1.20260914175625**. Startup still fails inside the native controller.
Returning to stable therefore did not resolve this phone's failure. The
earlier beta-firmware explanation is insufficient.

The current connection is attempt `1a188f38efa-6`, PID 17154, app UID 10524,
on October 29 at 11:37 MST / 18:37 UTC. The ZIP also retains the October 28
failure in `app.log` and an older network snapshot. Those are historical
data, not additional attempts on build 2953. The repeated first-failure and
recent-timeline sections in `glasses-startup.txt` describe the same attempt.

Line numbers in this section refer to the unmodified extracted files under
`artifacts/pixel10-fold-onepro-022-stable/export/`.

| Evidence | Observation |
| --- | --- |
| `app.log:20512–20513`, 11:37:05.830–05.831 | Firmware matches the exact recommended stable version and is accepted without an override. |
| `app.log:20897`, 11:37:06.320; `glasses-startup.txt:59`, 11:37:08.483 | SDK result 2 requests MTP reconfiguration, then the attachment continuation resumes the same attempt. |
| `app.log:21678–21687`, 11:37:08.976 | The helper's interface-bound readiness ping receives a reply from the glasses; `ready=true`. |
| `app.log:21762–21763`, 11:37:09.019 | Native `Leopard Mcu` starts and logs `InitializeCodecFunctions succeed`. |
| `app.log:21873–21876`, 11:37:29.021–29.065 | After 20.002 seconds, `Mcu Start Client` is followed by `Leopard Mcu Started. 1` and plugin-start error 1. |
| `glasses-startup.txt:109–119` | Initialization is false, startup state is -1, DP initialization is false, and the connection screen reports failure. |
| `network-diagnostics.txt:198–203,392–397` | Both current app-UID probes fail with `SO_BINDTODEVICE: Operation not permitted`. |
| `network-diagnostics.txt:263,357,378–387,572–581` | Ethernet is enabled, `eth0` has 169.254.2.10/24, and current failure/export helper probes each receive 3/3 replies. |
| `glasses-control-trace.txt:10–26` | 241 samples over 12.014 seconds show only DHCP UDP sockets belonging to UID 1073, not the app's UID 10524. No app-owned control TCP socket is captured. |
| `glasses-control.txt:48–66` | The failure snapshot also retains only DHCP sockets. Unbound shell route lookups select Wi-Fi for both glasses peers. |
| `device-and-session.txt:21–22`; `glasses-startup.txt:119` | The glasses remain at 1920 × 1080 / 60 Hz. There is no completed native startup or stereo transition. |

**The leading suspect is the app's control-network selection/interface-binding
restriction, but this ZIP does not prove the native socket used Wi-Fi.** The
[Galaxy Z Fold 8 case](samsung-fold8-one-v021.md) demonstrates that failure:
the app's control TCP socket has a Wi-Fi source address and remains in
`SYN_SENT`, despite successful helper pings. This Fold has the same app-UID
bind denial, successful helper readiness and native client-start error, now
with stable firmware independently verified. It has no retained native
`bind to net device` line or app-owned peer socket, however. A shell-UID route
lookup cannot establish the native app socket's route.

The trace starts at 11:37:05.906, before MTP reconfiguration, and ends at
11:37:17.920, about 8.9 seconds into the 20-second native start. It observes
the beginning of native startup, but not its final error. Missing TCP rows
cannot distinguish a native client that never opened a socket, an earlier
stuck client, a socket outside the sampled namespace, or a brief socket
between samples. The Fold 8's retries make stale native-client state worth
checking, but this capture does not establish that it happened here. The
attempt counter and collection start do not prove a fresh app process.

The export-time `RejectedExecutionException` says a control diagnostic is
still finishing. It occurs after the SDK has already failed; it is not the
AR startup cause. Earlier failure-time and cancellation snapshots are
retained. No renderer geometry sample or immersive Presentation capture is
available because startup never reaches rendering. This is not evidence of
the separate Pixel 10 Pro stereo/VGA failure.

The next development step is to investigate the native client's network
selection and lifecycle, using the network-binding proposal in the Fold 8
report as a candidate fix. Validation needs a trace from a fresh app process
covering the full native start, native bind/connect return values and errors,
and proof that the app's control socket uses the glasses Ethernet address and
connects. The helper's successful ping alone cannot validate that fix. No
further firmware downgrade is indicated by this bundle.

Suggested reply to the reporter:

> Thanks for retesting. The debug file confirms that your One Pro is on the
> exact recommended stable firmware. USB access works and Unbound can reach
> the glasses over the cable, but the native XREAL controller still fails
> after about 20 seconds, before AR Space starts. Android is also restricting
> the app's network-interface binding on your Fold. That is our main suspect
> for the remaining connection failure, although this capture does not prove
> the exact cause. There is no need to change firmware again based on this
> log; we need to investigate the app's control connection on this phone.

The follow-up ZIP SHA-256 is
`d21cedb5dd9051cbd060d25b103e881a3757717fd32e6907823d15b57777b17c`.
Local `artifacts/pixel10-fold-onepro-022-stable/evidence.json` retains checked
source anchors, file hashes and capture limits. Raw exports remain in the
ignored artifacts directory. This update changes documentation only; no
runtime fix or successful device validation is claimed.

## Original 0.2.0 report and initial firmware hypothesis

The following records the earlier investigation and its original next test.
The stable-firmware retest above supersedes the rollback advice.

The supplied `unbound-debug-20261029-003537-1e9530b8.zip` identifies **Google
Pixel 10 Pro Fold, Android 17 / API 37, Unbound 0.2.0 alpha build 2946**, with
XREAL One Pro (SDK model 6, USB 13080:1078). The report was created on October
28 at 17:35:37 MST, October 29 in UTC.

**AR startup fails inside the native glasses controller, before rendering or
stereo mode selection.** USB permission and the helper's Ethernet readiness
check succeed. Native `Leopard Mcu` startup then stalls for 20 seconds and
reports `Mcu Start Client`, followed by plugin-start error 1. The SDK returns
`init=false` and startup state -1. The reporter subsequently confirmed that
the glasses ran **beta firmware**. Firmware incompatibility was initially the
leading suspect, given the earlier One Pro case resolved by returning to
stable. The exact beta version remains unknown. The later 0.2.2 retest above
confirms the failure persists on the exact recommended stable release.

All times below are the phone's local log time. Line numbers refer to the
unmodified extracted files under `artifacts/pixel10-fold-onepro-020/export/`.

| Evidence | Observation |
| --- | --- |
| `app.log:87`, 17:34:48.703 | The main process observes external display 18, XREAL One Pro, at 1920 × 1080 / 60 Hz. |
| `app.log:996–1001`, 17:34:52.915–52.967 | The first SDK call returns result 2 after requesting an MTP configuration change; the connection waits for USB to reconnect. |
| `app.log:1293`, 17:34:54.991 | The attachment continuation resumes the same connection attempt automatically. This is one attempt with two SDK calls, rather than two failed native handshakes. |
| `app.log:1790–1796`, 17:34:55.448 | The paired helper's fixed `eth0` readiness ping succeeds, with one reply and zero loss. |
| `app.log:1874`, 17:34:55.502 | Native `Leopard Mcu` starts. |
| `app.log:1980–1983`, 17:35:15.505–15.515 | After 20.003 seconds, `Mcu Start Client` is followed by `Plugin Start failed: retcode=1, id=nr_leopard_mcu_id`. |
| `app.log:2006–2020`, 17:35:15.529–15.564 | Glasses control startup fails; native initialization is false, startup state is -1, API error is 1, and the connection screen reports failure. |
| `network-diagnostics.txt:184–193,378–387` | Failure-time and export-time helper probes each receive all three replies. Ethernet is enabled and both glasses interfaces have addresses. |
| `display.txt:273–275`; `surfaceflinger.txt:173–175` | Android and SurfaceFlinger independently retain native 1920 × 1080 / 60 Hz output. |

The retained app log has one external display addition in the main process,
no display-removal callback, and nine periodic observations of the same display
18 at 1920 × 1080 / 60 Hz. The three processes each logging its addition must
not be counted as three physical reconnects. There is no successful SDK
configuration, firmware response, numbered control-request timeout, native
client-connection message or phone-owned Unity startup in this capture.

The original MTP reconfiguration completes: the USB configuration changes from
`mtp=2` to `mtp=1`, and startup resumes without another Activity intent. The
USB camera-status query reports `plug_in`. Neither missing USB permission nor
an absent camera explains the recorded native client failure.

The [earlier Pixel Fold readiness failure](connection-startup-recovery.md#ethernet-readiness-through-the-paired-helper--build-2912)
stopped at the SDK's unprivileged ping. That restriction remains visible in
this ZIP's diagnostic app-UID probes as `SO_BINDTODEVICE: Operation not
permitted`, but the actual startup ping now runs through the helper and
succeeds. That warning must not be presented as the current failing readiness
check. A successful helper ping also does not establish that the app's native
TCP client can connect to the glasses.

This differs from the [Pixel 10 Pro VGA report](pixel10-pro-one-pro-v012.md):
that phone completed its native handshake and lost native modes after the
stereo switch. This Fold never reaches that switch. Its 1080p mode therefore
does not establish a stereo-mode failure. It also lacks the repeated external
display removals seen in the [Samsung reconnect reports](samsung-display-reconnect-v020.md).

The three control snapshots are all taken after native initialization has
failed. They show only connected DHCP UDP sockets to the two glasses peers,
with netlink access and interface statistics denied. The missing TCP rows do
not prove that a TCP connection was never attempted or that a firewall
blocked it; the socket may already have closed. Unbound shell route lookups
choose Wi-Fi, while the explicitly interface-bound helper ping reaches the
glasses. Those lookups belong to the shell UID, not the native app socket.
Network selection is a candidate to investigate, rather than a demonstrated
routing defect.

The [Samsung beta-firmware case](samsung-android17-startup.md#resolution-beta-glasses-firmware)
did reach a control connection and read a firmware version before its numbered
requests failed. This archive does neither, so the native failure signatures
must not be called identical. The user's follow-up confirms beta firmware
enrollment for this Fold's One Pro, independently of the ZIP. It does not
establish the exact version or that it matches the other case's
`2.0.0.20260901182643` build.

The next useful test is to record the current glasses firmware version,
return to stable firmware through XREAL's supported process, and retry the
same launcher build, phone and connection hardware. Record the stable version
and whether AR actually starts. The prior One Pro wearer's successful rollback
makes this a reasonable targeted test, rather than a validated fix for the
Fold. XREAL's [One Series firmware guide](https://tutorials.xreal.com/docs/glasses/one-series/faq/)
documents the official OTA updater; the public guide does not describe leaving
the beta channel. If the beta enrollment/update interface does not offer the
stable release, obtain the rollback procedure from [XREAL support](https://www.xreal.com/support).

If stable firmware still fails, collect a fresh export with a control trace
starting before Enter AR Space, leaving collection on until the failure and
export finish. Existing diagnostic build 2947 adds the
app UID and kernel socket states, queues, timers and retransmission counters
for the fixed glasses peers. It can distinguish an early TCP connection stall
from a connection that succeeds and then stops exchanging data, where Android
allows the reads. Its 12-second trace observes the beginning of this failure;
it would end before the full 23-second sequence from the first SDK call
finishes. A capture intended to include the final client error needs a longer
trace window. Native connect/bind return values and errors, or a matched
working control handshake on the same glasses, would further narrow the cause.

Suggested reply to the reporter:

> Thanks for the debug file. It confirms that Unbound detects your One Pro,
> gets USB access and reaches the glasses over Ethernet, but the native XREAL
> controller fails to start after about 20 seconds, before AR rendering begins.
> Since your glasses are on beta firmware, that is now the leading suspect:
> another One Pro wearer resolved a startup failure by returning to stable.
> Please record your current firmware version, return to stable using XREAL's
> supported process, and retry the same Unbound version. Let us know the stable
> firmware version and whether AR starts; if it still fails, send a fresh debug
> file. This still needs confirmation on your Fold.

The ZIP SHA-256 is
`51a677d981662b7c1be3662e98a5ff630cc5cdf1e33feb3abc6f7f23a9764d57`.
Local `evidence.json` retains 23 checked source anchors, file hashes and event
counts. Raw exports stay in the ignored artifacts directory. This investigation
changes documentation only; no runtime fix, APK build, publication or physical
device modification is claimed.

## Update — Galaxy Z Fold 8 comparison, 2026-10-29

A [Galaxy Z Fold 8 / XREAL One export](samsung-fold8-one-v021.md) on
**stable** firmware shows the same 20-second `Mcu Start Client` failure and
the same app-UID `SO_BINDTODEVICE` `EPERM`. Its build 2949 trace shows the
native control socket leaving through Wi-Fi. Beta firmware is therefore no
longer the only candidate for this Fold; see that report for the comparison
and for why this export's signature is not identical.
