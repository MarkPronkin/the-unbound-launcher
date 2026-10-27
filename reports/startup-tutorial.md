# Disable the glasses launcher's first-use tutorial — build 2901

The glasses launcher now follows its normal startup path on a fresh install,
without automatically opening the basic tutorial. The Tutorial app remains
available for manual use. Phone setup, the disclaimer and permission review
are unchanged.

The original My Glasses 2.2.2 Unity library uses
`NREAL.EPTutorial.EPTHelper.NeedOpenBasicTutorial` at `0x29A0D20` to check
device-specific saved completion data. The loading scene and both synchronous
and asynchronous tutorial startup paths consult that predicate. It also informs
tutorial completion and release-note timing.

`tools/startup_tutorial.py` replaces the predicate's first eight bytes with
`mov w0, wzr; ret`. This selects the existing returning-user behavior for every
glasses model, including fresh app data. It does not write completion flags.
The synchronous startup path still enables controller input, and the tutorial
notification handler retains its direct manual `EnterLobby` and `StartBasic`
calls. The tutorial assets and app-menu entry remain intact.

The patch runs after native app-color selection in the single-APK builder.
It requires the exact input library SHA-256 and original instructions. The APK
verifier reverses this patch and the color patch to reproduce the pinned
baseline byte for byte; it also verifies the packaged patch source. Repeated
patching, partial restoration and unrelated library changes are rejected.

Local tests cover the exact eight-byte scope, preservation of prior native
changes and manual tutorial methods, the ARM64 return value and preserved
registers, and the actual startup callers taking their no-tutorial branches.
These instruction checks use Capstone and a bounded interpreter because this
host cannot run Unicorn's JIT. Source-archive, version and native-color checks
also pass: 12 test methods in total.

APK signature/alignment checks and the complete production APK audit passed
on 2026-10-27. The signed candidate, build report, verification report and
SHA-256 file are under `artifacts/unbound-launcher-no-startup-tutorial-2901/`.
Physical validation of first startup and manual tutorial playback in the
glasses is pending.
