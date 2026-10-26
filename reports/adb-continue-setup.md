# Continue to ADB closes setup after launcher-icon startup

The user reports that build 2892 closes when **Continue to ADB** is pressed and
reopening the app closes it again. Live Pixel 8 Pro inspection confirms build
2892. The retained crash and exit buffers contain no Unbound process crash for
this event. ActivityTaskManager instead records an ADB activity launch returning
result code 3, followed by the permissions activity finishing and its task being
removed. Reopening the icon repeats that handoff.

The source copies the incoming home-screen Intent between setup screens. That
Intent includes ACTION_MAIN, CATEGORY_LAUNCHER and flags **0x10200000**
(NEW_TASK and RESET_TASK_IF_NEEDED). Android applies those home-screen task
semantics to the internal transition instead of creating the requested ADB
screen. The caller then finishes. Launching the installed app with a clean
explicit Intent immediately reaches AdbPairingActivity on the same Pixel, without
updating the package, clearing data or changing permissions.

## Correction in build 2893

SetupNavigation is shared by the disclaimer, permissions, ADB entry and final
return to the launcher. It copies the original device/action/data/extras and URI
grants, while removing home-screen categories, ACTION_MAIN and activity task
flags. Internal activity starts therefore stay ordinary explicit transitions.
USB and custom connection payloads still pass through unchanged.

AdbEntry now reports whether its reflective setup call returned successfully.
The permissions screen remains visible if that call throws instead of finishing
after a failed start. Existing on-phone setup ordering, overlay checks and the
live-helper requirement for Finish setup remain enforced.

## Evidence

- Ten host tests pass, including 12 navigation assertions for the recorded
  launcher flags, payload/grant preservation and failed-start handling.
- The isolated Android 13 emulator passes 10 launcher-origin transition checks,
  32 existing disclaimer/permission/ADB checks and 11 live-helper continuation
  checks: **53 assertions**. Continue opens the ADB screen, Finish setup enters
  the launcher only with a real helper Binder, and optional permissions do not
  repeat. The fixture uses a launcher entry with the production singleTask and
  separate task-affinity behavior.
- Full APK, manifest, DEX restoration/USB coverage, signature/alignment and
  display-default audits pass. The shell helper's two DEX payloads are identical
  to build 2892, retaining its on-Pixel USB worker validation.
- After receiving build 2893 for manual installation, the user confirmed
  **“works now”** on the Pixel. This validates resolution of the reported
  Continue to ADB/setup navigation failure. The evidence is the user's report;
  USB reconnect and fresh-install behavior were not separately reported.

Raw Pixel logs and isolated emulator results remain local under
`artifacts/adb-continue-crash/`. The public launch name stays **0.1.0 alpha**,
with Android version code **2893**.
