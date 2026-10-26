# Universal app-window colors — 2026-10-26

Version 0.1.10 (2884) uses one APK for compatible ARM64 Android phones. This
includes the 432-pixel installed-app icon loader, the existing per-glasses
mono/stereo selector, and embedded ADB display recovery. A single package does
not imply that every phone, USB-C output implementation or glasses model is
hardware-validated. Samsung and other additional phones remain untested.

## Why the earlier packages differed

The Pixel's retained app log reports GL_INVALID_ENUM when XREAL requests
TEXTURE_FORMAT_SRGB_OVERRIDE_EXT on its external app texture. The stock copy
shader assumes hardware conversion. Version 0.1.8's replacement shader restored
colors on Pixel, as confirmed by the wearer, but could decode twice on a GPU
where the original override succeeds. The device-independent icon fix did not
require that package split.

## Runtime selection

`AppColorCompat.generateExternalTexture` keeps the original external texture,
filtering and clamp parameters. On the owning render thread it checks the exact
GL_EXT_texture_format_sRGB_override extension token. When present it requests
the original SRGB override and checks both the GL error and parameter readback.
A rejected or unverified override is reset before software fallback. The result
is stored per Java surface owner in a synchronized weak map; the Unity-thread
getter performs no GL work. No manufacturer, model or Android-version test
chooses the color path.

The original native app and phone-mirror copy-material creation calls retain
their FLIP_Y keyword. A small, pinned native helper queries the associated Java
surface and enables GAMMA_TO_LINEAR when software conversion is needed. It uses
the existing AndroidJavaObject and Material APIs, retains the caller's ABI and
leaves the original app-window geometry, alpha, input, native display provider,
per-eye calibration and glasses mode selector intact.

The shader combines existing programs: 24 GAMMA_TO_LINEAR variants come from
the stock software decoder; 24 other variants remain the original pass-through
programs. Serialized keywords, properties and render state stay intact. Program
offsets/lengths and cache identifiers are updated. The separate software shader
asset is retained verbatim. This changes the normal app-window copy path;
experimental direct-OES rendering is not newly enabled or claimed validated.

The selection occurs when a copy material is created. The current renderer keeps
that material across size changes on the same GPU. Context recreation and
reconnects are part of the pending hardware test, along with multiple app windows.

## Audit and validation

- The DEX factory replacement and added getter restore all original definitions;
  they compose with the seven already-audited headset icon hooks.
- Two native call sites and 224 bytes after a disabled analytics RET are changed.
  The analytics entry remains disabled. Reversing those changes reproduces the
  exact pinned library SHA-256. Other native libraries are untouched.
- Host checks exercise supported, absent, rejected and unreadable GL capability
  cases, per-surface decisions, both native entry points, both color branches,
  null-surface behavior and register/stack preservation. The bounded AArch64
  interpreter validates the actual selector instructions with mocked engine/JNI
  calls; it is not an Android runtime test.
- Every selected shader program is compared with its original stock variant,
  and all 96 vertex/fragment stages are compiled locally.
- No phone installation, pairing, setting change or new code execution on a
  connected device is performed while building this candidate. Hardware results
  for this version remain pending.

The extension's semantics and external-texture applicability are documented in
the [Khronos EXT_texture_format_sRGB_override specification](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_texture_format_sRGB_override.txt).
It converts RGB during sampling; alpha is unaffected. The fallback retains
XREAL's original inexpensive software approximation rather than introducing
a new transfer curve.
