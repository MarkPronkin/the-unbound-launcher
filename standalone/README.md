# Unbound Standalone experiment

**Status: shelved prototype, 2026-10-26.** The owner elected to keep the existing
ADB-assisted launcher for now. The temporary prototype app has been removed from
the OnePlus, test capture has stopped, and the original launcher has been reopened.
Its shell helper was confirmed running as PID 19465. Main launcher sources, APK
and app data were not replaced by this experiment.

This is the first executable implementation of an on-phone runtime. It installs
as **Unbound Standalone**, package `dev.beampro.standalone`, alongside the existing
launcher. It uses its own signing key and does not call the ADB helper, companion
provider, shell commands, hidden APIs, or privileged windowing service.

The [APK](../artifacts/standalone/unbound-standalone.apk) is a development
experiment with `debuggable=true`, minimum Android 11/API 30, and target API 34.
It does not contain or launch the XREAL Unity renderer. No build of the original
launcher has been replaced or promoted as a standalone release.

## On-device setup and tests

1. Install the APK and open Unbound Standalone.
2. Tap **Enable pointer access**. In Android Accessibility settings, find
   Unbound Standalone under downloaded apps and enable it through Android's
   consent screen. The service only acts on the selected apps during a test.
3. Select AIDA64 and Chrome, then tap **Test pointer permission**. The app sends
   a real Accessibility gesture to its own target button and checks receipt.
4. Tap **Test two independent app windows**. It tests both apps concurrently
   with virtual-display flags 0, 8, 9, 10 and 11. It records launch policy,
   actual exceptions, frame data, app-window identity, and input results. A
   denied launch never falls back to opening the app on display 0.
5. For the alternative backend, tap **Start screen sharing**, choose **Entire
   screen** and approve Android's capture prompt. Then tap **Test two apps in
   split screen**. The app uses `FLAG_ACTIVITY_LAUNCH_ADJACENT`, handles the
   source Activity being recreated by OxygenOS Canvas, and opens the second app
   from the remaining source window. It verifies two disjoint app regions,
   captures both, opens AIDA64's CPU page with a gesture, and focuses Chrome's
   address bar with another gesture. Control verification currently recognizes
   these two apps; other picker entries do not have automated control checks.
6. **Copy test report** copies the JSON evidence. Screen sharing stops at the
   end of the split test or when the Stop action is used. An unused session also
   stops after two minutes. Denial or cancellation does not bypass system consent.

The app stores the JSON report and test PNGs in its own internal data directory.
They are diagnostic captures of the selected test apps. Capture is visibly
indicated by Android and the foreground-service notification. A new session
requires new consent. Reboot does not require a helper restart for this public-API
experiment, but reboot/reconnect behavior has not yet been tested physically.

## Physical OnePlus 15 result

On Android 16/API 36, the process runs as ordinary UID 10642. Runtime checks
confirm input-injection, trusted-display, activity-embedding, secure-settings,
and privileged video-capture permissions are all denied. The APK requests only
the two foreground-service permissions and notifications; Accessibility and
screen sharing use their normal on-device consent screens.

- App-owned presentation rendering and actual captured pixels pass.
- An Accessibility gesture produces an actual click in the experiment's view.
- Both real apps are denied placement on each of the five virtual-display
  configurations: ten launch denials, with no app frames received.
- The split-screen capture backend runs AIDA64 and Chrome together. Both regions
  produce changing frames and both apps visibly respond to Accessibility taps.
- Capture and the experiment's virtual displays are released after the test.

The completed runtime evidence is from the earlier successful prototype build.
The final APK adds lifecycle cleanup guards; its last repeat test was stopped
at the owner's request. Do not describe that final APK as fully runtime-validated.
The interrupted run is retained separately, together with the successful result.

See the [runtime evidence](../artifacts/standalone/oneplus-runtime-results.json),
[verification](../artifacts/standalone/verification.json), and
[captured two-app screen](../artifacts/standalone/oneplus-two-apps.png).

This proves a **limited two-app split-screen capture backend**, not independent
offscreen app windows. The two apps occupy the phone display. The prototype does
not supply a phone trackpad overlay, stereo glasses output, anchoring, native
Unity texture integration, or ten independent apps. Protected content, rotation,
keyboard interaction beyond the address-bar check, dragging, process-death
recovery, reboot, glasses reconnect, and Pixel behavior need further work.

## Build and verify evidence

```sh
python3 tools/build_standalone.py
python3 tools/verify_standalone_evidence.py
```

Run these from the repository root. The second command reads recorded evidence
and checks APK permissions, signing identity, both successful and failed runtime
results, actual input responses, and the separation between split capture and
independent virtual windows. It never installs software or grants access.

ADB was used by the developer to install this APK, operate the same on-device
permission UI, and collect evidence. No `pm grant`, secure-settings permission
grant, shell-started server, or helper Binder is used by this experiment. Its
different signing certificate also prevents it from consuming the existing
companion's helper endpoint.

## Embedded ADB alternative

The owner has also asked whether the app can bootstrap the existing privileged
backend by connecting to the phone's own ADB daemon. That is technically viable
on supported Android 11+ devices with wireless debugging and pairing. It would
keep full app-window capability while removing the PC/terminal startup workflow.
This prototype does not yet implement that client or bundle the shell backend.

An eventual implementation must integrate the Binder provider and helper payload
into the launcher APK, keep the client identity private to the installation,
use Android's pairing approval, start only the bundled backend, and report
connection/startup failures. It must not pretend to be a normal runtime permission
or promise permanent activation after reboot. See
[LibADB Android](https://github.com/MuntashirAkon/libadb-android),
[Shizuku on-device startup](https://shizuku.rikka.app/guide/setup/), and
[QGO's pairing changelog](https://anagan79.itch.io/quest-games-optimizer/devlog/360224/changelog).
