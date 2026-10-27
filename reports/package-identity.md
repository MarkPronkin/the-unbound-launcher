# Unbound's own Android package identity

Build 2906 installs as **`dev.markpronkin.unbound`**, replacing the release build's
use of XREAL's `com.xreal.evapro.nebula` application ID. Its signing key remains
the same, with SHA-256 certificate fingerprint:

```
5f9e5010b3faab5da4b3047619eabefd9a96ada7898e9bdab3e22c72adf6237a
```

Use the new application ID and this certificate fingerprint for Unbound's
Android developer verification registration.

## Developer registration

On 2026-10-27, **The Unbound Launcher** was submitted in the owner's verified
Android Developer Console account with package name `dev.markpronkin.unbound`
and the certificate fingerprint above. The fingerprint was checked directly
against the signed build 2906 APK before submission.

After review and a page reload, the console shows **Registered** for the
package and **Verified** for the signing key. Google did not request an
ownership-verification APK during this submission. This registration covers
the package and signing certificate; it does not publish the app on Google Play.

## Installation and existing users

Android treats a changed application ID as a new app. The old installation is
retained, and the new installation has its own UID, permissions, Wireless
debugging identity, preferences, app-menu data and external-files directory.
Complete setup and pairing again. No existing app data is deleted or copied.
Updates for this new installation must carry the new package name and the same
signing certificate; the app's existing updater enforces both.

The package migration is included in **0.1.1 alpha (build 2908)**. The earlier
public 0.1.0 alpha APK retains its original package identity. Completely uninstall
previous Unbound builds before installing this alpha, then repeat setup and
pairing; this clean-install recommendation also applies to development builds.

## What moves together

- The manifest application ID, package-scoped permissions, actions and every
  content-provider authority, including the original providers, helper, APK
  installer and debug ZIP provider.
- Fixed package references in the USB permission adapter and helper, automatic
  glasses entry, renderer process restart and experimental stereo preference.
- The Android resource-table package name and Unity's two app-ID literals.
- The native experimental-stereo preference path. Java and Unity read the same
  new package-owned file.
- The helper's installed-UID lookup, provider endpoint, launch component and
  recents exclusion. Helper extraction/logs and recents files use a directory
  under the new package name.

The display-policy watchdog keeps its shared lock and recovery journal. This
protects the device-wide display policy from concurrent owners; two installed
launchers should not run AR sessions simultaneously.

## How the binary compatibility boundary is maintained

`tools/package_identity.py` defines both identities. Their lengths match, so the
resource-table name, binary XML strings, Unity metadata literals and native
preference path can be relocated without changing data sizes or offsets.
Resource IDs, original Java class names and JNI bindings stay the same.

`tools/RepackageDex.java` rewrites string constants and encoded string values
with dexlib2, which rebuilds sorted DEX tables and checksums. It reverses the
identity change and compares the complete canonical DEX with its input before
accepting the result. A raw byte replacement in DEX would break its ordering
requirements and is deliberately avoided.

The release builder applies identity relocation after the existing audited
startup, USB, color, tutorial and experimental-stereo patches. The original
native patch specification still contains its pinned baseline path; the final
packaging step relocates that path. The APK ships the corresponding relocation
source alongside the other pairing/helper sources.

The full APK audit reverses the identity change before auditing the existing
DEX/native hooks against their original baselines. It also checks resource and
metadata restoration, component/provider identity, helper wiring, class
uniqueness and absence of the old app ID from runtime payloads. The bundled
source archive intentionally retains references describing the original ID.

## Build and checks

### Public 0.1.1 alpha, build 2908

The 2026-10-27 release rebuilds the latest user-confirmed working development
build with **0.1.1 alpha** as both the Android version name and the About page's
App version. Android version code **2908** advances beyond builds 2906 and 2907.
The registered package and signing certificate remain unchanged.

All 145 host regressions pass. The complete packaged-code/resource audit, v3
signature verification and 16 KB alignment checks pass; all 154 bundled source
files match the build inputs. A DEX check verifies the version string used by
the actual About rendering method, as well as the generated version constant.
Application debugging remains disabled. The release version rebuild has not had
a separate physical glasses test; the earlier 2907 emulator evidence below
remains the runtime baseline.

Release assets are under `artifacts/releases/v0.1.1-alpha/` locally and GitHub
tag `v0.1.1-alpha`. APK SHA-256:
`d7b3fe480f625801a767010b899818c0cddb04c589ed761a13ed708e8ed92a4b`.

### Registered build 2907

On 2026-10-27, the latest local sources were rebuilt after registration as
**0.1.0 alpha, build 2907**, retaining the registered package name and signing
certificate. The version increment permits updates over build 2906.

The deliverable is
`artifacts/unbound-launcher-registered-2907/the-unbound-launcher-0.1.0-alpha-build2907.apk`,
with a SHA-256 checksum and `validation.json` in the same directory. APK SHA-256:
`c595e7c9c51eb154ef1033494b929df0f98912f8c1d65937b0d5928e453857a9`.

All 145 host regressions and the complete packaged-code/resource audit pass.
The release APK has debugging disabled, a valid v3 signature matching the
registered certificate, and verified 16 KB native-library alignment. All 144
production package checks and normal startup pass in a disposable API 33 ARM64
emulator with the older public launcher installed alongside it. These cover
component loading, helper publication, storage/provider relocation and update
validation. Physical phone/glasses validation remains pending. This build has
not been published to GitHub Releases or Google Play.

### Package migration build 2906

Validation on 2026-10-27:

- All 145 host regression tests passed, including the three package-relocation
  tests and the existing USB, display, startup, storage, input and updater suites.
- The signed APK passes `verify_embedded_adb.py`, with 22,938 class definitions,
  no duplicate classes, and all 154 bundled source files matching the build inputs.
- Android build tools confirm package `dev.markpronkin.unbound`, version code
  2906, the unchanged signing certificate, a valid v3 APK signature and 16 KB
  native-library alignment.
- All 144 checks against the installed production APK pass in an API 33 ARM64
  emulator with the old launcher also installed. They cover component loading,
  resources, storage, stereo persistence, the real helper/provider connection,
  USB/automatic-entry/restart constants, provider URIs and update validation.
  The normal Splash entry also cold-starts successfully into permission setup.
- APK SHA-256:
  `0bdea70c1721b463080fca05cd9349d8514390eeeb623805055ccf194fd9acb4`.

```sh
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-own-package
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-own-package/the-unbound-launcher.apk --report artifacts/unbound-launcher-own-package/verification.json
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 tools/verify_package_identity_runtime.py --output artifacts/unbound-launcher-own-package
```

The runtime check requires an isolated ARM64 emulator with the earlier launcher
installed. It installs the production APK alongside that app and runs a separate,
same-signer instrumentation APK against the production installation. It checks
component loading, resource lookup, private/external storage, experimental stereo
persistence, the real helper/provider connection, package-dependent constants,
update/debug provider URIs, acceptance of a newer same-ID/same-signer update,
and rejection of an APK carrying the old package name.

Physical phone/glasses validation remains required for AR rendering, SDK
authorization, automatic USB entry, pointer input and repeated reconnects.
