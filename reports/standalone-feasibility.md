# Standalone installation requirement and feasibility audit

**Current status: shelved prototype.** On 2026-10-26 the owner asked to retain
the ADB-assisted version and record this investigation for later. The prototype
is isolated from the main build; its temporary phone installation was removed.
See [prototype notes](../standalone/README.md) for measured results and limitations.

Recorded 2026-10-26 following the project owner's clarification: the end product
must work by installing one APK and granting permissions on the phone. ADB is a
development tool, not an end-user command-line prerequisite. The owner has since
asked to evaluate an embedded on-device ADB bootstrap if needed. The strict
permissions-only experience remains the preferred path; the embedded-ADB option
must disclose its Developer options, pairing, and reboot requirements.

The first [standalone runtime experiment](../standalone/README.md) has now been
implemented and hardware-tested. It passes capture and Accessibility control of
two real apps together in phone split screen on the OnePlus 15; independent
app-owned virtual displays reject both apps. That limited result is not full AR
Space parity. The older requirements/evidence below explain the investigation.

## Required experience

1. Install one APK on a phone with external-display output.
2. Complete an on-device setup flow using permissions and access the user can
   actually grant. Include any Android-required capture-session consent.
3. Connect supported glasses and enter AR Space with working floating Android
   app windows, phone-pointer input, and the intended tracking behavior.
4. Recover after disconnect/reconnect, app termination, and phone reboot without
   a computer, ADB, developer options, root, a separate helper app, or a shell
   process started by the user. Required session consent may appear again.

Broad compatibility is the product goal. DisplayPort support alone does not
establish that every phone/OS/glasses combination can run the current native
renderer or host third-party app windows. Unsupported combinations need a clear
on-device explanation, rather than a blank display or a debugging prerequisite.

## Current dependencies

| Capability | Current implementation | Standalone work needed |
| --- | --- | --- |
| App-window service | `port-client/src/ai/nreal/nebula/service/LaunchService.java` returns the companion's shell-owned Binder | An implementation running under the APK's ordinary app UID |
| Service startup | `bridge/src/dev/beampro/bridge/LaunchServer.java` explicitly requires UID 2000; `tools/helper.py` starts it with ADB | On-device lifecycle with no shell identity assumption |
| Window creation and app launch | Original `LaunchManager`, adapted to trusted non-secure virtual displays; shell helper invokes `am start --display` | Prove an allowed display/launch path for actual third-party apps |
| Phone-pointer input | Original privileged input injection through the helper | Evaluate a user-enabled Accessibility service and display-targeted gestures |
| Beam-only flags | `SettingsCompat` reads/writes through `dev.beampro.portprobe.bridge` | App-local state shared across the main and `:space` processes within one APK |
| Recent apps, previews, radio metadata | Helper Binder extensions and helper-owned files | App-owned storage and public APIs; request any relevant access on-device |
| Actual phone-panel power | Helper's `PhoneScreenPower` implementation | Assess separately; a black view is not evidence that panel power was switched off |
| Physical glasses output | Original XREAL display/Unity lifecycle | Validate fullscreen stereo output and Android desktop/mirror transitions across hosts |

The permission checklist cannot grant the privileges used by the current helper.
Android 16 defines `INJECT_EVENTS` as a signature permission and
`ADD_TRUSTED_DISPLAY` as signature/role protected in the
[platform manifest](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/res/AndroidManifest.xml).
Bundling the existing helper in the APK changes neither its UID nor those grants.

## Existing measured evidence

The saved OnePlus 15 ordinary-UID runs
([initial](oneplus-15-probe.json), [manual repeat](oneplus-15-probe-manual.json))
denied all seven privileged permissions checked. Trusted virtual-display creation
failed with an `ADD_TRUSTED_DISPLAY` error. Flags 9 created displays, but the own
fixture activities were denied placement and the presentation failed to attach;
neither run saw fixture pixels. These observations establish failures of those
specific test configurations on that OnePlus build, not a proof that every
unprivileged display configuration is impossible.

The [shell run](oneplus-shell-probe.json) had input-injection, trusted-display,
and video-capture permissions. The successful spatial-app tests therefore do
not establish a standalone app implementation.

The Pixel 8 Pro report is currently user-observed only: Android presents a
Desktop/Mirror chooser on connection and again when entering AR Space; Mirror
shows no picture, and Desktop shows stretched output with a tutorial. The
tutorial's identity, exact display/window bounds, helper state, and cause have
not been established by logs. No Pixel runtime capture was performed for this
audit. This output issue needs testing independently of the app-window service.

## Candidate paths and their limits

- **MediaProjection** provides user-consented capture of a display or app window.
  It is a candidate for a capture backend, not an automatic replacement for
  trusted app displays and task management. Android requires fresh consent for
  each session and imposes single-use token rules on Android 14 and higher.
  Another projection can end the current session, so independent per-window
  sessions cannot simply be assumed to coexist. See
  [Media projection](https://developer.android.com/media/grow/media-projection).
- **Accessibility** offers display-targeted gesture dispatch through
  [`GestureDescription.Builder.setDisplayId`](https://developer.android.com/reference/android/accessibilityservice/GestureDescription.Builder#setDisplayId(int))
  on API 30 and higher. Evaluate taps, scrolling, continued drags, cancellation,
  keyboard entry, and display removal with the user's enabled service. This does
  not itself grant trusted-display creation or app placement.
- **App-owned untrusted virtual displays** need a broader, controlled test of
  flags, ownership, app-launch policy, and rendered content. First prove an own
  fixture, then an independently signed fixture and real installed apps; do not
  infer support from successful display allocation alone. Check
  [`isActivityStartAllowedOnDisplay`](https://source.android.com/docs/core/display/multi_display/activity-launch)
  and record both launch failures and actual received pixels.
- **VirtualDeviceManager/companion streaming** supplies relevant display and
  input mechanisms, but the documented app-streaming role is restricted to
  preinstalled privileged apps. It is not a general sideloaded-APK solution.
  Newer virtual-device facilities also need their actual role, permission, and
  device requirements checked before use. See
  [Companion app streaming](https://source.android.com/docs/core/permissions/app-streaming)
  and [VirtualDeviceManager](https://developer.android.com/reference/android/companion/virtual/VirtualDeviceManager).

No audited path currently proves full multi-app parity on arbitrary stock Android
phones. Do not promise that a permissions-only replacement exists until the
launch/capture/input combination has passed real tests. A single captured phone
screen or built-in web panels would be a narrower feature and must not be reported
as completion of the requested floating Android app experience.

## Next architecture milestone and acceptance

Build an isolated ordinary-UID feasibility implementation before replacing the
working development backend. All test capabilities and any Accessibility or
capture service must be installable in one APK; a separately signed fixture may
be used as a development test target, but cannot be a release dependency.

On both Pixel 8 Pro and OnePlus 15, demonstrate two independently rendered real
third-party apps at once, live frame updates, correct pointer targeting, taps,
scrolling, dragging, window closure/reopening, and reconnect recovery. Repeat
from a clean installation and after reboot, with no running shell helper and no
debugging prerequisite. Record the OS, app UID, granted access, display identity,
rendered frames, input feedback, and any unsupported applications.

If a required operation remains unavailable through user-grantable APIs, record
that exact boundary and evaluate a different architecture. A computer-run helper,
external Shizuku dependency, or root setup does not satisfy the single-APK goal.
An integrated phone-local ADB client is now an explicitly requested alternative
to investigate; it is distinct from the permissions-only path and must use
Android's own wireless-debugging pairing approval. User involvement after reboot
must be stated and tested, rather than assumed away.
