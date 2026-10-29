# Galaxy Z Fold 8 / XREAL One: native control socket leaves through Wi-Fi on 0.2.1

The wearer reports *Connection paused* with an XREAL One, while an XREAL Air 1
works on the same phone. `unbound-debug-20261029-060024-42e5f5d7.zip`
identifies **Samsung SM-F971U, Android 17 / API 37, Unbound 0.2.1 alpha build
2949**, with XREAL One (SDK model 7) on firmware **1.9.1.20260914175625, the
recommended stable release**. Unbound verified that version before each
attempt.

**The glasses' native control client never connects, because its TCP
connection is routed over Wi-Fi instead of the glasses' USB Ethernet link.**
USB access, the MTP reconfiguration and the helper's `eth0` readiness ping all
succeed. Native `Leopard Mcu` then logs `bind to net device=eth0` and opens a
TCP connection to the glasses' control port, `169.254.2.1:52999`. That
connection leaves with the phone's Wi-Fi address, `192.168.0.147`, as its
source and stays in `SYN_SENT`. After 20 seconds the SDK reports
`Mcu Start Client`, startup state -1, and Unbound shows *Connection paused.*
All three attempts fail this way, including one after a phone restart.

All times below are the phone's local log time. Line numbers refer to the
unmodified extracted files under `artifacts/fold8-one-2949/export/`.

| Evidence | Observation |
| --- | --- |
| `app.log:9121`, 01:58:11.754 | Firmware check: detected 1.9.1.20260914175625, which matches the recommended stable release. |
| `app.log:10264`, 01:58:16.829 | Helper readiness ping over `eth0` succeeds. |
| `app.log:10361`, 01:58:16.873 | Native log: `bind to net device=eth0`. |
| `glasses-control-trace.txt:71`, 01:58:16.910 | The app UID (10544) opens `192.168.0.147:54844 → 169.254.2.1:52999` in `SYN_SENT`. Five retransmissions follow during the 12-second trace, and none is answered. |
| `app.log:10472–10475`, 01:58:36.874 | 20.002 s after the start: `Mcu Start Client`, `Leopard Mcu Started. 1`, plugin start error 1. |
| `glasses-control.txt:38,53` | At the failure snapshot the same socket is still `SYN_SENT` (8 retransmissions, RTO 16 s). |
| `glasses-control.txt:62` | A lookup without interface binding resolves `169.254.2.1 via 192.168.0.1 dev wlan0 table 1044 src 192.168.0.147`. |
| `network-diagnostics.txt:9,184,342` | Each app-UID `ping -I eth0` fails with `SO_BINDTODEVICE: Operation not permitted`. |
| `network-diagnostics.txt:165–175,323–333` | The shell helper's `ping -I eth0` receives 3/3 replies from `169.254.2.1` at both failure snapshots. |
| `network-diagnostics.txt:66–139` | `eth0` has 169.254.2.10/24 and its own table 1161. Wi-Fi (table 1044) is the default network. |

The earlier attempts, at 01:54:32 and 01:55:32, fail with the same 20.003 s
client error (`app.log:4619–4622,7660–7663`). In the first, the trace
records SYN source `192.168.0.147` as well (`glasses-control-trace.txt:16`).

## Why the socket is on Wi-Fi

A socket bound to `eth0` with `SO_BINDTODEVICE` cannot use a Wi-Fi source
address. The kernel's route lookup skips nexthops on other interfaces and
otherwise selects an `eth0` address. The native socket's `192.168.0.147`
source therefore shows that the bind the native library logged did not take
effect. The bundled XREAL `libnr_*` libraries reference `setsockopt`, and
none references `android_setsocknetwork`. No bind failure is logged. On this phone,
Android returns `EPERM` for `SO_BINDTODEVICE` from the app UID. The unbound
socket follows the app's default network, Wi-Fi, to the home router, and the
glasses never see it.

**The working Pixel 10 Pro is the matched control.** In
`artifacts/pixel10-onepro-2949/export/` the phone is also on Wi-Fi, and its
shell route lookup also chooses `wlan0`. Its app-UID `ping -I eth0` succeeds,
however, and the native socket opens from `169.254.2.10`. It becomes
`ESTABLISHED` and `Leopard Mcu Started. 0` follows within 40 ms. Wi-Fi alone
is not sufficient for this failure; the bind restriction is the deciding
difference.

Every prior export with an Ethernet-series XREAL (One or One Pro) fits this
pattern:

| Export | App-UID `SO_BINDTODEVICE` | Native MCU start |
| --- | --- | --- |
| SM-S938U, SM-S948U1, SM-S928U (build 2946) | permitted | succeeds |
| Pixel 10 Pro (builds 2924 and 2949) | permitted | succeeds |
| Pixel 10 Pro Fold (build 2946) | `EPERM` | 20.003 s client error |
| Galaxy Z Fold 8 (build 2924, before helper readiness) | `EPERM` | stopped at the SDK ping |
| Galaxy Z Fold 8 (build 2949, this report) | `EPERM` | 20.002–20.003 s client error |

Every export in the table had Wi-Fi up. The OPPO CPH2791 exports also show
`EPERM`, but they use the Air 2 Pro, a non-Ethernet model, so they have no
bearing on this pattern.

The **Air 1 works** because Air-series glasses are not Ethernet-series in the
SDK (`isEthernet=false`). They never open this `169.254.x` control
connection.

**Firmware is not the cause here**: the glasses run the exact recommended
stable build. This also reopens the [Pixel 10 Pro Fold report](pixel10-fold-one-pro-v020.md),
which names beta firmware as the leading suspect. That export has the same
app-UID `EPERM`, Wi-Fi as default network and 20.003 s client error. Its only
retained native start, however, logs `InitializeCodecFunctions succeed` and
no `bind to net device` line, and no TCP socket to the glasses appears in its
snapshots. That matches the Fold 8's second, in-process attempt (below)
rather than its fresh-process attempts. Build 2946 also did not record socket
source addresses. A shared cause is plausible but unconfirmed.

The source of the `EPERM` is not identified. Upstream Linux has allowed an
unprivileged first `SO_BINDTODEVICE` since 5.7, so an Android or vendor
policy denies it on these phones. The Pixel 10 Pro and Pixel 10 Pro Fold
share a SoC and Android version yet differ, so the deciding factor is not
simply the kernel family. A runtime policy, flag or module version is more
likely. Unbound does not need to identify it to work around it.

## Secondary observation: the stuck socket survives retries

The first attempt's socket (inode 5915981, port 48624) is never closed. When
the second attempt's trace starts, it is still `SYN_SENT` at retransmission 9
with a 32 s RTO (`glasses-control-trace.txt:44`). It reaches retransmission
10 and a 64 s RTO, and no new control socket appears in that window. The
second native start logs `InitializeCodecFunctions succeed` but no
`bind to net device` line (`app.log:7531–7532`). In the working SM-S948U1
export, by contrast, a second start in the same process logs the bind again
and connects (`samsung-s26-onepro-020/export/app.log:6532–6534`). Retrying in
the same process therefore seems to leave the native client blocked behind
the stuck connection. Only after the phone restart does attempt 3 open a new
socket, which fails the same way.

## Proposed fix (not implemented)

Route the native client through the Ethernet network without a privileged
bind. Before the SDK's native start, find the connected `Network` whose
`LinkProperties.getInterfaceName()` is `eth0`, and call
`ConnectivityManager.bindProcessToNetwork(network)`. Android's netd client
then marks sockets created in the process with that network, so the
connection resolves through table 1161 and uses source `169.254.2.10`. This
requires no privilege, because the Ethernet network is `NOT_RESTRICTED`.

Questions to settle before building:

- **Scope.** A process-wide binding sends every new socket over `eth0`,
  which has no internet. Find out whether the native client opens later
  connections during reconnect or resume. If not, binding only around the
  native start, then calling `bindProcessToNetwork(null)`, is the narrowest
  change. Otherwise, keep the binding for the AR session and confirm that
  nothing in that process needs internet meanwhile.
- **Interface identity.** The SDK pings `169.254.2.1` on `eth0`, and this
  phone's control peer is on `eth0`. Match the network by its address or
  route to `169.254.2.0/24`, not only by interface name, and fail visibly
  when it is absent.
- **Stale client.** After a failed start, verify that the next attempt
  actually opens a new socket. If the native client keeps the old one,
  recovery needs a process restart or a cleanup hook.

Validation needs a phone with the `EPERM` restriction: this wearer's Fold 8 or
the Pixel 10 Pro Fold. Success means the control trace shows source
`169.254.2.10`, the socket reaches `ESTABLISHED`, and `Leopard Mcu Started. 0`
follows. A phone that permits the bind, such as the Pixel 10 Pro, only checks
for regressions.

An optional wearer-side test can confirm the routing diagnosis before a build.
Start Unbound with Wi-Fi on so that its helper reports Ready. Then turn on
Airplane mode, confirm that Wi-Fi and mobile data are off, connect the One
and enter AR Space. Without Wi-Fi or cellular, Android may make an Ethernet
network the default. If it chooses `eth0`, AR should start. If it chooses
`eth1`, the attempt still fails, so a failure does not refute the diagnosis.
This is not a workaround to recommend.

Suggested reply to the reporter:

> Thanks for the debug file, it pinpoints the problem. Your One's firmware is
> the correct stable version, and Unbound reaches the glasses over USB. The
> One, unlike the Air 1, needs a network-style connection over the cable. On
> your Fold 8, Android won't let Unbound tie that connection to the glasses,
> so it goes out over your Wi-Fi instead and never arrives. That is why you
> see *Connection paused* after about 20 seconds. This needs a fix in Unbound
> rather than anything on your side, and we're working on it.
>
> If you'd like to help confirm it: open Unbound with Wi-Fi on until it's
> ready, then turn on Airplane mode (make sure Wi-Fi is off), plug in the One
> and enter AR Space. If AR starts, that confirms it. If it doesn't, that's
> still useful to know; please send another debug file.

The ZIP SHA-256 is
`85ff64cdb7c108dbb56f3cc65310be6adf9b21b2f4e84f6a0ad6a0f0803838f3`.
Local `artifacts/fold8-one-2949/evidence.json` retains source anchors, file
hashes and the comparison set. Raw exports stay in the ignored artifacts
directory. This investigation changes documentation only. No runtime fix,
APK build, publication or physical device modification is claimed.
