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

Physical glasses validation is pending. Enabling the setting does not bypass the
SDK's model registration or rejection of an unsupported hardware switch, and
does not add a driver for non-XREAL glasses.
