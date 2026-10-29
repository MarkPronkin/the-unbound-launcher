# Pixel 10 Pro / XREAL One Pro: v0.1.2 startup failure

The October 27 export from **Pixel 10 Pro, Android 17 / API 37, build 2924**
shows AR startup blocked after switching the glasses to stereo. Android and
SurfaceFlinger expose only **640 × 480 at 60 Hz** after that switch. Unity and
the phone controller never start. This accounts for the missing touchpad; the
reported half-screen phone mirror is consistent with fallback output reaching
glasses that have already switched to stereo. The export cannot establish the
exact optical image or why the native mode disappeared.

The glasses are **XREAL One Pro**, SDK model 6. This report concerns a Pixel 10
Pro, rather than the earlier Pixel 10 Pro Fold networking report.

## Evidence

| Evidence | What it establishes |
| --- | --- |
| `app.log`, 19:02:44.238 | The paired helper's Ethernet readiness ping succeeds. |
| `app.log`, 19:02:50.042 | The native SDK reports successful startup. |
| `app.log`, 19:02:50.147–50.238 | The glasses initially output 1920 × 1080. The SDK selects Ethernet EDID 5 (`3840 × 1080 / 60 Hz`); the mode-setting command returns success. |
| `app.log`, 19:02:52.339–52.398 | A replacement display appears at 640 × 480, initially off and then on. The direct Presentation startup continues waiting for native output. |
| `app.log`, 19:03:12.252 | A second AR attempt still waits at 640 × 480. Neither attempt records the phone-owned Unity startup. Both entry activities finish before their 45-second readiness deadlines. |
| `display.txt`, external `mSfDisplayModes` | The hardware mode list contains only 640 × 480. The additional Android mode at 769 × 480 is explicitly an anisotropy correction. No 1920 × 1080 or 3840 × 1080 mode is available after the switch. |
| `surfaceflinger.txt`, external `displayModes` | Independently confirms that the compositor sees only 640 × 480, with no pending mode change. |
| `display.txt`, display 35 policy votes | The size cap is 3840 × 2160 and the refresh cap is 61 Hz. These permit the requested 3840 × 1080 / 60 Hz mode. |
| `network-diagnostics.txt` | Both app and helper diagnostic pings succeed with zero packet loss. |

The Pixel 8 workaround selects an **advertised** SBS mode and, when needed,
temporarily overrides a narrower framework size cap. Neither condition applies
here: the SBS mode is absent from the low-level list, and this size cap already
permits it. Selecting another display preference or forcing a mode vote cannot
manufacture the missing hardware mode.

Version 0.1.2 intentionally uses Android's **Mirror** connection routing so the
SDK can draw its native glasses Presentation without an external desktop task.
A successful session replaces the phone mirror with AR output. The routing
choice by itself does not explain this failure or establish that the spatial
desktop feature was removed. The Dimmer control can remain while startup is
blocked; it is not evidence that Unity or the trackpad has initialized.

## Separate stereo hook defect: fixed in source

The export also contains `UnboundStereo: NoSuchFieldException: mContext`.
The SDK's `XREALManager` does have `mContext`. The DEX patch passed the original
`p0` register to `ExperimentalStereo.selectConfig`, but the SDK reuses that
register for its G-series log string before returning. The hook consequently
receives a `String` instead of the manager.

`PatchExperimentalStereoDex` now allocates an untouched incoming receiver
register and copies it into the original receiver slot at entry. All original
selector instructions, branches and per-model decisions remain in place. The
hook uses the preserved manager, and the exact baseline DEX remains restorable.

**This fixes the hook warning, not the demonstrated VGA-only output failure.**
On this One Pro, the original selector already returns Ethernet EDID 5 and the
failed hook returns that same value unchanged. The log confirms that EDID 5 was
requested successfully despite the warning. Fixing the receiver therefore does
not change the requested stereo timing in this report.

## Validation

- The fixture transplants the actual SDK selector from the public v0.1.2 APK.
  An observing hook checks receiver identity and forwards to the production
  stereo policy. The released method fails on its first G-series case:
  `Stereo hook lost XREALManager receiver: java.lang.String`.
- The corrected patched method passes **32 Android assertions** on the isolated
  emulator, covering One Pro/model 20, legacy mono/stereo home choices, model 5,
  an external MR app choice, and opt-in enabled/disabled.
- All **three experimental stereo host tests** pass, including both mode
  namespaces, preference persistence, native byte/assembly checks, exact DEX
  restoration and rejection of duplicate/unrelated changes.
- All **three direct Presentation host tests** pass: **13 routing assertions**
  and **103 SDK-derived readiness checks**, plus exact lifecycle DEX restoration.
  The routing cases now include this VGA-only hardware mode list and confirm
  that it neither invents an SBS mode nor applies the Pixel 8 cap override.

Reproduce the selector comparison with:

```sh
python3 tools/verify_stereo_selector_runtime.py --serial emulator-5580 \
  --sdk-apk artifacts/releases/v0.1.2-alpha/the-unbound-launcher-0.1.2-alpha.apk \
  --output artifacts/pixel10-onepro-012/selector-release-regression
# Expected: failure identifying java.lang.String as the hook argument.
python3 tools/verify_stereo_selector_runtime.py --serial emulator-5580
python3 -m unittest discover -s tools -p 'test_experimental_stereo.py' -v
python3 -m unittest discover -s tools -p 'test_presentation_only.py' -v
```

Raw evidence and test output are retained locally under
`artifacts/pixel10-onepro-012/`; device identifiers and network addresses are not
copied into this report. No new launcher APK was installed or published for
this investigation. The connected Pixel 8 Pro was not modified.

## Remaining hardware investigation

The export establishes where startup stops, but does not identify whether the
mode loss comes from the phone's display driver, DisplayPort link negotiation,
the connection hardware or the glasses firmware. A Pixel 10 Pro / One Pro test
needs system display/DisplayPort logs around the transition from working 1080p
to EDID 5, and any EDID/link diagnostics available on that phone. App-UID logs
cannot supply the driver-side rejection reason.

Treat this combination as **AR not working on build 2924**. Do not present the
receiver fix, re-pairing ADB, disabling Desktop, or the Pixel 8 cap workaround as
a validated remedy. Native 1080p/stereo output and an actual AR/controller launch
must be observed before claiming recovery.

## In-app collection — development build 2941

Build 2941 adds `display-link.txt` to the existing Help debug ZIP. It collects
USB port/DisplayPort Alt Mode state, external display windows, recent logs from
fixed Android display/USB/composer tags, filtered kernel display/link messages
when available, exposed DRM/Type-C connector attributes and EDID, and the
helper's temporary display-policy recovery journal. Probe timestamps, exit
codes, timeouts, missing paths and permission denials remain in the report.
Android user builds may block kernel logs and connector files; successful
collection on an emulator does not establish their availability on this Pixel.

The existing paired helper performs fixed read-only probes. System log buffers
and `dmesg` retain their newest bounded output; unrelated kernel messages are
filtered out. Each subprocess has a deadline, connector reads have individual
deadlines, and link capture has a 30-second client deadline. The Binder operation
still accepts only the installed launcher UID and a fixed profile number.

With Debug mode and collection enabled, the app retains a baseline snapshot at
collection start and another on a connection failure. Failures arriving during
the baseline coalesce into one follow-up request. Export adds the current
snapshot. The latest three snapshots survive app restarts and stopping
collection, each limited to 200,000 UTF-8 bytes; the complete ZIP remains capped
at 15 MB. A pipe ownership race found during validation is also corrected:
reaching EOF now means the producer has released its busy state.

Tester steps:

1. Install the updated test APK and open **Settings → On-device ADB**. Ensure
   the updated helper is running. An export from an older helper explains that
   it must be restarted; the other diagnostics remain exportable.
2. In **Help**, enable **Debug mode** and **Collect debug data** before
   connecting the glasses.
3. Connect the glasses and reproduce the half-screen mirror/missing controller.
4. With the glasses still connected, return to **Help → Create debug file**.
   Keep them connected until the file is ready, then use **Share debug file**
   or **Save to Downloads**. The additional evidence is inside the same ZIP.

The Help page describes the additional identifiers and selected system logs.
This build gathers evidence for the unresolved display-link failure; it does
not claim to repair the Pixel 10 Pro / One Pro stereo output.

Validation evidence is under `artifacts/pixel10-onepro-diagnostics/`. Host checks
cover fixed commands, partial failure, cancellation, recent-log retention,
UTF-8 bounds, snapshot rotation across restarts and the maximum ZIP budget.
Android checks cover real Binder pipes, older helpers, stalled capture,
overlapping failures, the production export, sharing and Downloads. A separate
shell-UID emulator check executes the real probes. The test APK and complete
signed-package audit are recorded with that evidence.

Build 2941 passed all **six focused host tests**, **29 Android transport/export
checks**, **40 Help/share/Downloads checks**, and all three real probes executed
from the helper extracted from the signed APK. The complete APK audit passes,
including the new diagnostics, original permission set, source correspondence,
class uniqueness, alignment/signature and scoped SDK DEX restoration. The signing
certificate matches public v0.1.2, supporting an in-place update. Physical Pixel
10 Pro validation remains pending; this APK has not been installed on a physical
phone or published.

APK: `artifacts/unbound-launcher-2941-display-diagnostics-final/the-unbound-launcher.apk`.
SHA-256: `49729b53934f9b810c0b142d9be612a99e157e0124370b88fbe273f79f5bf834`.
Results: `artifacts/pixel10-onepro-diagnostics/validation.json` and
`signed-apk-audit.json` in the same evidence directory.
