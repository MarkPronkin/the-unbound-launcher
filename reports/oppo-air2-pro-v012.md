# OPPO CPH2791 / Air 2 Pro blank output on 0.1.2

## Report and version gate

The supplied `unbound-debug-20261027-181116-98914fc5.zip` identifies **0.1.2
alpha, build 2924**, on OPPO CPH2791, Android 17 / API 37, with XREAL Air 2 Pro.
This meets the request to investigate only reports running 0.1.2. The wearer
sees the phone controls and hears scene audio, but sees nothing in the glasses.

Evidence is retained under `artifacts/oppo-egl-analysis/input/`. Times below
are the report's device time (EDT, 2026-10-27).

- At 14:09:25.068, the direct Presentation path starts the phone-owned Unity
  lifecycle with external display 71 active at **1920 × 1080 / 90 Hz**.
- At 14:09:25.466, the graphics log identifies **Mali-G1-Ultra MC12**. Its EGL
  extension list includes `EGL_KHR_gl_colorspace`.
- At 14:09:29.580, NRSDK obtains the Android surface and starts rendering. The
  active native hardware profile is type 1.
- From 14:09:29.591 to 14:09:42.780, the log records **220** occurrences of
  `Failed to create window surface:12292`, with **109** failed surface/warp
  initialization attempts. The SDK's single-buffer attempt and subsequent
  back-buffer fallback both fail. Unity subsequently reports
  `NativeRendering+SubmitFrame` errors.
- At 14:09:50, USB and display removal are recorded. The later exported
  DisplayManager and SurfaceFlinger dumps therefore contain only the phone
  display. Those dumps do not establish that external output was absent
  during the rendering failure; the earlier app snapshots show it active.

Decimal 12292 is `0x3004`, `EGL_BAD_ATTRIBUTE`. The scene cannot reach the
glasses because its EGL surface fails to initialize. Searching the room or
recentering the scene cannot resolve that graphics failure.

## Native defect and correction — build 2942

The SDK's real attribute builder at `libnr_api.so:0x14cad80` obtains the
colorspace attribute from its hardware profile's vtable. In type 1, the getter
at `0x14b0688` returns **`0x3087` (`EGL_VG_COLORSPACE`)**, the OpenVG attribute.
Its linear/sRGB values are `0x308a` / `0x3089`. The other four concrete profiles
already return **`0x309d` (`EGL_GL_COLORSPACE_KHR`)** with those same values.

[Khronos defines `0x309d` for OpenGL and OpenGL ES surface creation](https://registry.khronos.org/EGL/extensions/KHR/EGL_KHR_gl_colorspace.txt).
The extension deliberately aliases the linear/sRGB values to their OpenVG
equivalents; the attribute name itself is different.

`tools/egl_colorspace_compat.py` changes one ARM64 instruction:
`MOV W0, #0x3087` becomes `MOV W0, #0x309d`. The getter's return and every caller
remain intact. Buffer selection, requested linear/sRGB value, protected-content
attribute, display selection, refresh rate, shaders and the fallback between
single/back buffering retain their existing behavior. No phone-brand check or
firmware command is introduced.

The patch takes the pinned library after the existing API-34 HDR-lookup fix.
Its full-library hash and exact restoration reject repeated, partial and
unrelated changes. Build and independent APK audits restore this correction
before auditing the preceding patch. The corresponding-source ZIP includes
the new patch implementation.

The public 0.1.2 / 2924 APK also contains this original getter, checked directly
against `artifacts/unbound-launcher-0.1.2-alpha/the-unbound-launcher.apk`.

## Validation and limits

Three native host regressions execute the actual SDK attribute builder and
relocated hardware vtables. They cover all eight combinations of single/back
buffering, protected/unprotected output and linear/sRGB for type 1, unchanged
results in the other four concrete profiles, register/stack preservation and
exact patch scope/restoration. The existing three native Display.Mode tests,
five overlay-routing tests and ten embedded-ADB tests also pass.
Two version/upgradability tests pass, for **23 targeted host tests** in total.

The Android 13 emulator accepts the old OpenVG attribute and lacks the reported
GL-colorspace extension; it cannot reproduce this driver-specific failure.
That limitation is retained in `artifacts/oppo-egl-runtime/`, rather than
reported as a successful reproduction.

A temporary background-only instrumentation fixture on the connected Pixel 8
Pro's Mali GPU reproduces **EGL_BAD_ATTRIBUTE with the original SDK getter**
for both linear and sRGB. With the patched getter, real Android
`eglCreateWindowSurface` succeeds in both cases. The fixture queries the
requested colorspace, renders a pixel, verifies linear versus sRGB encoding
and successfully swaps the window buffer. It uses a private SurfaceTexture
native window and cleans up its test package. It launches no phone Activity
and does not change the launcher's running session or display settings.

Only the fixture copies replace an existing exported JNI entry with a branch
to the actual SDK getter. Production contains only the audited four-byte
correction, alongside the pre-existing HDR fix. The host test covers the full
attribute builder; the Android fixture covers the real getter and EGL driver.
It does not initialize NRSDK or simulate tracking/glasses output.

The test candidate remains named **0.1.2 alpha**, with Android build **2942**:
`artifacts/unbound-launcher-oppo-egl-2942/the-unbound-launcher.apk`.
Its SHA-256 is
`88bee182548e2863464c2a63766611468f81370b189ae5fe95025f88e55f838b`.
Its certificate matches public 0.1.2 / 2924, so it supports an in-place update.
The signed APK's exact SDK library also passes the Android fixture on
**Mali-G715**: the original getter produces `0x3004` in both cases; the corrected
getter produces `EGL_SUCCESS`, with green-channel pixel values **128** (linear)
and **188** (sRGB), as expected. Evidence is under
`artifacts/oppo-egl-runtime/signed-2942/`.
Build/audit evidence is recorded alongside the APK and under
`artifacts/oppo-egl-analysis/`.
The independent full APK audit passes, including signing/alignment, exact
native and DEX restoration, bundled source correspondence, package/version,
class uniqueness and existing permissions. Consolidated results are in
`artifacts/oppo-egl-analysis/validation.json`.

**Physical OPPO / Air 2 Pro confirmation is pending.** Install the candidate
as an update and reconnect the glasses. If output is still blank, create a
fresh debug archive while the glasses remain connected in the failed state;
this preserves live display/compositor evidence for the next failure stage.
