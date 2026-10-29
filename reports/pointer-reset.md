# Interrupted Home holds — build 2936

The wearer reported pointer recentering with its confirmation vibration while
Home was not being held. The Pixel 8 Pro was running build 2935, connected over
Wireless debugging.

All three stock controller listeners (Spatial Mouse, Vertical Trackpad and
Horizontal Trackpad) schedule `mHomeLongPressed` two seconds after a Home down.
They remove it on Home up, but their `ACTION_CANCEL` branch only restores the
button artwork. The callback remains queued and calls `OnHomeLongPressed` after
the interrupted touch has ended. The fragments also lack lifecycle cleanup for
that callback. Sliding outside Home and additional contacts do not cancel it.

`HomeButtonCompat` cancels the existing timer on canceled/released touches,
additional contacts, and movement outside the button's normal touch slop. New
fragment `onPause` and `onDestroyView` hooks remove pending callbacks too. The
stock listener retains its normal input forwarding, artwork and two-second
intentional hold. The hook only runs in its Home branch. Cancellation records a
`PortHomeButton` log entry when a timer was pending.

`PatchHomeButtonDex.java` adds the hook to each of the three listeners and adds
the two lifecycle methods to each fragment. Its reverse audit compares all
classes against the expected patch and restores the exact input DEX. Duplicate
patching and unrelated changes are rejected. The builder applies the correction
after the existing main-DEX patches and bundles the corresponding sources.

## Verification

- The Pixel executes build 2935's actual controller DEX with real Android
  `MotionEvent`s and `Handler` timers. A receiver records resets instead of
  invoking Unity. All three canceled touches still fire after two seconds;
  the reproduction passes 18 assertions.
- The signed build 2936's actual DEX passes 78 checks across all three modes:
  cancellation, short tap, sliding outside and back, multiple contacts, pause,
  view destruction, deliberate hold and no-repeat behavior. Evidence is under
  `artifacts/pointer-reset/after/`.
- Host checks cover exact restoration and rejection of duplicate/unrelated
  edits. The source-archive and version regressions pass too.
- The complete signed-APK audit passes, including signature/alignment,
  unchanged permissions, unique classes, package relocation, the full reverse
  DEX/native patch chain and bundled corresponding-source checks.
- Comparison with the installed build confirms identical native libraries,
  Unity assets, shaders, controller resources and helper DEX. The helper JAR's
  packaging timestamp changes its digest, which updates its generated payload
  identity. Runtime changes are confined to the controller DEX, client DEX,
  embedded payload identity and manifest version.

The signed candidate is
`artifacts/unbound-launcher-pointer-reset-2936/the-unbound-launcher.apk`, SHA-256
`7795d764f8839ef1ba41a2c84d4e20b5c5f4090f127e84f2b4ef07f84d5159cb`.

That exact APK is installed on the Pixel 8 Pro. The installed file hash matches
the audited candidate and package metadata reports version code 2936. A normal
launcher start resumes the controller, attaches the native glasses Presentation
at 3840 × 1080 / 60 Hz, and commits both controller and glasses frames. No new
Unbound crash appears in the startup crash buffer. Installation/startup evidence
is under `artifacts/pointer-reset/installed/`. The temporary transfer APK has
been removed from the phone.

The test runs as an isolated ADB shell process, using the selected APK's
unmodified DEX and the installed matching controller resources. Android 17's
view setup requires a process-local `ApplicationSharedMemory` instance in that
test process. No device settings or launcher process state are changed by the
fixture. An initial isolated test APK was rejected by Android package
verification; the final regression installs no extra package.

The captured vibration history includes Unbound's 100 ms vibrations, but the
logs do not identify the touch sequence that caused each earlier event. The
reproduced cancellation bug is a confirmed way to cause the reported symptom;
wearer testing is still needed to establish that every observed reset is fixed.

## Reproduce

```sh
python3 -m unittest discover -s tools -p 'test_home_button.py' -v
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-pointer-reset-2936
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-pointer-reset-2936/the-unbound-launcher.apk
python3 tools/verify_home_button.py \
  --apk artifacts/unbound-launcher-pointer-reset-2936/the-unbound-launcher.apk \
  --serial DEVICE_SERIAL --output artifacts/pointer-reset/after
```

For the original build, add `--expect-bug` and use the saved 2935 APK at
`artifacts/pointer-reset/installed-2935.apk`.
