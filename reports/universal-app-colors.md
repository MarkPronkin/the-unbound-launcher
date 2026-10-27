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

## Protected native overlays

YouTube and other protected windows do not use the Unity copy material. The
Pixel log shows `CreateQuadSurfaceLayer` / `UseProtectContent`,
`mIsOverlayWindow = True`, then `RenderTextureGL::Create` reporting
`GL_INVALID_ENUM`. `libnr_api.so` asks `GL_TEXTURE_FORMAT_SRGB_OVERRIDE_EXT`
to treat the external image as sRGB. Mali does not implement that extension.
Calendar and Files stay on the Unity path this report already covers.

Build 2922 removed the two external override calls and rewrote the static
318-byte `samplerExternalOES` shader with the IEC 61966-2-1 decode
(`<= 0.04045` uses `c/12.92`, otherwise `((c+0.055)/1.055)^2.4`). That shader
is the blit shader. On the Pixel, YouTube stayed washed out. The picture is
produced by `RenderCompositionOpenGL::GetComposeShader`, which assembles a
fragment shader at runtime. External layers (type 2, and type 3 with a texel
size) sample with a pass-through `texture` / `textureProj` into a surface the
compositor labels sRGB, so encoded pixels are encoded again.

Build 2923 appended that decode after type-2 and type-3 samples only. The
installed library contained the cave, and YouTube stayed washed out. A capture
of YouTube's virtual display is an ordinary RGBA window, and the glasses scan
out one protected surface. The layer that reaches that surface is the
sampler2D fall-through, which 2923 did not decode.

Build 2924 keeps the type-2 and type-3 caves. The first instruction of the
shared sample now enters a separate cave, repeats the sample, and appends the
same IEC decode of `color_top.rgb`. sampler2DArray and the warp passes are not
decoded. The blit rewrite and the two override removals stay, so a GPU that
implements the override still decodes once. Alpha is unchanged. The
protected-texture parameter stays; a remaining `GL_INVALID_ENUM` from that
parameter is not the color path.

Host checks pin the blit shader, both override calls, the three composition
branches, both caves, and an exact restore, including on top of the Display.Mode
edit. Wearer confirmation on YouTube and on an ordinary window is still
required. If YouTube goes dark, this sampler2D decode and an earlier decode
both ran. If it is unchanged, the visible pixels are still outside this shader.
