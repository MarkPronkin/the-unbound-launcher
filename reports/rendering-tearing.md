# XR presentation tearing — offline investigation, 2026-10-25

## Result

A signed, opt-in buffered-presentation candidate has been built and installed. It changes the
shared XR screen-presentation policy, addressing the single-buffer mechanism
strongly implicated by the captured tear. **The user confirmed “tearing disappeared”
after installation on the OnePlus/XREAL One.** The initial investigation and build stayed offline as requested.
The user subsequently authorized installation. The installed checksum matches,
the helper connects, and the user confirmed AR Space looks normal. The subsequent
presentation trace shows changing submission timestamps, but an explicit SDK
screen-buffer-mode readback was not captured. See
[runtime verification](../artifacts/port-buffered-presentation/runtime-verification.json).

## Evidence and interpretation

The original 3840 × 1080 host recording contains a torn frame at PTS
**67.142911 seconds** (32.142911 seconds in `minecraft-earlier.mp4`). Both eyes
have a discontinuity between output rows 507 and 508. The left window boundary
changes from x=13 to x=97; its right boundary changes from x=1695 to x=1774.
The split affects the spatial window boundary, not only Minecraft's internal
image. In large sampled regions, the upper portion closely matches the next
decoded frame (mean absolute RGB difference 0.132/255) and the lower portion the
previous frame (0.240/255). See
[`confirmed-tear.json`](../artifacts/render-audit/capture-20261025-1110/frame-analysis/confirmed-tear.json)
and the [original decoded stereo frame](../artifacts/render-audit/capture-20261025-1110/frame-analysis/confirmed-tear-stereo.png).

The user also reproduced periodic artifacts during straight, fast Minecraft
flight without head swings, in Geometry Dash, and on stock Beam Pro. Those
additional cases were reported by the user; they were not separately recorded.

Saved OnePlus SDK startup logs explicitly contain `Single buffer is on` and
`single_buffer:1`. The same startup records a two-part render mesh with a boundary
at v=0.5294118. In top-origin output coordinates this is
`1080 × (1 − 0.5294118) = 508.235256`, matching the observed split row. These logs
predate the capture but use the unchanged original XR plugin. Evidence is in
`artifacts/port-bootstrap/glasses-live/close-fix-stream.txt`, lines 1249–1262.

Together these observations strongly implicate exposure of separately updated
regions of the single-buffer output. They do not identify a missing individual
fence or establish that every reported artifact has the same cause. Capturing a
shared output can also observe a concurrent write. The reproduction through the
glasses without recording makes a capture-only explanation less persuasive.

The EGL mutable-render-buffer specification explicitly requires separate
display synchronization for single-buffer/front-buffer rendering; the extension
does not provide presentation-time guarantees. This supports the mechanism, not
a claim about an unobserved driver implementation:
[Khronos EGL_KHR_mutable_render_buffer](https://registry.khronos.org/EGL/extensions/KHR/EGL_KHR_mutable_render_buffer.txt).

## Code trace

The pinned original `libXREALXRPlugin.so` has SHA-256
`0381362bcc46808df9ae27711d058250810c7a013c6fd5320e33b392135937e5`.

- `DisplayManager::Initialize` calls `ApplyUseSingleBufferMode` at 0xC7F6C,
  reads the flag at receiver offset 0x164, and passes 1 for true or 2 for false
  to `NativeRendering::InitSetScreenBufferMode` at 0xC7F84.
- `ApplyUseSingleBufferMode` normally reads `UseSingleBufferMode` from glasses
  initialization configuration. The singleton's default is true.
- `DisplayManager::SetSingleBufferMode` writes that same flag and follows the
  original renderer lifecycle before selecting screen-buffer mode through the
  same 1/2 mapping at 0xCD8B8–0xCD8D4.
- The native wrapper invokes `NRRenderingInitSetScreenBufferMode` through the
  rendering API table. The packaged loader exposes that symbol; the core API's
  name resolver also supplies an implementation. None of those wrappers are
  patched.
- `NRFrameBufferMode` is separately selected for stereo/mono in
  `GfxThreadStart`; it is not a screen-buffer count. That selection is preserved.

The disassemblies are saved in `artifacts/render-audit/native/`.

## Candidate change and verification

`tools/render_sync.py` makes two pinned changes, totaling twelve bytes:

1. `ApplyUseSingleBufferMode` becomes a leaf setter that writes false to the
   existing flag and returns. Startup configuration cannot turn single buffering
   back on.
2. The runtime setter coerces its boolean argument to false before the original
   field write. Its logging, renderer lifecycle and native API call remain intact.

This chooses the SDK's existing non-single-buffer path, requesting screen-buffer
mode 2. It does not add sleeps, force a different refresh rate, alter head poses,
change per-app capture, or edit a glasses firmware image.

The builder requires `--buffered-presentation`. Unit checks cover original-byte
and full-library restoration, startup field/address/register correctness,
runtime coercion, preserved mode selection, and rejection of partial or unrelated
patches. LLVM disassembly independently confirms the replacement instructions.
`tools/verify_render_sync.py` checks every logical application payload against the
working quick-access APK, requires the presentation patch, and runs the existing
app-limit, telemetry, quick-access, APK-signature and alignment checks.

Build and verification results are saved in
`artifacts/port-buffered-presentation/build.json` and `verification.json`.

## User validation and remaining coverage

The candidate is installed, initial startup checks passed, and the user confirmed
that tearing disappeared. This is a user-observed result, not a post-fix frame
capture measurement. Completion of each individual game scenario, reconnect
coverage and longer-term behavior were not separately stated. Latency has not
been reported or measured. An explicit SDK mode readback was not captured.
