# Universal app-window colors

## Pinned state — build 2935, 2026-10-28

Color work is paused at the wearer's request. Pixel is now much improved, with
a slight remaining difference from OnePlus. The repaired shader loads and the
live material selects software decoding. Full APK and focused host checks pass.
Keep 2935 as the current baseline; resume color tuning only when the wearer asks.
The read-only APK and validation evidence are in
`artifacts/pinned/color-baseline-2935/`; see
[baseline metadata](color-baseline-2935.json) for its checksum and remaining work.

## Shader binary packaging correction — 2026-10-28, build 2935

2934 successfully moves Pixel YouTube onto the copy path, but Unity rejects the
modified copy shader. The source rewriter had discarded two zero-valued metadata
fields following the aligned GLSL array. The standalone GLSL render test missed
this because it bypassed Unity's binary loader.

2935 preserves that metadata and refreshes shader cache identifiers. Every
build/audit now runs the actual Unity ARM64 GPU-program parser for all 60 app/OES
records. The parser reproduces 2934's rejection and accepts the corrected
records. Pixel's route and transfer curve are unchanged; the OnePlus hardware
copy programs remain stock. Live material decisions are logged as well.

See [the current handoff](youtube-washout-handoff.md) for the native reader
addresses, negative controls and pending device result.

## Live result and routing correction — 2026-10-28, build 2934

The wearer confirms 2933 is correct on OnePlus and remains washed out on Pixel.
Live Pixel logs show YouTube still using a protected native overlay: the
`AppInfo.isProtected` hint wins before the previously cleared multi-layer hint.
The Unity copy decoder is never created for that window. This is evidence of a
missed path, not evidence that the exact sRGB curve is wrong.

2934 keeps the 2933 compositor and shaders. `AppSurfacePolicy` checks the GPU's
sRGB-override extension on a dedicated thread using an isolated EGL pbuffer,
without borrowing Unity's context. A private one-byte flag selects the copy
route when that extension is absent. Six pinned, reversible native trampolines
apply that choice to the SDK's protected-app routing hint. Supported GPUs,
unknown capability, missing files, failed reads and malformed flags all retain
the original overlay. This preserves the validated OnePlus route.

The port's virtual display remains non-secure, and Android's actual secure
window flags are untouched. The SDK hint does not grant secure capture. A true
secure video cannot be made capturable by this route change.

Evidence and native/Java validation details are in
[the live Pixel handoff](youtube-washout-handoff.md). 2934 passed the full APK
audit and is installed on Pixel. Its production probe selects `app_surface=copy`
on Mali-G715. The actual Pixel also passes the shader pixel checks. A YouTube
reopen and wearer confirmation remain pending.

## Replacement candidate — 2026-10-28, build 2933

The wearer reports incorrect YouTube contrast and saturation on OnePlus and
unsatisfactory saturation on Pixel. They do not recall a OnePlus contrast issue
before the recent fixes. Treat the contrast symptom as a possible regression;
double conversion is an explanation to test, not a measured diagnosis.

This candidate removes the accumulated `libnr_api.so` color patches completely.
The binary must now restore byte-for-byte to the original library after undoing
only the independent Display.Mode API compatibility edit. This removes the
static blit decode, all three composition decodes, and the two override-call
removals together. On Adreno, the old sampler2D shader decode could stack with
the remaining native texture override; readback of an override enum alone also
does not prove that conversion happened for a particular EGLImage: the
[override specification](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_texture_format_sRGB_override.txt)
allows format-dependent support and ignored overrides.

Non-protected app windows retain build 2925's routing through Android's
composited RGBA SurfaceTexture and Unity's copy material. Android handles the
video's YUV matrix and limited range before this boundary. Do not apply video
range expansion to the combined app image: that also changes the full-range UI.
Protected app windows keep their stock overlay and are not claimed fixed.

Keep the existing, per-texture hardware negotiation on supported GPUs, including
the OnePlus path that previously worked. Where the override is absent or cannot
be verified, the copy fragment now uses the IEC 61966-2-1 piecewise sRGB decode
instead of the stock cubic approximation. The optional direct-OES fallback uses
the same curve instead of `pow(c, 2.2)`. Alpha, tint, texture orientation, vertex
stages, and hardware copy programs retain their prior behavior. Shader cache
identifiers change so old software programs cannot be reused.

This is a correction to the transfer boundary, with no saturation or contrast
multiplier. It does not establish that every source buffer is sRGB or fix HDR
and wide-gamut metadata. A video-only error after this change requires a live
source/output dataspace trace rather than another global gamma change.

The original shader and native patch tests remain as historical coverage.
`tools/test_universal_color.py` audits the precise software-source changes,
unchanged hardware variants, compiled GLES stages, Java negotiation and native
selector ABI. `tools/test_overlay_routing.py` also proves the compositor audit
rejects the retired corrections. `tools/verify_app_color_runtime.py` renders the
actual APK fragments using a Canvas-produced RGBA SurfaceTexture and an sRGB
framebuffer on an isolated emulator, checking grayscale, primary/secondary
ramps and mixed RGB colors, with missing-decode and double-decode negative
controls. The
[external-image specification](https://registry.khronos.org/OpenGL/extensions/OES/OES_EGL_image_external.txt)
defines sampled RGB in the source image's transfer encoding; an
[sRGB EGL framebuffer](https://registry.khronos.org/EGL/extensions/KHR/EGL_KHR_gl_colorspace.txt)
encodes linear fragment output on write in GLES.

Neither physical phone is connected during development. A finished candidate
still requires the same SDR video and ordinary app window to be viewed on both
phones, including a reconnect. That checks both the new OnePlus symptom and
Pixel's remaining saturation complaint. An emulator cannot validate the phone
driver, SurfaceFlinger video conversion or the protected glasses scanout.

Validation of the finished 2933 APK: 36 focused host tests passed; signature and
16 KB alignment checks and the full APK audit passed. Binary comparison with
2932 confirms the Java texture negotiation and Unity library (including routing
and material selection) are unchanged; the compositor difference is exactly the
removal of the retired color patches. The emulator executed all 36 software fragments
from the APK, plus two deliberate transfer-error controls, for 262,656 RGB
component comparisons. Corrected fragments reproduced every input byte exactly;
both invalid controls were rejected. Its known Canvas colors had unknown
dataspace metadata, so this is a fragment/SurfaceTexture test, not a test of
SurfaceFlinger's video color management.

APK: `artifacts/unbound-launcher-2933/the-unbound-launcher.apk`.
SHA-256: `071e814ebf607a94c51cc85a89c4405a006e7cc9ff73334d0c2160586a65efb6`.
Render results: `artifacts/app-color-runtime-2933/verification.json`.

## Historical implementation — 2026-10-26

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

## Historical compositor experiments — 2922–2924, retired in 2933

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
