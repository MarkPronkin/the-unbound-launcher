# Samsung SM-F956U1: Bluetooth mouse reconnect during AR Space

In the October 28 follow-up, the wearer reports that mouse control works
when the mouse is connected before AR Space starts, but disconnecting and
reconnecting it during the session leaves a cursor on the phone's Vertical
Trackpad and apparently inverted movement. Both exports are from **Unbound
0.1.2 alpha, build 2924, Samsung SM-F956U1, Android 16 / API 36**, with Air 2 Pro
glasses.

The logs confirm repeated pointer-service crashes during device changes and
Mouse/Touchpad switching. They identify the same protected-setting defect
already corrected by the local controller-mode and input-helper patches.
They do **not** establish that the crashes fully explain the reported cursor
placement or inversion. Test the existing combined correction before changing
mouse routing or axis signs.

## Captures and overlap

| Export | Retained log interval (October 28, device local time) | Pointer-service crashes | Mouse/Touchpad transitions |
| --- | --- | --- | --- |
| `unbound-debug-20261028-225059-dcc8bc1b.zip` | 18:45:17.492–18:50:59.298 | 41 | 22 |
| `unbound-debug-20261028-225549-82eb30b0.zip` | 18:47:06.586–18:55:49.472 | 43 | 23 |

The second export retains the first export's failure sequence. Its 43 crashes
include those same 41 crashes plus two during the fresh session; these are not
84 independent crashes. The filename times are UTC; report creation times
and log events use EDT.

Raw exports, extracted files, line-numbered input timelines, archive hashes
and `analysis.json` are retained locally under
`artifacts/samsung-fold6-mouse-reconnect-2924/`. Private network and display
identifiers are omitted from this report.

## Failure during the existing session

The first export shows these events in renderer process **27011**:

1. **18:48:46.949:** Unity's mouse callback reports disconnected and calls
   `MouseEventCenter.ShowPointerIcon`.
2. **18:48:46.979:** `dev.markpronkin.unbound:service` crashes while processing
   `evapro.job.ACTION_MOUSE_CTRL`. The cause is `SecurityException` requiring
   `WRITE_SECURE_SETTINGS`, at `JobService.onStartCommand`, line 44
   (`225059/app.log:39148`).
3. **18:48:47.146 / 18:48:47.500:** Android input-device callbacks remove device
   37 and add device 38. Further remove/add sequences introduce devices 39,
   40 and 41. Bluetooth-state callbacks and Android device callbacks are
   separate observations; the service failure begins before the first logged
   Android device removal.
4. **18:48:47.194 / 18:48:47.275:** the active ray switches Mouse → Touchpad →
   Mouse. There are **22** such switches through 18:50:18.431 and **41** fatal
   service crashes through 18:50:19.539. Every captured fatal stack names the
   same mouse-control action and protected settings write. The renderer PID
   remains 27011 across this sequence; these are service-process crashes.
5. **18:50:30.969:** Unity also logs a direct `InputHelper.hidePointerIcon`
   protected-write failure (`225059/app.log:66075`). This occurs alongside
   glasses USB disconnection. Further helper failures occur during teardown
   and activity pause; they are a second affected code path, not proof of
   another Bluetooth reconnect.

This is stronger evidence than the [October 27 Samsung export](samsung-bluetooth-stability.md),
which contained no failure history. The optional Bluetooth enumeration guard
documented there does not fix these settings writes. This follow-up records
successful Bluetooth enumeration and actual input-device callbacks.

## New session with the mouse already connected

The later export records renderer process **20844** detecting an external
mouse at **18:54:46.883**, switching Touchpad → Mouse at **18:54:47.642**, and
setting `mouse active =True` at **18:54:48.046**. The logged input-device name
is **iClever BK08 KB Keyboard**, whose sources include mouse input. This
identifies a pointer-capable device; the export does not identify which
physical mouse the wearer disconnected.

There is no subsequent Mouse → Touchpad switch before pause at 18:55:44.950,
and no input-device add/remove callback in this new-session interval. That is
consistent with the wearer's report that starting AR with the mouse already
connected works. It does not exercise reconnect in the new session.

The workaround is not an error-free state: the same pointer service crashes
at **18:54:47.408** and **18:54:48.133**, and the direct helper write fails on
pause at **18:55:44.977**. A working mouse after restart does not rule out the
protected-setting defect.

## Existing corrections and remaining uncertainty

The relevant fixes already existed in this workspace before this follow-up:

- [Controller-mode correction](controller-mode-switch.md):
  `PatchControllerModeDex` routes the `JobService` pointer write through
  `SettingsCompat` and the private flag provider. This covers all fatal stacks
  in these captures.
- [Input-helper correction](oppo-input-v012.md#confirmed-pointer-flag-defect):
  `PatchInputCompatDex` routes `InputHelper.hidePointerIcon` and
  `isPointerIconHidden` through that same private state. Fixing only the
  service leaves the direct helper exception in place. Build **2945** contains
  both corrections.

These patches remove protected-setting exceptions and retain the stock
device listeners, mouse callbacks and coordinate handling. They do not add
Bluetooth reconnection logic or prove correct cursor routing on Samsung.

Neither capture contains raw mouse/touch source, tool-type and axis samples
around the reported inversion. Mouse/Touchpad switches alone do not prove
that Android delivered mouse input as a finger. Ordinary finger interaction
can also switch the active ray. Neither capture records keyboard-show/hide
callbacks, so the separate false-IME issue from the OPPO report is not
established here.

## Validation and follow-up

Five host regressions pass across `test_input_compat.py`,
`test_controller_mode.py` and `test_bluetooth_compat.py`, including exact DEX
restoration, rejection of unintended changes, composition with the existing
disconnect correction, the native keyboard-state regression and 16 Bluetooth
enumeration assertions.

Fresh isolated-emulator checks for this investigation pass:

- The released build **2924** reproduces the direct helper permission failure
  (6 expected-bug assertions in `released-input-runtime/verification.json`).
- The existing build **2945** candidate passes **92** input/IME/helper
  assertions and **149** controller-service assertions, including **36**
  pointer-state transitions with a stable service process. The protected
  permission is denied, and global pointer state is unchanged. Results are in
  `input-runtime/verification.json` and `controller-runtime/verification.json`.

The tested candidate is
`artifacts/unbound-launcher-oppo-input-2945/the-unbound-launcher.apk`, SHA-256
`b14fdfa16741bd4c6442757e42dfcad9ee5387ff65c2a7c7ab3d31c7038593f4`.
Fixtures run that APK's real DEX with production adapters and a separate-process
private provider on an isolated Android emulator. They do not use Bluetooth
hardware, exercise the full Unity mouse-reconnection flow or assess the glasses
cursor. Both temporary fixture packages were removed afterward.

The remaining wearer check is the combined build on the reported Samsung:
start AR with the mouse connected, disconnect/reconnect it repeatedly without
restarting AR, and check movement, clicks and scrolling in Vertical Trackpad.
Repeat once starting AR before connecting the mouse. Capture debug logs from
before AR startup through the failure or successful reconnects. If the cursor
still moves onto the phone or feels inverted after the protected-write
failures are gone, investigate event source/tool type, relative axes and
focus/capture state at that transition. No new routing change or release is
made for this report.
