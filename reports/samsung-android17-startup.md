# Samsung Android 17 connection failures

The three supplied exports stop at two different startup gates. None records a
successful AR renderer launch. A further wearer reports the same screen but
supplied no debug archive or hardware identification, so that case cannot yet
be assigned to either failure.

| Export | Phone | Glasses | Confirmed startup blocker |
| --- | --- | --- | --- |
| `unbound-debug-20261027-192227-10879776.zip` | Samsung SM-S938U, Android 17 / API 37 | XREAL One, USB 13080:1080, SDK type 7 | Android's Ethernet service is disabled; both glasses interfaces are down. |
| `unbound-debug-20261028-191157-88bff0cc.zip` | Samsung SM-S928U1, Android 17 / API 37 | XREAL 1S, USB 13080:1086, SDK type 9 | Ethernet and native initialization succeed; the native startup-state request times out. The glasses report the same 2.0.0 beta firmware as the One Pro case. |
| `unbound-debug-20261029-003229-a5781d65.zip` | Samsung SM-S928U, Android 17 / API 37 | XREAL One Pro, USB 13080:1078, SDK type 6 | The same control-request timeouts as the 1S, on 0.2.0 alpha. **Resolved:** XREAL beta glasses firmware; the wearer reports startup works after returning to stable firmware. |

The first two session headers identify **0.1.2 alpha, build 2924**; the third
identifies **0.2.0 alpha, build 2946**. The first app log also
contains older build 2915 collection history; its final build 2924 attempts
reproduce the same network failure. Times below use each phone's logged local
time, rather than the UTC timestamps in the export filenames.

## USB chooser in the screenshot

The screenshot offers Gallery, MTP Host and Photos. The original
`XREALManager.startSdk` explicitly enables MTP when its camera-status query
returns zero. Both exports record `set mtp==1`, a USB detach/reattach, and a new
USB descriptor containing an MTP interface (class 6, subclass 1, protocol 1).
That sequence is consistent with Android offering media-import handlers for
the newly exposed interface. AOSP's [Gallery manifest](https://android.googlesource.com/platform/packages/apps/Gallery2/+/refs/heads/main/AndroidManifest.xml)
declares such a USB-attached media-import handler.

The debug collection is scoped to the application's UID, so it does not prove
the exact system resolver intent behind the screenshot. It does show successful
Unbound USB permission grants and startup continuing after the re-enumeration.
This is separate from Unbound's deferred SDK USB permission callback.

Dismiss the chooser with Back and return to Unbound. Selecting a media-import
app does not address either captured startup blocker. Unbound deliberately has
no USB handler activity, as documented in [the USB permission notes](xreal-usb-permission.md).

## SM-S938U with XREAL One

At 15:21:24.057, `UnboundUSB` records successful USB access. At 15:21:26.283,
the connection flow resumes after USB reconfiguration. Its helper readiness
pings repeatedly return `exit=2`, `ready=false` and
`connect: Network is unreachable`, followed by connection failure at
15:21:31.575. Retrying reproduces the failure.

The three retained network captures at 15:21:55, 15:22:06 and 15:22:27 establish
the missing prerequisite:

```text
Ethernet State: disabled
eth0             DOWN
eth1             DOWN
```

Both tracked interfaces have `up: false`, no IP client and no link addresses.
The glasses USB configuration has already changed to `ncm=1, ecm=1`; enabling
those functions in the glasses does not enable Android's Ethernet service.
This explains the readiness failure without attributing it to DeX, missing USB
permission or a display-resolution problem. The export does not establish who
or what disabled Ethernet.

With the glasses attached, open **Settings → Connections → More connection
settings → Ethernet**, enable Ethernet if the phone offers the switch, and
retry Unbound. Samsung documents that menu in its
[connection settings guide](https://www.samsung.com/us/support/answer/ANS10002546/).
The glasses provide the USB network interfaces; no separate Ethernet adapter
is needed for this test. A successful retry still requires wearer confirmation.

## SM-S928U1 with XREAL 1S

At 12:11:27.497 the helper readiness ping succeeds. Later network captures
also receive all three replies with no packet loss, and Ethernet is enabled
with an address on each glasses interface. The native service connects over
`eth0`, reads the HMD identity and firmware version, and reports
`nativeGlassesInit` success.

Startup then fails on specific control requests:

| Time | Native result |
| --- | --- |
| 12:11:28.154 | Request 333 times out; `GetDisplaySupportedRois ret:1, count:0`. |
| 12:11:28.654 | Request 334 times out; `GetDisplayDpResolutionMap ret:1, count:0`. |
| 12:11:29.167 | Request 265 times out; `nativeGetGlassesStartupState` reports error 1 and startup state -1. |

The SDK turns state -1 into startup failure at 12:11:29.167. On the second
attempt, requests 15, 240, 229, 16 and 13 also time out, followed by 333, 334 and
265. The reported firmware strings are empty on that attempt. The first two
of these broader timeouts occur before the wearer presses Back at 12:11:46.838;
cancellation cannot explain the original failure or all of the retry failures.
The trace records the first native service stopping at 12:11:45.506 and the
second being created at 12:11:45.664, so it does not establish overlapping native
service lifetimes.

The immediate blocker is an unresponsive startup/control channel despite
successful ping. The captured native SDK version is
`3.1.2.20261009101910`; the first attempt reads glasses system 3563 and software
`2.0.0.20260901182643`. Firmware/SDK protocol compatibility and a stalled glasses
control session remain possible explanations. These are different component
versions; their numbers alone do not establish outdated firmware. The SDK
already maps the 1S USB IDs 1085/1086 to type 9, so missing USB model recognition
is not the observed blocker.

The same SDK receives successful ROI and display-mode-table replies in the
One Pro comparison export at
`artifacts/pixel10-onepro-012/export/app.log:1123`. Its optional
`libexternal_alg.so` load warning also appears in that comparison, so that
warning does not explain the 1S-specific request failures. Verified excerpts
and the native startup-result mapping are retained in
`artifacts/one-s-startup-native/evidence.json`. A packet capture is needed to
establish whether the timed-out requests reached the glasses.

The 1S exposes 1920 × 1200 output in this report. The failure occurs before
renderer launch or stereo-mode selection, so changing renderer resolution or
forcing a successful readiness result is not an established fix.
The current 1080-line display predicates also need a separate audit for the
1S's 1200-line output after the control handshake works; that later compatibility
question does not explain this pre-renderer failure.
The existing [type 11 startup fallback](sony-xperia-type11-startup.md) applies
to unsupported-command error 103 on a non-Ethernet model. This report has
timeout error 1 on an Ethernet model and must retain its failure behavior.

First unplug the glasses fully, force-stop Unbound, reopen it and reconnect to
test a fresh application and glasses control session. If the failure repeats,
check the 1S firmware with XREAL's official updater and collect another export
while connected. XREAL documents the update paths in the
[One Series FAQ](https://tutorials.xreal.com/docs/glasses/one-series/faq/).
An update is a diagnostic step; the captured evidence does not establish that
a newer firmware fixes these requests. If it persists, reproduce the control
handshake on the same hardware and compare the SDK and firmware before
changing startup acceptance.

## Automatic Ethernet recovery in build 2943

The paired helper now attempts to enable Android Ethernet after a failed
readiness ping when the service explicitly reports that it is disabled. The
request includes the exact USB attachment identity; a separate shell worker
revalidates the installed launcher UID and live glasses before calling
`IEthernetManager.setEthernetEnabled(true)`. This uses the shell's existing
`NETWORK_SETTINGS` permission and adds no application permission.

The helper makes one recovery attempt per attachment. An already-enabled,
absent or unrecognized service keeps the original ping behavior. Legacy clients
without attachment data receive only the original read-only ping. The operation
does not change IP configuration, and enabling the service never substitutes
for a real successful readiness ping or the native startup handshake.

The Android service API and required permission are defined in
[AOSP EthernetServiceImpl](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/service-t/src/com/android/server/ethernet/EthernetServiceImpl.java).
Fourteen focused regression tests pass, covering USB grants, Ethernet readiness,
startup-state handling and connection recovery. The final focused checks include
26 helper assertions, 42 recovery assertions and 53 client-readiness assertions.
The full helper and client Java sources compile. On the connected Pixel 8 Pro /
Android 17, a shell-UID call to the real `IEthernetManager.setEthernetEnabled(true)`
succeeds with Ethernet enabled before and after the call; the phone was never
switched off for this check. Evidence is under
`artifacts/ethernet-auto-enable-2943/`.

Samsung hardware validation of recovery from its disabled state, including IP
address assignment after enabling, remains pending. The 1S control-channel
failure requires separate investigation. No timeout acceptance or 1S startup
bypass is included.

Avoiding the original SDK's forced MTP enable remains a separate candidate
change that must retain required NCM/ECM configuration and camera/storage
behavior. Suppressing that chooser alone would leave both captured blockers
unresolved.

The signed test APK is
`artifacts/unbound-launcher-ethernet-2943/the-unbound-launcher.apk`, SHA-256
`d8d93d89f881a28e14097282f943b1e473e9b189a17f44ca39aa598403c3d993`.
The full signed-APK audit passes, including original permissions, class
uniqueness, corresponding sources and scoped DEX/native restoration. Its signing
certificate matches build 2942. The original SDK DEX files and both NR native
libraries are byte-identical to 2942; changes do not bypass the 1S handshake.
Install as an update and start the updated helper under **On-device ADB → Connect
and start**, then reconnect the glasses. This APK has not been installed on the
physical phone during this task.

## Startup diagnostics in build 2944

Debug collection now writes `glasses-startup.txt` separately from the noisy
application log. It retains the first startup failure across retries and app
process restarts, a recent timeline, observed timeout request IDs/counts and
SDK/firmware/startup-state messages. Connection events carry attempt IDs and
passive Java bookkeeping for USB reconfiguration, SDK callbacks, retries,
cancellation and controller readiness. Diagnostics never invoke additional
native SDK getters or change startup acceptance.

`glasses-control.txt` keeps three timestamped control-channel snapshots. They
capture fixed-peer TCP/UDP socket state, route lookups and eth0/eth1 link and
packet/error/drop counters before and after the queries. Capture is requested
before SDK startup and at the first observed control failure, independently of
the slower display dumps, and again at export. Queues and producer slots are
bounded; an old, absent or blocked helper leaves readable availability evidence.
Network failure capture also precedes the expensive display/link capture.

The recent timeline is capped at 96 KB and first-failure context at 64 KB; the
startup report including its summary is capped at 165 KB. Each control snapshot
is capped at 32 KB. The application still collects a 12 MB log ring; a ZIP with
startup evidence reserves 300 KB by exporting the most recent 11.7 MB of that
ring. The complete ZIP retains its 15 MB hard limit with all existing display,
SurfaceFlinger, network and link-history budgets present. Starting a new
collection explicitly resets the pinned startup failure; automatically resuming
an existing collection preserves it.

The supplied 1S archive replay preserves requests 333/334/265, the SDK version
and first firmware strings after reconstructing the trace, while excluding
notification payloads from the startup report. Nine host regressions pass,
including 65 archive, 30 startup-trace, 50 display/control and 77 connection
assertions. Android passes 26 cross-process control-pipe assertions and 45
Help/collection/export assertions, including opt-out and retaining the first
failure alongside a retry. The existing display/export path passes another
31 Android assertions with both new evidence files present. Evidence is under `artifacts/startup-debug-2944/`
and `artifacts/control-diagnostics-runtime/`.

The real read-only control capture runs under shell UID 2000 on the Pixel /
Android 17 in 867 ms. That phone denies netlink socket visibility and has no
glasses interfaces attached during the check; the report records both facts.
Samsung may impose different restrictions. Socket state and aggregate interface
counters do not establish that a particular control request reached firmware.
These diagnostics improve the next 1S report; they do not resolve its timeout.

Install the update, start its helper under **On-device ADB → Connect and start**,
then enable **Help → Debug mode → Collect debug data** before connecting the
glasses. Reproduce the failure and create a debug file while the glasses remain
connected. The first failure stays available if the wearer retries before
exporting.

The signed build is
`artifacts/unbound-launcher-startup-debug-2944/the-unbound-launcher.apk`, SHA-256
`a7e9479581d7f6b9ec47c7ec9a2b4f7c1ca2abb07b1633cb63924883b2b4e3fe`.
The full APK audit passes, including bundled sources, original permissions,
class uniqueness and the complete scoped DEX/native restoration checks. The
original SDK DEX files and both NR native libraries match build 2943 exactly.
The complete APK has not been installed on the physical phone during this task;
Samsung/1S capture still needs a fresh wearer report.

## SM-S928U with XREAL One Pro on 0.2.0 alpha

The third export reproduces the 1S failure with different glasses. USB access is
granted, Android Ethernet is enabled, both glasses interfaces have addresses and
every retained ping to `169.254.2.1` succeeds with no loss. At 20:32:13.144 the
native service connects over `eth0`. The glasses answer the clock-correlation
request within 5 ms, and the SDK logs `HMD Device type(local):11,11` and
`Supported Devices: 1537 -> 1537`.

Those lines are identical in the working Pixel 10 Pro / One Pro export, where
the ROI reply arrives 11 ms later (`artifacts/pixel10-onepro-012/export/app.log:1123`).
Here, request 333 times out at 20:32:13.685, then 334 and 265 follow at 500 ms
intervals. The SDK reports startup state -1 and fails at 20:32:14.687. The retry
receives no reply at all, starting with request 15, and its firmware strings are
empty. The request sequence and counts match the 1S export exactly, and both
report the same firmware.

The control snapshot taken at the failure shows one established socket to
`169.254.2.1:52999` with 39 bytes in its send queue. Two earlier sockets remain
in FIN-WAIT-2: the glasses acknowledged their close but never closed their own
side. Samsung denies netlink socket details and sysfs interface statistics to
the shell. One snapshot cannot separate an unacknowledged request from one
written just before the capture.

The USB configuration list, MTP handling and camera status match the working
Pixel 10 Pro One Pro export. Disabled Ethernet, USB permission, USB
configuration and model recognition are therefore not the blocker. The two
failing reports come from different wearers, phone variants, time zones and
networks. They share three properties that no session with a successful control
handshake has:

| | Both S24 Ultra failures | Every successful control handshake |
| --- | --- | --- |
| Phone | Galaxy S24 Ultra, Samsung build `CP2A.260605.016` | Pixel 8 Pro, Pixel 10 Pro, OnePlus 15 |
| Glasses refresh rate at SDK start | 90 Hz, Samsung's default mode | 60 Hz |
| Glasses firmware | system 3563, software 2.0.0.20260901182643 | system 3603, software 1.9.1.20260914175625 |

The Samsung Fold export also drives XREAL One at 90 Hz, but it stops at the
earlier Ethernet failure. Requested 90 Hz modes in the experimental-stereo tests
stayed at 60 Hz. No captured session therefore establishes a control handshake
at 90 Hz or on firmware 2.0.0, and the evidence cannot yet separate these three
properties. The export is retained under `artifacts/samsung-s928u-onepro-020/`.

### Resolution: beta glasses firmware

The wearer reports that downgrading the glasses from XREAL's beta firmware to
the latest stable firmware resolved the failure. The phone did not change, so
the S24 Ultra and its Samsung build are not the blocker for this case. Firmware
`2.0.0.20260901182643` (system 3563) is therefore the beta build. Its lower
system number and earlier build date than stable 1.9.1 (system 3603) are
consistent with a separate beta branch. The original SDK 3.1.2 does not complete
its control handshake with that build.

No export after the downgrade has been supplied. The stable version installed
and the refresh rate during the successful startup are therefore not captured.
The report alone does not rule out a 90 Hz contribution on other firmware, but
90 Hz did not prevent startup on this phone once the beta was removed.

The 1S export reports exactly the same firmware strings and the same timeout
sequence. Its wearer should check whether the glasses are enrolled in XREAL's
beta channel and move them to the latest stable firmware with XREAL's updater
([One Series FAQ](https://tutorials.xreal.com/docs/glasses/one-series/faq/)).
That remains a likely, unconfirmed fix for the 1S. Unbound does not install or
change glasses firmware.

## Control-channel trace in build 2947

The 0.2.0 control snapshots show the request stall only as one queue value at
one instant. Build 2947 records the stall as it happens:

- **`glasses-control-trace.txt`.** The first SDK start of each attempt starts
  a 12-second helper trace. It reads `/proc/net/tcp`, `tcp6`, `udp` and `udp6`
  every 50 ms and writes a line only when a socket to `169.254.2.1` or
  `169.254.1.1` appears, changes or closes. Each line includes the unacknowledged
  send queue, the unread receive queue, a retransmit or zero-window timer, the
  retransmission count, owner UID and `eth0`/`eth1` packet counters. A retry
  inside the window is covered by the running trace. Each trace is limited to
  24 KB. The first trace since collection began is kept beside the latest two,
  and an export waits for a trace still in progress.
- **Control snapshots** add decoded kernel socket rows, which carry the timers,
  retransmissions and owner UIDs that `ss` omits without netlink. When sysfs
  statistics are denied, as on this Samsung, interface counters come from
  `/proc/net/dev`.
- **Startup events** record each physical external display's active mode,
  refresh rate and available rates at SDK start, SDK result and failure.
  `glasses-startup.txt` summarizes the mode at the last SDK start.
- **The startup summary** now retains `HMD Device type` and `Supported Devices`
  lines. An empty retry no longer replaces the real firmware version. Replaying
  the One Pro export restores `system:3563, software:2.0.0.20260901182643`,
  which its 0.2.0 summary had lost. The device report adds the app UID for
  matching socket owners.

The One Pro case no longer depends on this trace. It remains useful for other
control-channel failures, including firmware a future SDK does not support. The
trace distinguishes these explanations:

| Trace during the timeout | Meaning |
| --- | --- |
| `sendq` stays above 0 with `timer=retransmit` and rising `retrans` | The glasses' TCP stack does not acknowledge the request. |
| `sendq` stays above 0 with `timer=zero-window-probe` | The glasses accept data but stopped reading their socket. |
| `sendq` returns to 0 while `recvq` stays 0 | The request is acknowledged and no reply is sent. |
| `recvq` stays above 0 | A reply arrived; the SDK did not read it. |

The trace is read-only and starts no network connection or SDK command. It
cannot see unconnected UDP sockets, other network namespaces or changes
shorter than 50 ms, and it does not identify which request the firmware
received. Startup acceptance, timeouts, refresh-rate selection and the original
SDK are unchanged.

### Verification

Host regressions pass: 28 control-trace assertions, covering real kernel row
formats, IPv4-mapped `tcp6`, short TIME_WAIT rows, UDP drops, change-only
sampling, byte limits, read failures, interruption and late samples. Also 57
display-diagnostic, 34 startup-trace and 69 debug-archive assertions. The
archive checks include the worst-case 15 MB ZIP with maximum traces and the
380 KB startup reservation. The Android emulator passes 34 cross-process
assertions over real Binder and pipes. They show that a running trace cannot
delay the failure snapshot or link capture, and that the released 0.2.0
helper's rejection of the new selector produces a helper-restart explanation.

On an Android 13 emulator, a root-only fixture aliases `169.254.2.1` onto
loopback and connects to port 52999. It then stops the client while a reply
arrives, and later drops inbound packets while a 39-byte request is written.
The production sampler, running as shell UID 2000 in the `shell` SELinux
domain, records `recvq=128` for the unread reply. For the dropped request it
records `sendq=39`, `timer=retransmit`, `retrans` rising from 0 to 4 and RTO
backing off from 200 ms to 3.26 s. The acknowledged request leaves no line. It
takes 221 samples in 11 s with no late samples and 3.7 KB of output. The
fixture restores the emulator's addresses and firewall afterward. Evidence is
under `artifacts/control-trace-2947/`.

The signed build is
`artifacts/unbound-launcher-control-trace-2947/the-unbound-launcher.apk`, SHA-256
`3e16ed0265ed8814e8a15ad5a680980f0fc6ea54df458c53561e6ccbda65818e`. The full
signed-APK audit passes. Against the published 0.2.0 APK, only the manifest
version, the shell helper, the app DEX and the bundled corresponding sources
change. The original SDK DEX files and both NR native libraries are
byte-identical. The complete APK has not been installed on a physical phone
during this task.

Install it as an update, then start the updated helper under **On-device ADB →
Connect and start**. An older running helper returns an explanation instead of
a trace. Turn on **Help → Debug mode → Collect debug data**, then connect the
glasses. Reproduce the failure, then create the debug file with the glasses
still connected; an export started during a trace waits for it to finish.
