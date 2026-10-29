# Pixel 10 Pro Fold / XREAL One Pro: native controller startup fails on 0.2.0

The supplied `unbound-debug-20261029-003537-1e9530b8.zip` identifies **Google
Pixel 10 Pro Fold, Android 17 / API 37, Unbound 0.2.0 alpha build 2946**, with
XREAL One Pro (SDK model 6, USB 13080:1078). The report was created on October
28 at 17:35:37 MST, October 29 in UTC.

**AR startup fails inside the native glasses controller, before rendering or
stereo mode selection.** USB permission and the helper's Ethernet readiness
check succeed. Native `Leopard Mcu` startup then stalls for 20 seconds and
reports `Mcu Start Client`, followed by plugin-start error 1. The SDK returns
`init=false` and startup state -1. The reporter subsequently confirmed that
the glasses run **beta firmware**. Firmware incompatibility is now the leading
suspect, given the earlier One Pro case resolved by returning to stable.
The exact beta version remains unknown, and this Fold has not yet been
retested on stable firmware, so its cause and recovery are not confirmed.

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
