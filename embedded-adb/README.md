# Single-APK on-device ADB variant

The current **0.1.14 (2888)** candidate is
`artifacts/unbound-launcher-multibrand/the-unbound-launcher.apk`.
It adds Android 11 provider-call compatibility, independent discovery of optional
OEM display APIs, broader window-scoped stereo recovery and manufacturer setup
help. Samsung, Honor, Oppo / Realme and other brands share this package; their
physical phone/glasses combinations remain unverified. See
[broader compatibility](../reports/multibrand-compatibility.md).


This version packages the launcher, wireless-debugging client, pairing UI,
signature-checked Binder provider, and shell launch helper in one APK. It needs
no Beam Port Probe installation, desktop ADB command, separate companion, or root.
Android's Developer options and wireless-debugging pairing are still required.

The candidate is `artifacts/unbound-launcher-adb-recovery/the-unbound-launcher.apk`,
version **0.1.2 alpha · on-device ADB**, code **2876**. It uses the launcher's existing
package and local certificate, so it can update that installation. The previous
Help APK remains available. Building this variant does not install it or replace
the currently installed launcher.

### Startup correction in 0.1.2

The complete 0.1.1 launcher failed after successful pairing on the OnePlus 15.
Its binary-manifest provider attributes were not ordered by Android resource ID.
Raw XML verification displayed `exported=true` and `:unbound_bridge`, but Android's
actual package parser skipped those settings. The shell helper was consequently
denied access to its provider. The isolated test APK used an aapt-compiled
manifest, so its earlier success did not exercise this shipping-manifest bug.

The builder now orders these attributes correctly, and verification rejects
unordered manifests. Device checks can parse the complete launcher archive with
Android's PackageManager, independently of the isolated test manifest. Startup
also removes stale read-only `.new` files and uses a forced rename over the
read-only helper destination, preventing interactive overwrite prompts on ADB's
shell channel. Connection discovery, ADB connection and helper-start failures
now have different recovery messages.

Build this revision without replacing the retained 0.1.1 artifact:

```sh
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-adb-recovery
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-adb-recovery/the-unbound-launcher.apk --report artifacts/unbound-launcher-adb-recovery/verification.json
```

## Phone setup

1. Install this single APK and accept the first-launch disclaimer. Review
   **App permissions** first. Display-over-apps access is required; the other
   permissions are optional. Tap **Continue to ADB** to open **Connect this phone**.
2. Connect to Wi-Fi and enable **Developer
   options → Wireless debugging** in Android Settings. Approve the network.
3. Tap **Start pairing** and allow notifications for code entry. In Android
   Settings, choose **Wireless debugging → Pair device with pairing code**.
4. Leave that dialog open, pull down notifications, and use **Enter pairing
   code** on the Unbound notification. Submit Android's six-digit code.
5. The client discovers this phone's pairing and connection ports. If pairing
   discovery is unavailable, reply with `six-digit-code:pairing-port`. The
   connection port can be entered separately in the app. These are different
   ports, both shown by Android. No remote host can be entered.
6. The notification immediately shows pairing progress, then **Code accepted**
   as soon as Android confirms pairing, while the helper starts. Its result replaces
   the code-entry notification, clears on tap, and expires after 30 seconds.
   Invalid or rejected codes retain code entry for another attempt.
7. Wait for **Ready**, then tap **Finish setup** to enter the launcher. Optional
   permissions skipped earlier do not appear again at this transition. After a reboot,
   enable Wireless debugging and use **Connect and start**. Android may require
   pairing again after the identity is revoked, expires, or the app is uninstalled.

With the **0.1.7** helper Ready, identified XREAL glasses get a saved **Desktop**
connection preference. The helper selects that mode automatically, including
the SBS reconnect, instead of requiring the repeated Android chooser. Android
may show the initial chooser before the helper has first configured the glasses.
Pairing consent remains part of Android's initial ADB setup. In older builds,
choose Desktop in both prompts: Android Mirror prevents renderer activity launch.

The fixed footer only allows continuation when the real launch-helper Binder is
alive. Completion is saved so subsequent permission reviews and ADB recovery do
not repeat first-run setup. Original glasses connection intents survive both
setup transitions. Disclaimer acceptance and ADB setup also defer the original
overlay-dependent application initialization.

Split-screen code entry and manual ports are under **Other ways to pair**. An optional floating panel is included,
but Android Settings can deliberately hide overlays; notification code entry is
the primary flow. Both the notification and panel stop after five minutes or
when closed. Pairing codes are not saved, backed up, or written to logs.

The helper is detached from its ADB connection. Once Ready is confirmed, Wireless
debugging may be switched off without intentionally stopping the helper. A reboot,
app update, force-stop, or provider death can stop it. No permanent shell access
after reboot is promised. Revocation is available under Android's **Wireless
debugging → Paired devices**. Remove the Unbound entry to revoke its ADB identity.

## Implementation

The launch build removes Splash's USB handler declaration, avoiding Android's
“Open Unbound to handle XREAL One?” confirmation on the glasses. Open Unbound on
the phone; its USB attach/detach receiver remains active. All SDK permission
requests go through the embedded helper after pairing. A separate shell worker
avoids Android 17's system-Context USB parcel restriction, rechecks the live
device and installed UID, and grants temporary access. The app verifies the
permission and delivers each original SDK PendingIntent result. Camera and
microphone privacy checks remain active. See the
[USB confirmation investigation](../reports/xreal-usb-permission.md).

All ADB sockets connect to `127.0.0.1`. mDNS results are accepted only when their
address belongs to this phone. Pairing uses SPAKE2 and TLS 1.3, with a fresh RSA
identity in private `noBackupFilesDir` storage. The client verifies shell UID 2000,
extracts the bundled helper from its installed APK, checks its SHA-256, makes the
DEX payload read-only, and starts it through `nohup setsid app_process`. Readiness
requires a live Binder from the provider; an ADB connection alone is insufficient.

The provider lives in `:unbound_bridge` under the launcher's package. Only shell
UID 2000 may publish a Binder. Consumers must share the launcher's signing
certificate. The helper also checks caller signatures and retains the original
restrictions on controlled displays. Provider death terminates this variant's
helper. The companion-backed development helper is unchanged.

The 0.1.11 candidate changes the manifest and `classes.dex` / `classes2.dex` / `classes3.dex` /
`classes4.dex` / `classes5.dex` relative to the previous Help APK. Two startup
hooks in `classes.dex` and one SDK-view constructor hook in `classes3.dex` restore
to that pinned baseline after checking every class definition. Seven icon methods
in `classes2.dex` also restore to the baseline, retaining Unity's raw-byte upload,
vertical flip and adaptive-layer flags. The helper, library
source/notices, and native TLS/pairing libraries are
added. The color change adds a texture factory hook and JNI getter in
`classes2.dex`, two material-selection call sites plus a 224-byte helper in
`libil2cpp.so`, and a combined copy-shader asset. The native helper occupies the
unreachable body after an already-disabled analytics RET; that RET is preserved.
All native color changes restore to the exact pinned library. All other native
libraries and resources remain unchanged.
The shipping APK excludes all device-test instrumentation and its exported test
activity. The new setup activity and service are not exported.

The production embedded helper also starts an independent display watchdog;
the companion/development helper and isolated test helper leave this feature off.
On Android builds with the inspected display APIs, it records Desktop only for
physical XREAL displays identified by MRG product data. The Desktop preference
is kept so Android can avoid the chooser on later connections. The helper's
discovery polls `IDisplayManager.getDisplayIds(true)` and `getDisplayInfo(id)`
directly. The 0.1.5 Pixel test showed Android deferring both shell processes'
display callbacks (`cached=true`, with pending events), leaving the framework's
client ID cache stale. Polling that client cache could never discover the glasses.
The direct snapshots include disabled displays and observe mode changes, unplug
and reconnect without receiving a callback. The helper's
resolution preference uses `storeMode=false`. If the live system's LIMIT_MODE
vote excludes the advertised SBS mode, a global app-mode policy override is
temporarily used while those glasses expose their SBS-only mode list. Android
does not expose a usable per-display exception for this cap on the inspected
Pixel build. This global override can affect mode selection on the phone too.

The 0.1.6 test reached XREAL discovery but crashed in KeyguardManager because
Android 17's WindowManagerGlobal requires ApplicationSharedMemory from normal
app binding. The 0.1.7 watchdog queries IWindowManager and IThermalService through
their named Binder methods, without constructing an app Context or service
wrapper. The lock and severe-thermal guards remain active. Mirror settings use
Android's fixed-key shell settings client and verify writes by reading them back;
this also avoids provider acquisition through an unbound app ContentResolver.
Global-policy restoration is attempted even if display enumeration fails, with
unfinished per-display recovery retained in the journal.

The user confirmed 0.1.7 enters AR Space; retained logs verify native stereo
output and successful app launches. App textures report `error=1280, OES_SRGB`:
the Pixel rejects the optional sRGB texture override, while the normal app copy
shader passes encoded pixels through. The Pixel-only 0.1.8 variant reused the
stock software-decoding shader. Universal 0.1.10 supersedes that split: it checks
the current GL extension and texture-parameter readback, then enables the
existing GAMMA_TO_LINEAR keyword only for textures requiring software decode.
The selected 24 shader variants use the exact stock software programs; the
remaining 24 preserve the original hardware-path programs. No device-brand
allowlist is involved. See [color selection](../reports/universal-app-colors.md).

The native SDK creates an independent Presentation whose views bypass the old
Java-view constructor hook. During the AR owner's lifetime, 0.1.8 discovers
actual roots with the public WindowInspector API. Only an identified stereo
XREAL Presentation receives immersive layout. A private opaque fullscreen
activity behind that surface owns the bar-visibility requests, including while
another app display has keyboard focus. Both windows remain non-focusable and
non-touchable. The background activity accepts only a live AR lease for the
matching glasses display, closes when the root/owner disappears, and refuses
stale restoration after process death. No system-wide navigation setting is
changed, and discovery stops with the AR owner.

The 0.1.8 Pixel colors are user-confirmed. Only installed-app headset icons remain
soft; built-in artwork is sharp. The stock icon loader sizes adaptive layers from
108 dp in system metrics and legacy icons from intrinsic size. The measured
Pixel density is 360 dpi, versus 476 dpi on the OnePlus; that formula gives
243 versus 321 pixels if those metrics are used. The 0.1.9 loader draws adaptive
layers directly at 432 × 432, matching built-in headset artwork, and preserves
legacy aspect ratio with a 432-pixel maximum dimension. It requests 640-dpi
source resources, keeps the ordinary icon if unavailable or if the adaptive/flat
representation changes, and avoids replacing a larger bitmap with a smaller one.
Drawable bounds, transparency, layer alignment and the native pixel contract
are preserved. Phone editor and built-in artwork are unchanged; no device
density or cache clearing is required. The source diagnosis is local; generated
Pixel icon dimensions and visual sharpness require the next wearer run.

Recents previews now discover the capture argument/listener types from the
actual `IWindowManager.captureDisplay` method. Android 17 moved them from
`ScreenCapture` to `ScreenCaptureInternal`; the old hardcoded builder caused
the retained Calculator/Calendar capture failures. `HeadlessScreenCapture`
connects to window/display Binder services directly, avoiding app-bound
`WindowManagerGlobal` initialization and the callback-fed display cache. Only
helper-owned virtual app windows are captured. Their ownership and unique
display identity are rechecked before saving; screenshot buffers are closed,
and a software bitmap is used for bounded JPEG encoding. Secure/protected
capture defaults remain unchanged. These checks run against both framework
contracts locally; hardware validation of 0.1.11 remains pending.

Disconnect, return to mono, lock/severe thermal status, parent EOF or an eight-second
heartbeat expiry restores the app-mode override and temporary preference. The
mirror setting is restored on disconnect, lock, or watchdog exit. If native stereo
does not become active within ten seconds, the watchdog restores state and exits;
failed sessions are retried no sooner than thirty seconds after their start. The
journal is synced before mutations; a restarted watchdog restores stale state
before beginning a new session. An OS file lock prevents two watchdogs racing.
If the watchdog itself is abruptly killed, recovery runs when the helper next
restarts; this is not a system-enforced Binder lease. No agent-driven setting
change or installation was performed on the Pixel while implementing this build.

## Build and verification

```sh
python3 tools/build_embedded_adb.py
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-multibrand/the-unbound-launcher.apk --report artifacts/unbound-launcher-multibrand/verification.json
python3 -m unittest discover -s tools -p 'test_multibrand_compat.py' -v
python3 -m unittest discover -s tools -p 'test_universal_color.py' -v
python3 -m unittest discover -s tools -p 'test_recent_previews.py' -v
python3 -m unittest discover -s tools -p 'test_headset_icons.py' -v
python3 -m unittest discover -s tools -p 'test_app_color.py' -v
python3 -m unittest discover -s tools -p 'test_managed_display_platform.py' -v
python3 -m unittest discover -s tools -p 'test_managed_display.py' -v
python3 -m unittest discover -s tools -p 'test_display_startup.py' -v
python3 -m unittest discover -s tools -p 'test_stereo_presentation.py' -v
python3 -m unittest discover -s tools -p 'test_embedded_adb.py' -v
```

Builds use the existing Android SDK and JDK 17 without Gradle or network downloads.
Dependency binaries are vendored and SHA-256 pinned. Corresponding ADB/SPAKE2
source, local library modifications, build script, and notices are included in
`assets/unbound-adb/pairing-sources.zip` in the APK. See `NOTICE.txt` and
`conscrypt-LICENSE.txt`. To relink a modified SPAKE2 library, replace the native
library in its vendored AAR, update its recorded checksum, and rebuild/sign the
APK. Its Java/JNI/C sources and native build configuration are included.

An isolated device-test package can be built without replacing the main launcher:

```sh
python3 tools/build_embedded_adb.py --test-only --output artifacts/embedded-adb-native-test
```

## Validation scope

OnePlus 15 / Android 16 checks cover installation-specific identity persistence,
native TLS loading, successful SPAKE2/AES-GCM exchange, rejection of a mismatched
password, real Android pairing, saved-identity reconnect, and startup of the
bundled helper as shell UID 2000 with a live Binder. These run in an isolated
test APK with the same implementation and different package/provider constants.
Notification UI validation and final artifact results are recorded in the
candidate's verification files.

The revised setup has 43 isolated Android 13 emulator assertions covering
disclaimer → permissions → ADB ordering, the required overlay grant, a disabled
Finish setup without the helper, Ready and continuation with the actual shell helper, notification entry without
an onboarding extra, incoming connection intent preservation, recreation, manual
port validation, and unsaved pairing codes. The non-ADB preview retains its
existing flow and passes 21 disclaimer assertions. Normal and 150% text layouts
were visually checked. Evidence is in `artifacts/embedded-adb-onboarding-test/`
and `artifacts/unbound-launcher-adb-setup/runtime-verification.json`. Current
ordering and notification evidence is in `artifacts/embedded-adb-order-notification-test/`.

The complete 0.1.2 launcher APK is installed on the OnePlus 15. Normal on-phone
**Connect and start** reaches **Ready**, including recovery from a stale read-only
temporary helper and read-only destination. Pairing, its identity file and the
app-data directory IDs are preserved. Android's actual archive parser confirms
the shipping provider's exported and separate-process settings; the same check
fails on 0.1.1. Fourteen targeted host regressions and the signature/alignment and
payload audits pass. Evidence is in `artifacts/unbound-launcher-adb-recovery/`.
Pixel 8 Pro user testing reports smooth pairing and a glasses 3D-mode switch,
followed by a repeated Android desktop/mirror chooser on entering AR Space.
Mirror shows black output; desktop shows a stretched/compressed Android desktop.
Read-only logs identify Android 17 / API 37, XREAL One and installed 0.1.2 (2876),
with a 640 × 480 default after the stereo reconnect. The local 0.1.3 (2877)
candidate requests the advertised stereo mode through its renderer window,
allows 45 seconds for Android's consent, retries display changes, and requests
a fullscreen renderer task. It makes no persistent system display changes.
The subsequent Desktop-mode test reaches the renderer but still presents at
640 × 480 physical / 850 × 480 logical size. The SDK opens its own Presentation
after the placeholder activity stops, so that activity's request does not cover
the actual output window. The local 0.1.4 (2878) candidate observes the SDK's own
views and applies stereo mode, full-window sizing, and zero system-bar insets to
their XREAL Presentation window. The request lives until the last watched view
detaches, and uses the external decor's context rather than the phone context
retained by the SDK child. Original native libraries are unchanged.
See the [Pixel test report](../reports/pixel-8-pro-user-validation.json) and
[display investigation](../reports/pixel-display-startup.md). This candidate has
not been installed or tested on any physical device. Its investigation used
read-only Pixel logs; no app or system setting on the Pixel was modified by the agent.
Full AR launch, glasses reconnect and reboot recovery remain unverified for this
revision. Android 11+ and the port's existing ARM64
and external-display requirements apply. The existing port/native-library limits,
including protected-content restrictions, are unchanged. Do not infer broad
phone compatibility from successful pairing on this OnePlus.
