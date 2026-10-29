# Spatial Mouse resets from adaptive brightness — build 2955

After build 2936 fixed interrupted Home holds, the wearer still saw Spatial
Mouse recenter by itself. It happened when a low-battery notification arrived,
about a second after picking up the phone from a table, and at other times
with no obvious cause.

## Cause

NebulaOS uses phone screen brightness as its controller screen-lock signal.
`com.xreal.evapro.ScreenBrightnessMonitor` observes
`Settings.System.screen_brightness` and passes every new value to Unity:

1. `SystemBroadcastReceiver.BrightnessChanged(value)` calls
   `CustomiseControllerExtendHandler.OnClickScreenLock()` for 0 and
   `OnClickScreenUnLock()` for any other value. It does not check whether the
   screen was locked.
2. The unlock callback hides the unlock layout and, when the ray aim mode is
   `DeviceOrientation` (Spatial Mouse), calls `XRInput.RecenterController()`.
   The recenter vibrates and logs `OnControllerRecentered: isAuto = False`.

The two trackpad modes skip the recenter. On the Beam Pro the setting changes
only when the screen locks or unlocks. On a phone with adaptive brightness,
Android rewrites the same setting every time automatic brightness adjusts, so
each adjustment recenters Spatial Mouse:

- Picking up a phone that has been lying on a table changes the ambient
  light. Automatic brightness responds after its debounce, so the pointer
  tracks normally for about a second and then resets.
- Moving between rooms, turning toward a window or changing lighting
  causes the "no visible reason" resets.
- The low-battery case is consistent with a brightness write at that moment,
  but no log covers it directly. The OnePlus battery history shows 20 % at
  11:11:48 and a session end at 11:12:11; its logcat had already rotated.

The other stock recenter callers were checked as well. Startup
(`ControllerLogEventMonitor.OnAdd`, `SyncInputSource`), the deliberate Home
hold, mode switches and the explicit Recenter commands need user action or
run once per session. `Event_AutoTriggerResetLaser` has no dispatcher in the
shipped code. The resume path (`OnHMDPoseReady` after
`OnApplicationPause(true)`) stays inactive while the port keeps Unity running
in the background.

## Evidence from saved logs

Logs from earlier Pixel 8 Pro and OnePlus sessions contain 33 Unity
`onBrightnessChanged` callbacks. Twenty-nine are followed 4–20 ms later by
`OnClickScreenUnLock` → `RecenterController`. The other four did not reach
`OnClickScreenUnLock`, which the receiver skips until the controller view has
finished loading. The values (14–27 on the Pixel, 55–125 on the OnePlus)
come from automatic adjustments, not manual changes.
`artifacts/connection-recovery-host/pixel-2899-live.txt` records four
brightness resets on the Pixel within 20 seconds. A typical sequence, from
`artifacts/pixel-color-017-20261026/active-logcat.txt`:

```
10-26 13:11:09.044 [input] onBrightnessChanged brightness =27
10-26 13:11:09.046 [PhoneControllerInputSignal] BrightnessChanged brightness=27, m_IsPaused=False
10-26 13:11:09.050 CustomiseControllerExtendHandler:<OnClickScreenUnLock>b__101_0()
10-26 13:11:09.050 [XR] [InputManager] RecenterController
10-26 13:11:09.053 [ControllerLogEventMonitor] OnControllerRecentered: isAuto = False
```

The OnePlus used for the latest session reports `screen_brightness_mode=1`
(adaptive brightness on). Its battery history records brightness level changes
during that AR session (11:06:10 and 11:06:32). The investigation captures are
in `artifacts/spatial-mouse-reset/oneplus-20261029/`.

## Change

`PatchBrightnessLockDex.java` replaces the stock observer's single
`Callback.onBrightnessChanged(int)` interface call with
`BrightnessLockCompat.onBrightnessChanged(Object, int)`, using the same two
registers. The filter forwards a value only when it changes the lock state:
a non-zero value after 0 (unlock), or 0 after a non-zero value (lock). Other
non-zero changes are ignored and logged as `PortBrightnessLock`. A new callback
object starts unlocked, as the stock receiver does.

Both Unity listeners only distinguish zero from non-zero:
`PhoneControllerInputSignal` sets `m_IsPaused = brightness == 0`, and the
receiver locks on 0 and unlocks otherwise. Their behavior for real
lock and unlock transitions is unchanged. Callback exceptions are rethrown
with their original type.

The setting read, observer registration, Unity code and every other class
are unchanged. The builder applies the patch last in the `classes2.dex`
chain. The builder and the independent APK audit restore the exact input,
and duplicate or unrelated edits are rejected.

## Verification

- **Negative control.** The released 0.2.2 alpha APK (build 2953) runs its
  actual DEX in an isolated shell process on `emulator-5580`. The check sets
  real `screen_brightness` values 120, 0, 80, 60, 30, 0 and 40, and the stock
  observer runs on its own Handler. The callback receives all seven values,
  so every non-zero value would unlock and recenter. This passes 20
  assertions under `--expect-bug`.
- **Fixed build.** The signed 2955 APK's DEX forwards only 0, 80, 0 and 40,
  the real lock/unlock transitions. 120, 60 and 30 are ignored and logged
  by `PortBrightnessLock`. A new callback starts unlocked: 70 is ignored and
  the next 0 is forwarded. Callback exceptions keep their original type.
  This passes 21 assertions, and both runs restored the emulator's
  brightness settings. ContentService accepts observer registration only
  from app processes, so the check triggers the stock observer's
  `dispatchChange` after each real setting change.
- **Host tests.** All 227 host regression tests pass, including the new
  exact-restore, double-patch and unrelated-edit test.
- **APK audit.** The full signed-APK audit passes. It covers
  signature/alignment, exact reverse restoration of the whole `classes2.dex`
  chain including the new filter, and bundled sources. All 233
  `pairing-sources.zip` entries match the workspace byte for byte.

The APK is `artifacts/unbound-launcher-brightness-lock-2955/the-unbound-launcher.apk`,
SHA-256 `c4bd0430c9a2756d86eb24f21ece0cd432d10a931db7bc64281c224f02bc6540`.
It also contains the unreleased build-2954
[Eye recording change](eye-recording.md). It is not installed on a phone yet.
Wearer testing with glasses and adaptive brightness is still needed to
confirm that no other reset source remains. Evidence is under
`artifacts/spatial-mouse-reset/`.

## Reproduce

```sh
python3 -m unittest discover -s tools -p 'test_brightness_lock.py' -v
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-brightness-lock-2955
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-brightness-lock-2955/the-unbound-launcher.apk
python3 tools/verify_brightness_lock.py \
  --apk artifacts/unbound-launcher-brightness-lock-2955/the-unbound-launcher.apk \
  --serial emulator-5580 --output artifacts/spatial-mouse-reset/after-2955
```

For the negative control, pass `--expect-bug` with the 0.2.2 alpha APK
(build 2953). The device check changes `screen_brightness` and
`screen_brightness_mode`, then restores them. It refuses a physical phone
unless `--allow-physical` is given.
