# Sony Xperia 1 II / xbx a01+ startup recovery

## Renderer crash on Android 12 — build 2914

The follow-up archive `unbound-debug-20261027-052919-1501b3a4.zip` identifies
build 2912 on the same Sony SO-51A / Android 12 combination. Its new session
confirms that the type 11 fix runs: at 13:28:17 and 13:29:01 in device time,
`UnboundStartup` records active glasses output, the startup state becomes 1,
and `NRServiceControl` reports SDK startup success.

The next failure is a native renderer abort in `dev.markpronkin.unbound:space`:
`jni::NameResolutionException: getSupportedHdrTypes`. It repeats after every
renderer restart. The trace leads through `libnr_api.so`'s mode-binding
constructor at 0x1e8f2bc, called while reading the display mode for rendering.
This is distinct from the earlier USB startup-state rejection.

The constructor eagerly resolves five `android.view.Display$Mode` methods.
`getModeId`, `getPhysicalWidth`, `getPhysicalHeight` and `getRefreshRate` exist
on older Android versions. `getSupportedHdrTypes` was
[added in API 34](https://developer.android.com/reference/android/view/Display.Mode#getSupportedHdrTypes()),
and is absent from both the Android 11 and Android 13 SDK stubs. The Sony's
Android 12 runtime therefore cannot resolve it. The binding's only native
consumer reads width, height and refresh rate; its HDR method-ID slot is unused.

Build 2914 replaces one ARM64 instruction at 0x1e8f3a0: the mandatory HDR
`GetMethodID` call becomes `MOV X0, XZR`, and the existing store initializes the
unused slot to null. The other four lookups, class initialization and cleanup,
mode-reading code, mode selection and rendering remain intact. No HDR result
is invented, and no screen mode or permission changes are introduced by this
patch. A complete library SHA-256 check pins this exact SDK; restoration
rejects all unrelated changes.

The host regression executes the real ARM64 constructor, reproduces the
original missing-method failure, and verifies required IDs, stack and saved
registers with the patch. Its scope check requires the complete native library
to differ by only the single instruction. The cache-reference audit pins all
17 references and confirms there is no consumer of the HDR slot.

An isolated Android 13 emulator, which also lacks this API, reproduced the
same native abort with the original complete `libnr_api.so`. With the patched
library, the actual constructor resolved all four required method IDs and
returned successfully through real Android JNI. The test changes an existing
JNI entry in a fixture copy solely to invoke this constructor; its probe code
is excluded from the launcher. No hardware, JNI calls or framework classes are
mocked in this Android regression.

All three native host regressions, the source-archive checks and version checks
pass. The signed APK contains exactly the native library used by the Android
regression, without the fixture probe. The independent full APK audit passes,
including native restoration, the existing DEX restoration chain, package
identity, permissions and bundled sources. Its certificate matches build 2912,
so installation as an update preserves the existing app data and permissions.

The current test candidate is
`artifacts/unbound-launcher-xperia-android12-2914/the-unbound-launcher.apk`.
Install it as an update and reconnect the glasses. Physical Sony / xbx a01+
validation is still pending. If another failure occurs, collect a fresh debug
archive while the glasses remain connected so the next startup stage is visible.

## Initial SDK failure — build 2911

The supplied `unbound-debug-20261027-040422-1a696c8a.zip` records a Sony SO-51A
running Android 12 (API 31), with USB product `xbx a01+` (VID 13080, PID 1090).
The SDK identifies these glasses as type 11, without an Ethernet connection.
The screenshot shows the connection-paused recovery screen.

At 12:04:01 in the report's device time, USB permission is granted and the native
controller opens successfully. `nativeGlassesInit` returns true. The next query,
`nativeGetGlassesStartupState`, receives the firmware response
`unsupported` and native API error 103; its Java result is -1. The SDK turns
that result into startup failure. The same sequence repeats on subsequent
attempts. The report also records active external output at 1920 × 1080,
120 Hz. This establishes a failure in the glasses status-query path; it does
not establish a Sony display-driver problem or an Ethernet/ping timeout.

`GlassesStartupCompat` supplies an alternative readiness check only when the
original query returns -1 with native error 103, the current USB model is type
11 and the SDK is using its non-Ethernet path. An active, valid display must
also pass the original SDK's XREAL identity predicate and have a physical mode
of 1920 × 1080 or 3840 × 1080. The hook runs inside the SDK's existing branch
after native initialization has succeeded. It returns ready so the original
SDK success configuration and model/display selection can proceed.

All other status results, native errors and models retain their original
behavior. A disconnected, disabled or unrelated screen cannot authorize this
fallback. Native initialization failures and the original initialization retry
remain intact. No firmware writes, mode overrides or new permissions are added.
The original SDK error-reporting call still follows the query hook, and the
fallback writes its native error and physical readiness evidence to the app log.

The DEX patch changes one invocation in the original SDK callback without
changing its registers, instruction addresses, branches or exception ranges.
Build and verification scripts round-trip it to the exact preceding DEX and
reject double patching and unrelated changes. The APK includes the adapter and
patch sources in its existing corresponding-source archive.

Host validation covers 37 assertions for the reported case, supported status
results, other native failures, Ethernet models, other H-series models, absent
or disabled displays, wrong modes, missing services and reflection failures.
The Android fixture transplants the actual callback implementation from the
built APK into a test-only SDK shell, retaining its original native-init and
status branches. Native calls and the EDID predicate use controlled fixtures;
the production compatibility code checks real Android display objects. This
checks ART register verification and branch behavior, not the physical glasses
or their firmware.

The isolated Android 13 emulator passed all 14 actual-SDK assertions, including
the unsupported-query success path, native-init failure/retry, failed ping,
other native errors and models, Ethernet DP wait and disconnected output.
The SDK DEX in the signed APK matches the tested DEX byte for byte. The existing
connection-recovery suite also passes its 55 assertions. Version-update and
corresponding-source archive checks pass.

The signed build passes `tools/verify_embedded_adb.py`, including the complete
DEX restoration chain, package identity, component access, source archive and
native/resource scope checks. Its signing certificate matches the existing
0.1.1 alpha APK, so an in-place update is supported. The candidate is saved at
`artifacts/unbound-launcher-xperia-type11-2911/the-unbound-launcher.apk`.

Build 2911 was the first test candidate; the follow-up above supersedes it.
Its original test instructions were to reconnect the glasses with the
experimental 3D setting unchanged and collect a fresh debug export on failure.
The follow-up archive confirms that this scoped `UnboundStartup` fallback ran
and exposed the subsequent renderer crash described above.
