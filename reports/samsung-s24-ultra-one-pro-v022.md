# Galaxy S24 Ultra (SM-S928B) / XREAL One Pro: native controller fails behind the app-UID bind restriction on 0.2.2

The wearer reports *Connection paused* on every attempt.
`unbound-debug-20261029-172934-24399d5c.zip` identifies **Samsung SM-S928B,
Android 17 / API 37, Unbound 0.2.2 alpha build 2953**, with XREAL One Pro
(SDK model 6) on firmware **1.9.1.20260914175625**. Unbound verified that this
is the exact recommended stable release before the attempt.

**This is the same failure as the [Galaxy Z Fold 8](samsung-fold8-one-v021.md)
and [Pixel 10 Pro Fold](pixel10-fold-one-pro-v020.md) cases.** USB access, the
firmware check and the helper's `eth0` readiness ping all succeed. Native
`Leopard Mcu` then fails to start its control client after 20 seconds, and
Unbound shows *Connection paused.* Android denies the app UID
`SO_BINDTODEVICE`, and Wi-Fi is the default network, as on both Folds. Build
2956's candidate fix targets this.

All times below are the phone's local log time (GMT). Line numbers refer to
the unmodified extracted files under
`artifacts/samsung-s928b-onepro-022/export/`.

| Evidence | Observation |
| --- | --- |
| `app.log:585–586`, 17:28:59.741 | Firmware matches the recommended stable release and is accepted without an override. |
| `app.log:894`, 17:28:59.910 | Helper readiness ping over `eth0` succeeds (`ready=true`, 2.16 ms). |
| `app.log:979–980`, 17:28:59.926 | `Leopard Mcu Starting`, then `InitializeCodecFunctions succeed`. No `bind to net device` line. |
| `app.log:1102–1103`, 17:29:19.928–.938 | 20.002 s later: `Mcu Start Client`, `Leopard Mcu Started. 1`. |
| `glasses-startup.txt` summary | `init=false`, startup state -1, `mDpInitStateGSeries=false`; the SDK reports failure. |
| `network-diagnostics.txt:9,179` | Both app-UID `ping -I eth0` probes fail with `SO_BINDTODEVICE: Operation not permitted`. |
| `network-diagnostics.txt:160–168,330–338` | The shell helper's `ping -I eth0` receives 3/3 replies from `169.254.2.1` at both snapshots. |
| `network-diagnostics.txt:59–87` | Wi-Fi `192.168.0.61` (table 1044) and mobile data are up; `eth0` is 169.254.2.10/24, `eth1` is 169.254.1.10/24. |
| `glasses-control.txt:59,68` | Shell route lookups for both glasses peers select `wlan0`, source `192.168.0.61`. |
| `glasses-control-trace.txt` | 241 samples over 12.004 s show only UID 1073 DHCP sockets. No app-owned (UID 10855) TCP socket to either peer is captured. |

## Same model, opposite result

The [SM-S928U export](../artifacts/samsung-s928u-onepro-020/export/) (US
S24 Ultra, Android 17, build 2946, One Pro) is a near-exact control. It also
has Wi-Fi as the default network. Its app-UID `ping -I eth0` succeeds
(`exit=0`), and native MCU startup succeeds. The international SM-S928B
differs only in the bind denial and fails. This supports the earlier
conclusion that a runtime policy or module version, not the SoC or kernel
family, decides the restriction. The Pixel 10 Pro and Pixel 10 Pro Fold pair
shows the same split.

## Limits of this capture

This bundle does not show the native socket on Wi-Fi. It matches the Pixel 10
Pro Fold retest, not the Fold 8's fresh-process attempts:

- The attempt ID `1a188b535ca-5` comes from a per-process counter
  (`GlassesConnectionCompat.ATTEMPTS`). PID 1727 therefore made four earlier
  attempts before collection began at 17:28:45, and `app.log` does not retain
  them.
- The retained attempt logs `InitializeCodecFunctions succeed` without
  `bind to net device`, and no new app-owned control socket appears. That is
  the Fold 8's in-process retry signature, where the native client stays
  blocked behind its first failed connection. Unlike the Fold 8, no stale
  `SYN_SENT` socket remains here. An earlier socket may have exhausted its SYN
  retries before this trace started, but that is unconfirmed.

The routing diagnosis is therefore the leading explanation, not proven for
this phone. Display, renderer and immersive-window diagnostics are empty or
irrelevant because startup never reaches rendering.

## Next step

Build 2956 (`artifacts/unbound-launcher-glasses-network-2956/`,
SHA-256 `970a058a…af45`) binds the process to the glasses' Ethernet network
around native startup. It installs in place over public 0.2.2. Installing it
also starts a new app process, which clears any native client stuck from the
earlier attempts. This phone is a third validation target alongside the two
Folds.

To be conclusive, the retest needs **collection enabled before the first
attempt after a fresh app start**, so that the first native start is retained.
Success means `Native startup network selected`, an app-UID control socket
sourced from 169.254.2.10 reaching `ESTABLISHED`, and `Leopard Mcu Started. 0`.

Suggested reply to the reporter:

> Thanks, you did it exactly right, and the logs are very useful. Your One
> Pro is on the correct stable firmware, and Unbound can reach the glasses
> over the cable. The problem is on the phone's side: on your S24 Ultra,
> Android won't let Unbound tie the glasses' control connection to the USB
> cable, so it tries to go out over Wi-Fi and never arrives. After about 20
> seconds that shows up as *Connection paused*. A couple of other phones have
> the same problem, and we have a fix in testing. Nothing on your side needs
> changing.
>
> When the test build is ready, it would help a lot if you could try it. Turn
> on debug collection *before* the first connection attempt, then send a new
> debug file whether or not it works.

The ZIP SHA-256 is
`b422fa1b2d581b07bc7bf136e1f059ca072f366ae64aefbf1f79236adcbbb378`. The raw
export stays in the ignored artifacts directory. This investigation changes
documentation only. No runtime fix, APK build, publication or device
validation is claimed.
