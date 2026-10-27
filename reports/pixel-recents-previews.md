# Missing Pixel Recents previews — 2026-10-26

The user sees app icons in the glasses' Recents menu but no app previews. The
read-only package dump confirms version 0.1.9 / 2883 is installed, not the newly
built universal 0.1.10. The helper log repeatedly records:

```
BEAM_RECENT_PREVIEW_FAILED com.google.android.calculator java.lang.ClassNotFoundException: android.window.ScreenCapture$CaptureArgs$Builder
BEAM_RECENT_PREVIEW_FAILED com.google.android.calendar java.lang.ClassNotFoundException: android.window.ScreenCapture$CaptureArgs$Builder
```

No cached preview JPEGs are present. The app's existing icon fallback explains
why icons can appear even though capture never reaches the screenshot service.
Evidence is in `artifacts/pixel-recents-20261026/analysis.json` and the retained
helper log. The current device framework SHA-256 matches the locally inspected
framework JAR.

## Cause and fix

This Android 17 framework declares `IWindowManager.captureDisplay` with
`ScreenCaptureInternal.CaptureArgs` and `ScreenCaptureInternal.ScreenCaptureListener`.
The older nested classes have moved out of `ScreenCapture`. The exact local
framework is authoritative here; the public AOSP main branch inspected during
research still contains the older API layout.

The helper's new `HeadlessScreenCapture` discovers these types from the actual
Binder method signature, then finds the matching builder and synchronous
listener. It keeps the legacy `ScreenCapture` path where that is the API the
platform accepts. There are no model or manufacturer checks.

It also connects directly through `ServiceManager`, avoiding
`WindowManagerGlobal`'s Android 17 `ApplicationSharedMemory` dependency. Display
information comes from synchronous `IDisplayManager.getDisplayInfo` reads;
headless shell callbacks can otherwise leave client display information stale.

The launch manager still determines ownership before any capture. The target
must be a named XREAL virtual app display, with valid geometry and a unique ID.
Ownership, display ID and unique ID are checked again before caching. Default
secure/protected-content exclusions remain in place; a result reporting secure
layers is discarded. Hardware capture handles are released after copying to a
software bitmap, which is encoded as a JPEG at up to 400 pixels and 256 KiB.
Closed windows retain their last successful preview. Failure never overwrites
a valid cached image, and errors now expose the underlying reflection cause.

## Validation scope

The production capture/cache code is exercised against legacy and Android 17
Binder contracts, including a public `ScreenCapture` class without the removed
builder. Thirty assertions per contract check the API, cache behavior, scaling,
ownership races, display-ID reuse, secure content, failed readback, oversize
images and handle cleanup. It compiles against the Android SDK. The APK audit
checks that the new adapter is actually called by the bundled helper.

Universal 0.1.11 / 2885 retains 0.1.10's color selector and the higher-resolution
headset icon loader. No new code was installed or executed on the Pixel, and
no device settings, caches or logs were changed or cleared. The capture fix
still requires a wearer test with an ordinary app opened in AR Space, followed
by Recents. Apps closed before any successful capture can only show the icon
fallback until reopened.

## Live 2919 check — 2026-10-27

On the installed 0.1.1 alpha / 2919 build, capture itself is working. The helper
uses the Android 17 path (`BEAM_RECENT_CAPTURE_API android.window.ScreenCaptureInternal`)
and wrote real 300×400 previews for Keep and LocalSend, with valid cached
previews for Calendar and eBay as well. Calling the launcher's `RecentAppsCompat`
against the running helper returned those tasks, each with a 300×400 hardware
snapshot. The Java handoff is fine.

The break is the next step. XREAL's `libHardwareBufferTexture.so` turns that
hardware bitmap into a Unity texture on a GL-only path. On this Pixel that path
fails, so Recents cards fall back to app icons. A replacement library with a
CPU-upload fallback was installed only as a device test. It is not in the repo.
That test build was removed and the original 2919 APK, including the original
64,296-byte `libHardwareBufferTexture.so`, was installed again. Whether a
CPU-upload fallback can show the previews still needs a wearer test, and that
library change has not been kept.
