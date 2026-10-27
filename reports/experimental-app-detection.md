# Experimental app/game detection

Build 2904 adds **Settings → Experimental → Detect as game**. The switch changes
Unbound's actual Android `ApplicationInfo.category` between `CATEGORY_GAME` (0)
and `CATEGORY_UNDEFINED` (-1, normal app). The manifest remains unclassified by
default. No other installed app is changed, and this setting does not change
rendering, frame rate, screen resolution or a gaming performance profile.

The switch reads the system category rather than saving an independent boolean.
Requests run off the UI thread, the control disables while a request is pending,
and its checked state follows the confirmed result. It refreshes when settings
regains focus and after recreation. Failures leave the actual category visible
with an explanation, including a missing or older helper.

Android exposes category hints only to the recorded installer; see the
[PackageManager API](https://developer.android.google.cn/reference/android/content/pm/PackageManager#setApplicationCategoryHint(java.lang.String,%20int))
and [Android's installer/category checks](https://android.googlesource.com/platform/frameworks/base/+/android16-release/services/core/java/com/android/server/pm/PackageManagerService.java).
When Unbound is its own recorded installer, its app process uses that public API
directly. Otherwise, a dedicated helper Binder request accepts only the launcher's
UID and the fixed query/normal/game operations. It derives the target package
from the installed launcher's UID; callers cannot choose another package.

The helper uses shell's recorded installer identity for ADB installs. For an
absent installer or one signed with the platform certificate, it temporarily
records `com.android.shell`, applies the hint and restores the original installer
in `finally`. It writes and syncs a recovery journal before that change and
recovers it after helper termination. Recovery does not overwrite a subsequent
update's installer. Installers with another signing certificate are rejected
without modification. Shell calls use its real package identity and perform
uncached Binder readback before reporting success.

Android stores the category across process restarts. Phone gaming services can
cache their own classification or use manufacturer catalogs, so users should
reopen the app after changing this option. Recognition by a specific OEM game
dashboard or performance optimizer requires testing on that phone.

## Validation

```sh
python3 -m unittest discover -s tools -p 'test_app_classification.py' -v
python3 tools/build_settings_ui.py --preview-only --output artifacts/experimental-app-detection
python3 tools/verify_app_detection.py --serial emulator-5554 --output artifacts/experimental-app-detection
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-experimental-game
python3 tools/verify_embedded_adb.py artifacts/unbound-launcher-experimental-game/the-unbound-launcher.apk
```

The production policy has 29 passing JVM assertions covering caller restrictions,
UID revalidation, invalid operations, idempotence, successful and rejected hints,
ignored writes, installer restoration, unsupported installers and crash recovery.
The Android 13 emulator checks the real package service's category in both
directions, fresh queries, adapter restarts and installer restoration. The
production switch also passes runtime checks for changes through the shell
Binder (7 assertions), its own recorded installer without a helper (7 assertions),
reopening settings and actionable failure without a helper (5 assertions).
The existing settings behavior checks (20 assertions) and OLED checks (33 assertions)
also pass, and the new row was visually checked in phone and two-pane tablet layouts. Preview
providers and instrumentation fixtures are excluded from the production APK.
The signed and aligned build 2904 passes the full production package audit with
zero duplicate classes. Its embedded helper and corresponding source archive
contain the current classification implementation.

Raw logs and build outputs stay under `artifacts/experimental-app-detection/`
and `artifacts/unbound-launcher-experimental-game/`. Physical-device validation
and manufacturer-specific gaming dashboards remain pending.
