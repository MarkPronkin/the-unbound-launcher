# Reference findings — 2026-10-25

This section records the initial documentation review. Subsequent Beam Pro
extraction and device measurements are in [the porting assessment](porting-assessment.md).
An experimental OnePlus port now renders AR Space and AIDA64 through an
ADB-started helper. See the assessment for current runtime findings and limits.

- The Beam Pro companion/server is called **MyGlasses**. On compatible Android
  phones, **ControlGlasses** acts as the server for XREAL SDK applications.
- XREAL describes MyGlasses as a consumer-oriented app with a broader feature
  set, and ControlGlasses as a lightweight tool for developer testing. The server
  supplies algorithm libraries, glasses status, 3D mode, and other services.
- The SDK compatibility page lists Samsung S24 / Android 14 and S25 / Android 15.
  OnePlus 15 is not on that list. This does not establish that it cannot work.
- The documentation currently warns that Android 16 compatibility issues are
  under investigation. Read the actual target phone OS version over ADB.
- SDK 3.1.0 adds 6DoF support for XREAL One series **with XREAL Eye** and matching
  firmware. Do not assume a bare XREAL One supplies positional tracking.
- Working USB-C video output establishes a display path. USB sensor/control
  access, tracking runtime, app compositing, and privileged OS interfaces still
  need separate verification.

## Primary references

- [ControlGlasses user guide](https://docs.xreal.com/Tools/ControlGlasses)
- [XREAL compatibility](https://docs.xreal.com/XREALDevices/Compatibility)
- [XREAL SDK 3.1.0 release notes](https://docs.xreal.com/Release%20Note/XREAL%20SDK%203.1.0)
- [Getting started with XREAL SDK](https://docs.xreal.com/Getting%20Started%20with%20XREAL%20SDK)
- [Android DisplayManager permissions](https://developer.android.com/reference/android/hardware/display/DisplayManager)

Official ControlGlasses 3.1.0 APK linked by the SDK release notes:

https://public-resource.xreal.com/download/XREALSDK_Release_3.1.0.20251124/ControlGlasses-3.1.0.20251118115716-release.apk

## Investigation status

Beam Pro My Glasses 2.2.2 and related XREAL APKs have now been extracted. Selected
Java/DEX code, framework services, and native ELF headers have been inspected.
A separate diagnostic APK has been built and run on both devices. Modified My
Glasses is installed on OnePlus 15 / Android 16, and the user has confirmed AR
Space, AIDA64/browser windows, pointer clicks and scrolling, smooth closing,
anchored/smooth-follow modes, and reconnect. This remains an experimental
ADB-assisted port; the assessment records its requirements and limits.
