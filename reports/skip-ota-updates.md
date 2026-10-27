# Skip glasses firmware updates — build 2900

Users reported that Unbound still entered the glasses firmware update screen
and remained at 0%. They also reported that enabling **Skip OTA** in the
official app's developer settings avoided its update flow.

The local My Glasses 2.2.2 code confirms the switch: `XREALManager.getOtaNum`
reads the `SKIP_OTA` boolean and returns zero when it is enabled. The presenter's
`checkNum` interprets zero pending updates as permission to continue to
`_goToSdk`. The original getter still calls the native firmware comparison
before applying this switch, and initializes the One Pro/model 20 SKU as a
side effect. A forced update calls `_goToOta` directly and skips this decision.

Earlier port builds disabled five low-level flash/boot methods, but that did
not disable the higher-level update decision, progress UI, or manager's OTA
state. Blocking flashing alone can therefore leave that UI waiting at 0%.
The reports do not establish which path each affected device entered.

Build 2900 makes skip-OTA behavior unconditional in the manager, irrespective
of saved developer preferences, and removes the native pending-update probe.
It retains the original model/SKU initialization and instruction addresses.
Both `_goToOta` and its queued UI callback now continue through the existing
connection preparation entry, covering forced recovery as well as ordinary
update attempts. This entry queues SDK work on the presenter's worker and
deduplicates requests, including callbacks arriving on the UI thread. The
connection recovery adapter already bypasses cloud/cache
firmware checks and preserves SKU identity before every SDK start.
The five low-level flash/boot guards remain in place.

This is a build-time patch, so users do not need to enable a hidden setting or
clear app data. Firmware updates continue to be handled separately through
XREAL's updater. Skipping an update does not establish compatibility with
older firmware.

`tools/test_skip_ota.py` audits the real pinned DEX, composes the patch with
connection recovery, verifies both update entries, the unconditional decision,
SKU initialization and all five existing flash/boot guards. Exact restoration
must reproduce the input DEX; repeated patching and unrelated changes are
rejected. The production APK verifier repeats these checks and reverses the
patch chain to the pinned baseline.

Local validation on 2026-10-27 passed: 18 test methods across skip OTA,
connection recovery, app version, embedded manifest and bundled sources;
APK signature/alignment checks; and the complete production APK audit.
The signed candidate is under `artifacts/unbound-launcher-skip-ota-2900/`,
with `build.json`, `verification.json` and an APK SHA-256 file.

Validation on glasses that exhibited the 0% screen is pending. The separate
reconnect audio investigation remains paused.
