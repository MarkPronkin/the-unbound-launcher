# Retroid Pocket Nova app-window aspect ratio

The reported Files window is consistent with a missing fixed display-rotation
offset in the helper's surface geometry. A guarded correction is implemented;
visual confirmation on the Retroid and glasses is still pending.

## Evidence

The supplied `unbound-debug-20261030-053038-d420c045.zip` is from Unbound
0.2.3 alpha / 2957 on a Moorechip Retroid Pocket Nova, Android 13 / API 33,
with XREAL One Pro glasses. The attached images show compressed or letterboxed
Files content inside landscape and portrait window frames.

- `display.txt` identifies the physical glasses device as 3840 × 1080 with
  **device rotation 3 (270°)**. Its logical display is 3840 × 1080 with
  **logical rotation 1 (90°)**; the compositor's current orientation is 0.
- `app.log:8187` requests a 1920 × 1080 app display. At `app.log:8217` the
  size callback instead reports **1080 × 1920, rotation 1**.
- Switching to the 900 × 1200 portrait preset produces a
  **1200 × 900, rotation 1** callback at `app.log:9709`. Switching back repeats
  the reversed 1080 × 1920 callback at `app.log:15766`.
- The retained renderer snapshots show a consistent 1968 × 1134 eye target
  and projection scale aspects around 1.76 during these changes. They do not
  establish optical correctness, but do not show a corresponding global
  renderer aspect change.

The export-time display dump contains no surviving app virtual display. The
physical device's fixed offset is directly captured; the same offset on the
app display remains an inference from its reversed dimensions. The fix reads
each affected virtual display's own metadata rather than assuming it shares
the glasses' offset or detecting the Retroid by model name.

## Change

The original `ActivityContent.updateSize()` undoes logical rotation when
calculating its buffer dimensions. It assumes device rotation is zero.
Android's [LogicalDisplay compositor setup](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/display/LogicalDisplay.java)
adds logical and fixed device rotation for displays that rotate with content.
For a 90° logical rotation and a 270° fixed rotation, the buffer rotation is
therefore 0°.

`AppWindowGeometry.rotation()` supplies that buffer rotation to the extracted
helper's `ActivityContent.getRotation()` call. Existing surface sizing,
resize notifications and Unity window geometry consume the same corrected
value. Only this helper call is rewritten; display flags retain the existing
non-secure policy and physical display configuration is unchanged.

The correction requires all of the following:

- A launcher app display with an exact reversal between logical dimensions,
  logical rotation and physical mode dimensions.
- Its exact virtual display unique ID in `dumpsys display`, shell ownership,
  and the `ROTATES_WITH_CONTENT` flag.
- A device rotation that reconciles the observed dimensions with the physical
  mode. Missing, ambiguous or unavailable metadata preserves the stock result.

The bounded metadata command runs once per affected Display object. The
result is weakly cached across resizes and reverse orientations. Ordinary
displays require no dump. Unknown square displays and stale mode sizes do not
trigger an inferred correction; an already established offset survives square
resizes. A failed metadata read remains on the stock path for that Display's
lifetime.

## Verification

```sh
PYTHONPATH=tools python3 -m unittest test_app_window_geometry test_window_size test_managed_display_platform -v
python3 tools/verify_app_window_geometry_runtime.py --serial emulator-5580
```

- Eight host tests pass, including 149 geometry assertions over both presets,
  logical quarter turns, fixed 90°/270° offsets, repeated callbacks, resizes,
  square transitions, unrelated displays and unavailable or malformed metadata.
- The DEX audit restores the geometry call and the pre-existing non-secure
  display flag patch, then compares all **53 extracted service classes** with
  the original definitions. No other service changes are accepted.
- An Android 15 / API 35 emulator passes **8 runtime assertions** using the
  patched ActivityContent DEX, real virtual displays, ImageReaders and real
  display metadata for 1920 × 1080 and 900 × 1200 windows.
- The emulator denied changing `persist.demo.remoterotation`, so the real
  fixed-offset Android fixture was not exercised. The property remained
  unchanged; test virtual displays and the pushed fixture are removed. The
  optional `--fixed-rotation` test supports an emulator already configured with
  that property set to `portrait`. Fixed-offset behavior is covered by the
  host tests, not claimed as a physical-device result.

The next device check is to open Files on the Retroid, switch between both
window orientations, verify text and touch alignment, and export diagnostics
while the affected window is still open. No APK was installed on a physical
device or published as part of this change.
