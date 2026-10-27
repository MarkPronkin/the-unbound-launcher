# Experimental 3D SBS for glasses that default to 2D

Build 2905 adds **Settings → Experimental → Force 3D on unsupported glasses**.
It is off by default. The saved switch applies after reconnecting the glasses;
turning it off and reconnecting restores the original model defaults.

The original model selectors execute completely before the optional filters.
Only a mono result changes, to native SBS at 60 Hz. Existing stereo results,
including their refresh rate, and unknown/error results remain unchanged.

The SDK's two mode namespaces must be distinguished:

| Selector | Original mono values | Experimental SBS value |
| --- | --- | --- |
| Legacy SDK `EGlassMode` | 1, 5, 10, 11 (60/72/90/120 Hz) | 3 |
| Ethernet SDK EDID | 1–4 | 5 |
| Unity home `EGlassMode` | 1, 5, 10, 11 | 3 |

`ExperimentalStereo.java` stores a single atomic opt-in byte in the app's private
files. Missing, disabled or malformed values keep the default. Its DEX hook
filters the shared return of `XREALManager.getFinalDisplayConfig()` so both the
normal SDK startup and connection recovery see the same target. Recovery now
recognizes legacy stereo modes as well as Ethernet EDID stereo modes, and waits
for physical 3840 × 1080 output before completing a recovered stereo connection.

Unity separately selects its home rendering mode. The native filter replaces
only the final RET of `GlassUtil.GetHomeGlassModeFromSettings` and uses 232 bytes
after an already-disabled analytics RET. It runs after the original selector,
reads the same opt-in byte, and changes only mono results. It preserves the
stack, return address and every register it uses except the result. Existing
renderer/provider logic remains intact. Both native and DEX patches are pinned,
reject repeated or unrelated changes, and restore their exact input baselines.

The native reader uses the package's primary-user private directory. Saving the
option in a different Android profile fails with an explanation instead of
allowing the Java and native choices to diverge. No global Android display
setting or original model configuration is rewritten.

Validation on 2026-10-27:

- Host tests pass for both mode namespaces, opt-in persistence, missing and
  malformed flags, unknown modes, profile restrictions, exact patch scope,
  assembly bytes, and patch restoration/rejection.
- Connection recovery passes 42 assertions, including waiting for physical SBS
  output with a legacy stereo target and preserving the original mono default.
- The API 33 ARM64 emulator passes 42 Android/UI assertions using the production
  setting: toggle persistence through recreation, mode filtering, connection
  refresh, disabling, and unchanged existing actions.
- The exact native filter, with only its private path replaced by a shell-owned
  fixture, passes 102 ARM64 process runs across modes −1 through 15 and six file
  states. The harness checks register and stack preservation on each run.
- The signed build 2905 passes `verify_embedded_adb.py`, including full DEX and
  native restoration, helper wiring, source archive equality, class uniqueness,
  manifest access and the existing shader/resource audits. The APK and audit
  report are under `artifacts/unbound-launcher-experimental-stereo/`.

Commands:

```sh
python3 -m unittest discover -s tools -p 'test_experimental_stereo.py' -v
python3 -m unittest discover -s tools -p 'test_connection_startup.py' -v
python3 tools/build_settings_ui.py --preview-only --output artifacts/experimental-stereo
python3 tools/verify_experimental_stereo.py --output artifacts/experimental-stereo
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-experimental-stereo
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-experimental-stereo/the-unbound-launcher.apk
```

Physical glasses validation was pending for the initial build. Enabling the setting does not bypass the
SDK's model registration or rejection of an unsupported hardware switch, and
does not add a driver for non-XREAL glasses.

## Samsung feedback and optional mono 60 Hz — test candidate 2913

The Air 2 wearer on Samsung SM-S928W / Android 17 confirms working stereo and
fullscreen output. Their `unbound-debug-20261027-034043-e09ea1f9.zip` independently
records 3840 × 1080 / 60 Hz physical output, `Mode_3D_60Hz` at the Unity home
configuration and four successful stereo renderer starts. The earlier selector
log still says `Mode_2D_90Hz` because that log runs before the existing tail filter.

The other Samsung report, SM-F956U1 / Android 16 / Air 2 Pro, loses its 1080p
mode after the default 2D 90 Hz switch and exposes only 640 × 480 and an Android
generated fallback. The native renderer rejects those modes. To test a lower
refresh rate independently of SBS, build 2913 adds **Use 60 Hz in 2D** under
Experimental. It is off by default. The two overrides are mutually exclusive,
and changing either requires a reconnect. Existing saved SBS preferences remain
enabled after updating.

Both Java mode namespaces and Unity's legacy selector use mode 1 for mono 60 Hz.
The existing one-byte preference now represents default (0), SBS 60 Hz (1), or
mono 60 Hz (2). Unknown values and incorrect lengths retain original defaults.
The native tail filter expands from 232 to 248 bytes inside the same disabled
analytics method, with exact baseline restoration and assembly matching checked.
Existing stereo modes and their refresh rates remain unchanged under either
override. The profile restriction applies to both choices so Java and Unity
cannot read different preference paths.

The production UI passes 83 emulator assertions, including mutual exclusion,
recreation, connection refresh and restoring defaults. The actual ARM64 filter
passes 136 runs covering modes −1 through 15 and eight preference states, checking
register and stack preservation. Java selection/persistence, native and DEX
round trips pass on the host. A physical Air 2 Pro test at mono 60 Hz is pending;
this option is a diagnostic candidate, not a confirmed black-screen fix.
