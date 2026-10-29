# Screen-off gamepad focus

The phone-screen confirmation and the full-screen touch guard were focusable
overlay windows. Opening the confirmation moved global input focus away from the
spatial app, and the guard kept it on the phone. Gamepad buttons and joystick
movement then stopped reaching the previously focused game. The guard needed
focus because it handled volume-button recovery in its own key dispatcher.

Both windows now use `FLAG_NOT_FOCUSABLE`. The guard still consumes phone touch
and generic-motion events delivered to the phone, while globally routed
controller input stays with the spatial app. Native phone-pointer suspension and
restoration retain their existing behavior.

Volume recovery now belongs to the independent panel watchdog. It observes
readable volume-capable `/dev/input/event*` nodes through `getevent`, recognizes
only volume-key releases, and restores the panel without taking focus. The
watchdog closes its readers on volume recovery, normal restoration, helper EOF,
lease expiry and command failure. If volume recovery cannot start, it leaves the
panel on. These events continue through Android's normal input handling, so a
wake press can also change the volume.

The helper appends session status to successful screen-power replies. The client
uses that status to remove its guard and restore its pointer after hardware
recovery, without reporting a helper failure. An old helper without this status
causes the client to restore the panel and request a helper restart. Use the
updated helper together with the updated client.

Validation:

- 118 Android instrumentation assertions pass on `emulator-5580`, using the
  production screen-off client and actual controller resources. A separate
  activity on a trusted virtual display retains focus and receives globally
  routed gamepad buttons and joystick motion before and during confirmation,
  while the production guard is present, and after blocked phone touches.
- Host regressions exercise helper/client DEX routing, duplicate Binder-route
  rejection, panel restoration, volume callbacks, watchdog timeout and EOF,
  key-reader cleanup, parser filtering and unavailable recovery. Existing
  glasses-identity and native pointer regressions also pass.
- The real key reader runs as UID 2000 on the emulator and recognizes both
  volume buttons through kernel events. Unrelated keys do not trigger recovery,
  and no `getevent` readers remain afterward.
- The controller verifier now rejects crashed instrumentation runs instead of
  accepting an old report. Its emulator identity fixture explicitly recognizes
  its synthetic glasses display. The physical runtime verifier uses kernel
  events for volume recovery because framework-injected `input keyevent` events
  do not appear in `getevent`.

Evidence is under `artifacts/screen-off-gamepad-focus/`, including
`verification.json`, `runtime-checks.log` and `raw-keys/verification.json`.
No production APK was installed on a physical phone. End-to-end panel-off play
with real glasses and a Bluetooth/USB gamepad still needs hardware validation.

Reproduce the main checks:

```sh
python3 -m unittest discover -s tools -p 'test_phone_screen.py' -v
python3 -m unittest discover -s tools -p 'test_external_display_controls.py' -v
python3 -m unittest discover -s tools -p 'test_phone_pointer.py' -v
python3 tools/verify_controller_screen.py --serial emulator-5580 \
  --output artifacts/screen-off-gamepad-focus
```
