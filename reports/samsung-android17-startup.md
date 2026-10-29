# Samsung Android 17 connection failures

The two supplied build 2924 exports stop at different startup gates. Neither
records a successful AR renderer launch. A third wearer reports the same screen
but supplied no debug archive or hardware identification, so that case cannot
yet be assigned to either failure.

| Export | Phone | Glasses | Confirmed startup blocker |
| --- | --- | --- | --- |
| `unbound-debug-20261027-192227-10879776.zip` | Samsung SM-S938U, Android 17 / API 37 | XREAL One, USB 13080:1080, SDK type 7 | Android's Ethernet service is disabled; both glasses interfaces are down. |
| `unbound-debug-20261028-191157-88bff0cc.zip` | Samsung SM-S928U1, Android 17 / API 37 | XREAL 1S, USB 13080:1086, SDK type 9 | Ethernet and native initialization succeed; the native startup-state request times out. |

Both session headers identify **0.1.2 alpha, build 2924**. The first app log also
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
