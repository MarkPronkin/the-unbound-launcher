# GitHub app updater

Build 2903 adds **Settings → About → App updates** for The Unbound Launcher.
The existing glasses firmware updater is separate.

## Behavior

- **App updates** enables release checks and app installation. It defaults on.
- **Check on startup** checks once per main app process when the phone's settings
  activity resumes after setup. It defaults on and can be disabled independently.
- **Check for updates** runs a manual check. Errors and the last successful
  check time are displayed in About. Automatic failures do not interrupt startup.
- A newer release offers **Download and install** with release notes and file
  size. Downloads require a tap, show progress, and can be cancelled. They run
  off the main thread and continue while the phone UI is in the background.
- After downloading, the active phone settings screen opens Android's installer.
  If the app is in the background, About shows **Install update** on return.
- The private install activity opens Android's install-source permission screen
  when required and resumes verification and installation after permission is
  granted. Android always shows its installation confirmation. Cancelling the
  installer keeps the staged APK for retry; completed updates and interrupted
  partial downloads are cleaned up on the next process start.

## Release contract

The updater queries the public, unauthenticated GitHub releases API for
`MarkPronkin/the-unbound-launcher`, requesting the most recent 100 releases.
It includes published prereleases for alpha/beta installs; stable installs receive
stable releases. Drafts and unfinished asset uploads are ignored.

New releases must advance both the semantic `version_name` in `version.json`
and the Android `version_code`. Use matching tags such as `v0.1.1-alpha` and
version names such as `0.1.1 alpha`. Build metadata does not affect ordering.
Development builds sharing a release name are not offered as new public releases.

Attach exactly one universal/arm64 APK named `the-unbound-launcher.apk` or
`the-unbound-launcher-<version>.apk`. Preview, debug, test, x86 and armv7 variants
are excluded; ambiguous multiple APKs are skipped. The existing public alpha's
asset naming and GitHub-provided SHA-256 digest match this contract.

Downloads use HTTPS with bounded redirects to GitHub's release-asset hosts,
timeouts, size limits, a free-storage check, exact byte counts and GitHub's
SHA-256 digest when available. The APK must match the installed app's package
and current signing certificate, have a higher Android version code, support
the device's Android version, and match the advertised release version. Signing
key rotation is conservatively rejected. Android independently verifies the
package when installing it.

The download stays in private app storage. A non-exported content provider with
temporary URI read grants exposes only the staged `update.apk`. Other paths and
all write access are rejected. No broad storage permission is added. Existing
Internet and install-package permissions are reused.

Android 13's archive parser needs both `GET_SIGNATURES` and
`GET_SIGNING_CERTIFICATES` to collect the certificate and populate `SigningInfo`.
See [the Android 13 implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/core/java/android/content/pm/PackageManager.java#8071).
The release fields follow [GitHub's Releases API](https://docs.github.com/en/rest/releases/releases).

## Validation

```sh
python3 -m unittest discover -s tools -p 'test_app_updater.py' -v
python3 tools/build_settings_ui.py --preview-only --output artifacts/app-updater
python3 tools/verify_app_updater.py --serial emulator-5580
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-app-updater-2903
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-app-updater-2903/the-unbound-launcher.apk
```

The JVM tests cover semantic ordering, alpha display names, invalid versions,
private component registration, original manifest preservation, resource-ID
ordering and idempotence. The isolated Android instrumentation uses offline HTTP
fixtures for release selection, startup preferences, approved downloading,
progress, cancellation, truncated/corrupt downloads, rate limits, redirects,
package/signature rejection and provider path/write restrictions. It also tests
the actual About toggles and persistence.

The emulator verifier builds signed newer-version and wrong-signer/package
fixtures, runs the updater and existing About/OLED checks, opens the real Android
install-source settings and package installer, confirms the update, and checks
that Android installed the higher version code. Artifacts and logs remain under
`artifacts/app-updater/`. Validation on the Android 13 emulator passed: 43 updater
assertions, 18 existing About assertions, 33 existing OLED assertions, and the
real install-source permission return and confirmed self-update to fixture build
2904. The 17 focused JVM/manifest/source-archive tests also passed. The production
APK is signed and aligned as build 2903, and its full package audit passed with
zero duplicate classes and verified existing patch scope. Physical-device and full-size APK download
validation remain pending.
