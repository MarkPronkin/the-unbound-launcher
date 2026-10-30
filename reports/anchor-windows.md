# Preserve anchored windows when opening apps

Opening another app now leaves existing windows at their current positions,
rotations and sizes in anchor mode. The new window keeps its normal launch
position. Smooth-follow layout and manual recentering keep their existing behavior.

## Cause and change

The Unity launcher's `EvaProAppSeparateWidgetContent.FirstRebuildWindowLayout`
calls `EvaProWindowLayoutBuilder2.RebuildLayout(true)` after an app opens. In
3/6DoF mode, that dispatcher runs `AvoidWidgetOverlap` against the window list
and can move windows that were already open.

`tools/anchor_windows.py` wraps only that app-open call. It reads the live
`In3Or6DofMode` property and skips automatic rearrangement in anchored 3/6DoF
mode. The original call still runs for 0DoF smooth follow and unknown layout
modes. The callback still clears its first-layout flag, including when
rearrangement is skipped.

The patch changes a four-byte call and adds a 32-byte wrapper after a disabled
analytics method's retained `RET`. It checks the full input library's SHA-256
and exact original instructions. The build and signed-APK verifier restore it
before auditing all preceding native patches, and include its source in the
APK's source archive.

New-window initialization, manual movement, resizing, recentering, tracking-mode
changes, closing windows and app limits are outside the patch.

## Validation

`python3 -m unittest discover -s tools -p 'test_anchor_windows.py' -v` executes
the actual ARM64 app-open callback, layout-mode getters and layout dispatcher
with Unicorn. Unity transform operations are represented at the overlap and
retrieve boundaries.

The tests reproduce the original overlap pass affecting existing windows, check
stable transforms with zero through nine existing windows, compare smooth-follow
and unknown-mode behavior against the original library, exercise tracking-mode
changes and duplicate callbacks, and verify stack/register preservation, exact
patch restoration, telemetry removal and package-identity composition.

All 14 focused tests pass: five anchor-window tests, four high-performance app
tests and five source-archive tests. The signed candidate at
`artifacts/unbound-launcher-anchor-windows/the-unbound-launcher.apk` passes the
build's signature/alignment checks and the separate `verify_embedded_adb.py`
audit, including exact native restoration and bundled source verification.
Its receipts are `build.json` and `verification.json` alongside the APK.

Physical glasses validation is pending: position several windows manually in
anchor mode, open another app, and confirm the existing windows stay in place.
Also check smooth follow and manual recentering.
