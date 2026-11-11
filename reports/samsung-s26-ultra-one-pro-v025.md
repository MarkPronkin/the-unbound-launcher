# Samsung S26 Ultra / XREAL One Pro: stalled firmware reads and control transport

The supplied `unbound-debug-20261109-025329-ee53d3f0.zip` identifies Samsung
**SM-S948U1, Android 17 / API 37**, Unbound **0.2.5 alpha, build 2967**, and
XREAL One Pro (USB 13080:1078, SDK type 6). The wearer reports a fresh install
with USB and wireless debugging enabled, followed by unplugging and reconnecting.

**The archive confirms the glasses match the app's supported firmware.** The
attached screenshot shows a timed-out read incorrectly described as
"Unsupported firmware," with `Detected: Not verified`. It does not establish
old firmware. One captured attempt verifies every component, then fails later
during native control startup. No attempt records a successful AR launch.

Line references below use the original bundle extracted locally to
`artifacts/debug-samsung-s26-ultra-20261109/`. Times are the phone's local log
times on November 8; the archive filename uses UTC on November 9.

## Firmware evidence

- `app.log:3118`: attempt `1a1be33f2cc-3` reaches the 15-second read threshold
  at 18:47:33.035. The UI pauses initialization while the read is still active.
- `app.log:3159`: Software returns at 18:47:38.444, approximately 20.46 seconds
  after the check began, reporting `1.9.1.20260914175625`. The lower-level log
  already records that matching version at 18:47:18.006 (`app.log:2999`),
  followed immediately by `close fd call java` and `close fd : 215`. USB close
  and the native wrapper return only at 18:47:38.442 (`app.log:3155–3158`).
  The long delay is therefore inside USB-handle cleanup after a successful
  version response. The next native open/ready handshake repeatedly times out
  (`app.log:3226–3240`); no MCU result is recorded before USB disconnect at
  18:48:15.043.
- `app.log:5441`, `5507`, `5564`: attempt `1a1be363b9c-4` reads Software
  `1.9.1.20260914175625`, MCU `15.1.03.603_20260914`, and DSP
  `15.A.01.097_20250430`. All three match the model's pinned recommendation.
  `firmware_result` reports `supported=true` at 18:49:47.821, approximately
  135 ms after the check began.
- `app.log:7411`: attempt `1a1be368ed4-6` pauses after another 15-second
  threshold at 18:50:24.048. No component response is recorded before the
  wearer exits at 18:51:06.103. The next attempt is held because that native
  request is still finishing.

Increasing the timeout would delay the warning, but would not explain why
another check finishes in 135 ms or why a later SDK handshake fails. Allowing
SDK startup while a firmware read is still in flight would overlap native
commands. Neither change is an established fix.

## Separate native control failure

After the complete firmware match, `app.log:5874–5890` records a successful
shell readiness ping, approximately 1.98 ms, followed by selection of Android
network 124 for native startup. This is different from the Samsung S21 FE
report's denied shell readiness ping, and does not show the older Wi-Fi
routing failure.

`glasses-control-trace.txt:16–19` shows the app-owned socket (UID 10501) from
`169.254.2.10:55098` to `169.254.2.1:52999`. Its TCP handshake takes about
1.1 seconds and retransmits a SYN. It briefly drains its send queue, then
accumulates unacknowledged data. At the trace's final snapshot, line 94,
`sendq=3933`, `recvq=0`, and the retransmission timer is active. Line 98 records
`eth0.rx_errors=440` over the 12-second sample, with only seven additional
received packets. These counters cover the whole interface and do not identify
the cause or which protocol requests reached the glasses. A zero sampled
receive queue does not prove that the SDK never read a reply between samples.

`app.log:6025–6045` records timeouts for requests 15, 240, 229, 16, 13, 333,
334 and 265. At line 6052, native startup reports result 1 at 18:49:53.434.
The captured display remains 1920 × 1080 and no Unity renderer starts.

The trace supports a stalled or degraded control transport after network
selection. It does not establish a firmware-version incompatibility, a specific
Samsung driver defect, or a cable defect. Packet capture or an affected-device
comparison is needed to distinguish those causes. The USB-read stalls and TCP
transport failure may share a cause; the archive does not prove that they do.
The `ERR_UNSUPPORTED_CMD` immediately before the firmware query refers to the
optional internal-code lookup and also occurs during the successful 135 ms
check. It is not evidence that the installed firmware version is unsupported.

## Source correction

Firmware policy results now distinguish `SUPPORTED`, `MISMATCH` and
`UNVERIFIED`. Empty responses, exceptions, unknown models and timeout screens
use `UNVERIFIED`; a nonempty differing version uses `MISMATCH`. Exact matching
and all supported model recommendations stay the same.

An unverified result displays **Firmware check paused**, explains that the
firmware has not been verified and may already be current, and gives reconnect
instructions. It does not direct the wearer to downgrade. While a read is
active, Exit remains available and continuation remains disabled. A completed
failed read offers **Continue without verification**. A late mismatch updates
the title, explanation, instructions and override together; a complete late
match resumes the existing connection exactly once.

Diagnostics now record each component before calling its USB reader, completed
read durations, and read errors. The timeout detail identifies the current
component when available. These changes improve reporting; they do not claim
to repair the native USB reader or TCP transport.

## Validation and remaining work

The connection-startup suite passes four host tests, including 115 firmware
policy assertions and 136 connection recovery assertions. Coverage includes
empty/error results, a slow Software read, a matching Software response followed
by a stalled MCU read, late completion, explicit overrides, cancellation and
USB replacement. The production warning compiles into the isolated Android
harness and passes 18 runtime assertions in each of small portrait, short
landscape and large-text layouts on an Android 13 emulator. These checks verify
message transitions and action availability, not Samsung hardware behavior.
Startup-retention and control-network host checks also pass, with 34 and 44
assertions respectively. Local UI evidence is under
`artifacts/firmware-timeout-s26/runtime/`.

A physical S26 Ultra / One Pro retest is still required. Preserve the same
firmware and export while connected if it fails again; component-start events
will identify a stalled USB call. For the post-verification failure, compare a
known working direct USB-C cable or another host and capture the native control
traffic on the affected phone. Do not mark this hardware combination working
based on the warning correction alone.
