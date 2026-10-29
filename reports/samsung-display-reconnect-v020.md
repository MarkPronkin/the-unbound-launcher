# Samsung display reconnect loop on 0.2.0

Two community exports from Samsung phones running Android 17 show the external
XREAL display repeatedly appearing and disappearing on **0.2.0 alpha, build
2946**. Both start cycling before the first recorded SDK startup. The common
failure is display instability; the later control timeouts do not establish
what initially causes it. Neither export reaches AR Space.

XREAL Eye is optional for the One series' native 3DoF operation; XREAL describes
it as adding camera functions and 6DoF tracking on its
[One Pro product page](https://us.shop.xreal.com/apps/arenza/products/xreal-one-pro).
The second export reports its camera connected and still reproduces the loop.
It also reads the stable firmware seen in earlier successful handshakes, so the
previously resolved beta-firmware case cannot explain both reports.

## Comparison of the two exports

| Evidence | S26 Ultra report | Second report |
| --- | --- | --- |
| Export | `unbound-debug-20261029-004522-c1cf1297 (2).zip` | `unbound-debug-20261029-010609-e16d63d0.zip` |
| Phone | Samsung SM-S948U1 | Samsung SM-S938U |
| Glasses | One Pro, SDK type 6, USB 13080:1078 | One, SDK type 7, USB 13080:1080 |
| Camera status | `plug_out`, `cameraStatus=1` | `plug_in`, `cameraStatus=0`; native RGB camera state also reports present |
| Display observations | 45 additions and 45 removals, IDs 218 through 262 | 67 additions and 66 removals, IDs 50 through 116; last addition is at export |
| Median time before removal | 156 ms | 156.5 ms |
| First recorded SDK startup | 09:43:47.350, after the first three display additions | 21:04:44.015, after the first two display additions |
| Firmware | Unreadable on both native initialization attempts | First two initializations read system 3603, software `1.9.1.20260914175625`; later retries read empty strings |
| Ethernet | Enabled; first ping of each of two native attempts succeeds, then readiness pings and retained network captures fail | Enabled; all five readiness pings and every retained network capture succeed |
| Startup result | Control requests time out from request 15 onward | First two native handshakes succeed; the SDK then finds no external HDMI display. Later attempts time out during the handshake |

Times use each phone's local log time: GMT+09:00 for the S26 report and EDT for
the second report. The second application log retains older build 2924 history;
the display events and native initializations above belong to build 2946,
process 1757.

## S26 Ultra failure

The first observed display appears at 09:43:44.486 and disappears 17 ms later.
Subsequent display lifetimes range from 118 to 475 ms. SDK startup begins at
09:43:47.350, after the reconnect pattern is already present. USB access is
granted, and the readiness ping at 09:43:47.686 succeeds.

Requests 15, 240, 229, 16, 13, 333, 334 and 265 then time out. The SDK reports
`init=true`, but its ROI and display-mode queries fail, its firmware strings
are empty, and startup state is -1 with API error 1. A later native attempt
repeats the same sequence. The missing firmware response prevents identifying
this pair as the earlier beta-firmware problem.

The retained Android display evidence exposes 1920 by 1080 output, including
90 Hz and 60 Hz timing, rather than working 3840 by 1080 stereo. The helper's
recovery journal records Mirror routing and a global mirror override. The
system also records an external display initially disabled and powered off,
and physical display removal. These observations do not identify who initiates
each disconnect.

## Second report with XREAL One

The display starts cycling at 21:04:42.059, before SDK startup. Initial
reconnections alternate between 1920 by 1080 at 60 Hz and 90 Hz. Later ones
mostly alternate between 640 by 480 and 2560 by 1080 at 60 Hz; one reports
1600 by 900. All 66 completed display lifetimes are between 120 and 226 ms.

At 21:04:46.926, native initialization reads firmware system 3603, software
`1.9.1.20260914175625`. The ROI query succeeds with two entries, startup state
is 0 while DP initialization finishes, and `sdkSuccessConfig` reports success
at 21:04:50.160. The native connection closes at 21:04:50.229, followed by
timeouts on requests 29, 20, 281 and 78. At 21:04:52.022, the SDK finds
`hdmiDisplay=null` and reports startup failure.

The second initialization again reads the firmware and ROI successfully,
reports startup state 1 and SDK initialization success at 21:04:54.613. It
selects the original stereo target, EDID 5, but at 21:04:54.659 Android exposes
only the phone display to its query. `hdmiDisplay=null` again ends startup.
This directly establishes a failure after successful native initialization.

Three later native attempts time out on the same eight requests as the S26
case, although ping still succeeds. Their empty firmware strings must not
replace the valid version already captured. The existing build 2947 startup
summary correction preserves the earlier nonempty response.

## Code comparison and remaining cause

The released 0.1.2 and 0.2.0 APKs contain byte-identical `libnr_service.so` and
`libnr_glasses_api.so`. This was not a replacement of those native control and
USB libraries. Other components did change, including `libnr_api.so`, the
application startup hooks and helper display handling.

In `ManagedDisplayPolicy.tick`, 0.1.2 waits two seconds before restoring Mirror
and connection routing when the XREAL display disappears. Version 0.2.0
restores them on the first empty display snapshot, and reapplies them when
the same glasses return. Both reports show reconnect intervals around 1.3
seconds and a Mirror override in the helper journal. Repeated cleanup and
reapplication is therefore a concrete regression candidate to test. The
exports contain snapshots of the journal, rather than a timestamped history
of helper writes, so they do not prove this candidate causes the hotplugs.

A fix should distinguish a temporary reconnect of the same glasses from an
actual unplug or replacement monitor, retaining prompt restoration for the
latter. Accepting startup state -1 or treating ping as successful native
initialization would leave the missing external display unresolved.

## Five second reconnect grace

The helper now retains its display leases for 5 seconds of continuous glasses
absence. A reconnect cancels the pending cleanup, preserving the original
preferences even when Android assigns a new logical display ID. Time without
a display does not consume its hardware mode-switch deadline.

An ordinary replacement monitor, screen lock, severe heat or watchdog shutdown
still restores the preferences immediately. This change addresses repeated
cleanup during reconnects; its effect on the reported Samsung loop still needs
hardware validation.

All eight focused managed-display and platform tests pass. Fifteen new timing
assertions cover the cleanup deadline, reconnect cancellation, a fresh deadline
for each disconnect, retained mode preferences across logical ID changes, and
immediate restoration on a replacement monitor, lock or watchdog shutdown.

## Next reproduction

1. On the same phone, glasses, firmware and cable, check mirroring after a phone
   reboot before launching Unbound or starting its helper. The shell helper
   can outlive the app, so force-stopping the app alone is not an isolated
   baseline.
2. Capture the loop with 0.2.0 and compare 0.1.2 under the same conditions,
   starting the matching helper in each case. The S26 reporter's earlier
   successful mirroring is useful history; a matched capture would establish
   which display-policy writes change the result.
3. Record helper display enable, Mirror and restoration operations alongside
   physical hotplug events. The control trace already added in diagnostic
   build 2947 can distinguish an unacknowledged control request from a reply
   that the SDK fails to read; it cannot by itself establish the display-loop
   trigger.

The second reporter already has the stable firmware seen in working control
handshakes. A blanket beta downgrade recommendation does not fit that export.
For the S26 reporter, ask for the firmware version and beta enrollment status
before applying the earlier One Pro workaround. XREAL documents its update
methods in the [One Series FAQ](https://tutorials.xreal.com/docs/glasses/one-series/faq/).

## Suggested response to the reporters

> XREAL Eye is optional, and another affected setup reports its camera
> connected, so missing Eye does not explain this loop. Both logs show Android
> repeatedly losing and rediscovering the glasses display. The second setup
> even completes native initialization on stable firmware, then fails because
> the external display has disappeared. That points us toward display
> connection handling, with a possible regression in 0.2.0, rather than an Eye
> requirement. Please check whether plain mirroring works after a phone
> restart before opening Unbound, and share the glasses firmware version. A
> comparison with 0.1.2 using the same setup would help isolate the change.

## Retained evidence

Private raw exports and verified event counts are under
`artifacts/samsung-s26-onepro-020/` and `artifacts/samsung-s938u-one-020/`, each
with `export/` and `evidence.json`. Extraction checks verify display counts,
lifetimes, timeout sequences, firmware responses and readiness results. The
source now includes the reconnect grace change. No APK was built or device
modified for this investigation.
