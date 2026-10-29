# Controller mode switching — build 2929

On the Pixel 8 Pro / Android 17, switching between Spatial Mouse and either
trackpad showed **“The Unbound Launcher keeps stopping”**. Build 2928's crash
buffer repeatedly records `dev.markpronkin.unbound:service` dying in
`com.xreal.evapro.JobService.onStartCommand`, handling
`evapro.job.ACTION_MOUSE_CTRL`. Its `Settings.Global.putInt` call throws
`SecurityException` because the ordinary app lacks `WRITE_SECURE_SETTINGS`.
The same failure reproduces with the actual build 2928 service DEX in an
isolated Android 13 emulator. Evidence is under
`artifacts/controller-mode-switch/before/` and `emulator-before/`.

The write stores `xreal_hide_pointicon`, a Beam-specific system-pointer flag.
Other Beam flags already use `SettingsCompat` and the certificate-protected
private flag provider; the controller service had escaped that earlier rewrite.
Build 2929 changes only this call to `SettingsCompat.putGlobalInt` and adds the
key to the provider's existing allowlist. Both pointer values are saved in app
state without a privileged settings write or a running shell helper. The
companion probe provider recognizes the same key.

`PatchControllerModeDex.java` preserves the original service method's code
addresses, registers, branches, return value and exception/debug metadata. Its
restore mode checks every class against the pinned input before reproducing the
original DEX byte for byte. Other service actions, including the existing unplug
audio correction, retain their prior behavior. The embedded APK builder applies
the correction to `classes2.dex`; the full APK audit reverses it in the complete
patch chain and checks that its corresponding sources are bundled.

Validation:

- Two host regressions pass: exact restoration, rejection of duplicate and
  unrelated edits, and composition with the disconnect-audio correction.
- The existing disconnect regression and five source-archive regressions pass.
- `verify_controller_modes.py` passes 149 Android checks with the signed build.
  It executes the selected APK's actual service DEX
  with the production settings adapter and a separate-process production flag
  provider. It checks 36 pointer requests, including repeated trackpad values,
  a stable service PID, unchanged global state, existing flags and rejection of
  non-Beam keys. It explicitly verifies that the stock protected write is denied.
- Comparison with installed build 2928 confirms that the native libraries,
  Unity assets, controller resources and client DEX are identical. Helper DEX
  payloads are also identical; their JAR packaging timestamps change. Runtime
  edits are confined to the service DEX, the provider/About DEX and the manifest
  version. The bundled source ZIP includes the correction.

The signed APK is
`artifacts/unbound-launcher-controller-mode-2929/the-unbound-launcher.apk`,
SHA-256 `b30f96852c0958aa98b6afb767c217288ff22dc2d6488755a911927a64e1d1ac`.
Build, audit, emulator and phone evidence is retained under
`artifacts/controller-mode-switch/`.

The complete APK audit passes, including signature/alignment, unchanged
permissions, class uniqueness, package relocation, the full DEX restoration
chain and corresponding-source checks. The exact audited APK is installed on
the Pixel 8 Pro / Android 17 with XREAL One connected; the installed file's
SHA-256 matches. Twelve UI transitions covered all three modes, including
Spatial Mouse to each trackpad, the return transitions and portrait/landscape
changes. The renderer PID remained 1730 and the service PID remained 1605
throughout, with no new crash-buffer entries. Screenshots confirm the selected
modes, and the phone is left in its original Vertical Trackpad mode. Results:
`artifacts/controller-mode-switch/after/verification.json`. Pointer movement
and glasses image quality were not assessed in this crash regression.

Reproduce the regression checks with:

```sh
python3 -m unittest discover -s tools -p 'test_controller_mode.py' -v
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-controller-mode-2929
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-controller-mode-2929/the-unbound-launcher.apk
python3 tools/verify_controller_modes.py \
  --apk artifacts/unbound-launcher-controller-mode-2929/the-unbound-launcher.apk \
  --serial emulator-5580
```
