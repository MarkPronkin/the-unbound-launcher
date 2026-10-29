# YouTube washout handoff — paused 2026-10-27

## Pinned baseline: 2935 — color work paused by wearer, 2026-10-28

The wearer reports **2935 looks way better on Pixel**, with a remaining slight
difference from OnePlus, and explicitly asks to pin this version and return to
the issue later. Stop color changes here until they resume the work.

The read-only pinned APK is
`artifacts/pinned/color-baseline-2935/the-unbound-launcher.apk`, SHA-256
`521c80a83992ff44d2307168ea3c7c19a924b7427cd1e0dca41fd3031c823aaa`.
Its build audit, shader tests and Pixel render/load evidence are archived beside
it; [baseline metadata](color-baseline-2935.json) records the result and deferred
work. `version.json` remains 2935, which is installed on Pixel.

Pixel's live 18:04:45–18:04:46 log confirms `mIsOverlayWindow = False`,
`texture=513 decode=shader renderer=Mali-G715`, and `material decode=shader`.
There are no copy-shader binary-load failures in that opening. All 14 focused
host tests and the full APK audit pass. The next investigation should
characterize the remaining difference with identical content/glasses; it should
retain the metadata-preservation fix and avoid restoring the old compositor
gamma patches.

## Confirmed shader packaging failure and 2935 — 2026-10-28

The wearer still sees washout on Pixel 2934. Live logs at 17:46:17 confirm the
routing correction works: `texture=510 decode=shader renderer=Mali-G715`, then
`mIsOverlayWindow = False`. Unity also logs **eight** occurrences of
`Failed to load GpuProgram from binary shader data in 'Custom/CopyOESTexture'`
while `RequireBlitProperties` loads the copy shader. Evidence:
`artifacts/pixel-color-2934-washed/`.

The source-array rewriter dropped the eight zero bytes after each aligned GLSL
array. Those bytes are the GPU flags and parameter count. UnityPy's exporter
stops before reading them, so extracting and compiling GLSL did not catch the
mistake. All 24 software-copy records and all 12 direct-OES records in 2933/2934
have the missing tail; the 24 hardware copy records retain it.

2935 preserves the entire post-source suffix, recalculates only source-array
alignment, changes shader cache identifiers, and logs the material's selected
decode state. The color curve, routing policy and compositor stay as in 2934.

`tools/unity_shader_loader.py` executes the actual pinned engine reader at
`0x291580` / `0x2909a0` under Unicorn. Only allocation, memory copy and keyword
bookkeeping are mocked. The real engine performs every binary bounds check and
reads the GPU flags/parameter metadata. It accepts all 60 corrected records and
rejects the 2934 software record at index 7. Unit tests also reject each record
with the final eight bytes deliberately removed and retain nonzero suffixes
across every source-alignment case. This check is now a build and APK audit gate.

## Live Pixel diagnosis and 2934 candidate — 2026-10-28

The wearer confirms **2933 looks correct on OnePlus**, while Pixel YouTube is
washed out. Wireless ADB identifies the Pixel 8 Pro (`husky`) on build 2933.
The live log shows `RequestOpenNativeApp ... True`, `CreateQuadSurfaceLayer`,
`UseProtectContent`, `mIsOverlayWindow = True`, and a native texture
`GL_INVALID_ENUM(1280)`. There are no `UnboundColor` texture-factory messages.
The corrected Unity fragment is bypassed. Artifacts:
`artifacts/pixel-color-2933-live/`.

The separate `AppInfo.isProtected` hint at `+0x72` wins before `useMultiLayer` is
examined. 2925 cleared only `+0x71`. Earlier notes describing YouTube as
non-protected do not describe this live opening.

2934 adds `AppSurfacePolicy`: in the main process, a private GLES3 pbuffer on a
dedicated thread checks the sRGB-override extension. It saves exactly one byte
in the app's private files directory. Missing extension selects copy; supported,
unreadable or failed capability selects the original overlay. The startup wait
is bounded to 1.5 seconds; the worker can finish later.

Six native trampolines preserve the original `isProtected` store and only clear
that SDK routing hint when the capability file is exactly `01`. They preserve
registers, stack and condition flags, and return to the original instruction
after each store. The disabled property collector's entry RET remains disabled.
No Android secure-window flags or virtual-display security flags change.
The OnePlus overlay route and all 2933 color shaders remain unchanged when the
GPU has the required extension.

Host checks execute all six entries for original hint values 0/1 and nine file
states, including failed open/read and malformed flags. EGL setup failures,
cleanup, exact extension tokens and stale capability reset are covered.

The standalone render fixture also ran on the physical Pixel (`Mali-G715`),
without installing or replacing the launcher: all 36 2933 fallback fragments
reproduced the input RGB bytes exactly, and both invalid controls were detected.
Results: `artifacts/pixel-color-2933-render/verification.json`.

The isolated EGL setup pairs initialization and termination on the probe thread;
Android's reference-counted display handling is documented in Google's
[Grafika EGL helper](https://github.com/google/grafika/blob/master/app/src/main/java/com/android/grafika/gles/EglCore.java).

The 2934 APK is built, signed and aligned at
`artifacts/unbound-launcher-2934/the-unbound-launcher.apk`, SHA-256
`98de5ca9e59c354467de8f34400b3c55100b6bc58e6bcf5b2c3f032fb83ba8ca`.
19 focused host tests passed. Running that APK's probe DEX on the physical
Pixel prints `ROUTING_COPY true`; its 36 shader fragments again reproduce every
reference RGB byte exactly. Results: `artifacts/pixel-color-2934-render/`.
Binary comparison proves all 2933 shaders and `libnr_api.so` are unchanged, and
the Unity-library difference is exactly the new capability routing.

The full APK audit passed, and 2934 was installed over the Pixel's confirmed
2933 APK (the on-device SHA-256 matched the host artifact). The launcher
reopened successfully. Its production startup log at 17:42:49 reports
`app_surface=copy renderer=Mali-G715 extensions_read=true`. A YouTube reopen
and wearer color confirmation are pending; artifacts are in
`artifacts/pixel-color-2934-live/`.

## Replacement candidate 2933 — 2026-10-28

The wearer now reports incorrect OnePlus YouTube contrast/saturation, and says
contrast had previously been fine there. Pixel's earlier saturation correction
also remains unsatisfactory. The candidate preserves the existing OnePlus
hardware copy path, removes **all** `libnr_api.so` color patches from the build,
and replaces the cubic Pixel copy fallback and direct-OES `pow(c, 2.2)` fallback
with an exact piecewise sRGB curve. Non-protected multi-layer apps retain the
Unity routing introduced in 2925; protected overlays remain stock.

The old sampler2D decode could run together with Adreno's remaining hardware
override. This is a plausible regression mechanism, not a verified hardware
diagnosis. Do not stack any of the older compositor corrections onto this
candidate. The APK verifier now rejects them using the full original-library
hash, after reversing the independent Display.Mode patch.

See [the current color approach](universal-app-colors.md) and
`tools/verify_app_color_runtime.py` for the actual-fragment render check.
No physical phone is connected, and no candidate is installed on either one.
Visual confirmation through both phones' glasses is still required.

The signed APK is finished at
`artifacts/unbound-launcher-2933/the-unbound-launcher.apk` (SHA-256
`071e814ebf607a94c51cc85a89c4405a006e7cc9ff73334d0c2160586a65efb6`).
36 focused host tests passed. All 36 fallback fragments from that APK reproduce
the reference RGB ramps exactly in the SurfaceTexture render fixture; the two
invalid-decode controls fail as expected. Results are in
`artifacts/app-color-runtime-2933/verification.json`. No phone installation.

## Update 2026-10-28

The published **0.1.2 alpha / versionCode 2924** was installed on the Pixel 8
Pro. Its `libnr_api.so` passes `verify_overlay_color` with the sampler2D
decode. The wearer reports **YouTube still washed out**, and Calendar looks
fine. So none of the 2922–2924 compositor decodes reaches YouTube's picture.
(The blank glasses after reconnecting were the home menu sitting above the
view; screencap showed it rendering normally.)

**Build 2925** adds `tools/overlay_routing.py`. In all six methods that compute
`AppInfo.useMultiLayer` on open, `strb w8, [xN, #0x71]` becomes `strb wzr`
(24 bytes, pinned input, exact restore, `tools/test_overlay_routing.py`).
Multi-layer windows such as YouTube then use the Unity copy path, the same one
Calendar uses. `isProtected` and the protected overlay are unchanged. Expect
`mIsOverlayWindow = False` and no `CreateQuadSurfaceLayer` when YouTube opens.
The 2922–2924 `libnr_api.so` edits are still in the build.

Why YouTube is on the compositor path at all, from `libil2cpp.so` (RVAs from
`artifacts/tools/il2cppdumper-6.7.46/script.json`):

- `EvaProAppSeparateWidgetContent.<RequestOpenNativeApp>d__51.MoveNext`
  (`0x32fd5dc`) branches at `0x32fda98`/`0x32fdaec`: `AppInfo.isProtected`
  (`+0x72`) → `CreateTemporaryNROverlay(…, true)`; else `AppInfo.useMultiLayer`
  (`+0x71`) → `CreateTemporaryNROverlay(…, false)`; else
  `CreateTemporaryAndroidSurfaceTexture`, the Unity copy path whose colors the
  wearer already confirmed (Calendar, Files).
- `OpenNativeApp` (`0x34ee2b0`–`0x34ee2f0`) stores both flags; `useMultiLayer`
  falls back to `Application2DManager.APPLICATIONN_USE_MULTILAYER` (static
  `+1`), which is off. YouTube logs `isProtected = False`, so it reaches the
  overlay through `useMultiLayer`. The server list
  (`app-api.xreal.com/…/space-app/config`, `multipleLayer`/`drmOpen`, value
  `< 2` means on) does not contain YouTube; where its flag comes from is still
  open.
- In the overlay, `com.xreal.glsurface.AndroidImageReader` feeds frames to
  `libyuvconvert.so`, which binds each AHardwareBuffer by EGLImage to
  `GL_TEXTURE_2D` (external OES only for swapchain type 4) with no sRGB
  override, then hands the textures to the `libnr_api.so` swapchain. That is
  consistent with 2922/2923 (OES-only decodes) changing nothing and 2924
  (sampler2D) being the first decode on the sampled texture.

Open risk in 2924: `RenderTextureGL::Create` still sets the
`GL_TEXTURE_2D` sRGB override at `0x14eed50`. On a GPU that accepts it
(Adreno), a texture that gets both would be decoded twice and look too dark.
Check a multi-layer app on the OnePlus before calling 2924 correct.

If 2924 does not fix YouTube, the smallest route change is to clear
`useMultiLayer` (and, since this port's virtual displays are never secure,
optionally `isProtected`) in `OpenNativeApp` / `OpenNativeAppWithIntent`, so
those apps use the Unity copy path.

## Original handoff, 2026-10-27

The phone was running **0.1.1 alpha / versionCode 2923**. YouTube still
looks washed out. Build 2924 is written in the tree and its host tests passed.
Its APK was not produced and nothing new was installed.

## Stopped here

`python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-2924`
was killed a few seconds in, while it was still compiling the shell server.
`artifacts/unbound-launcher-2924/` contains `build/` only. There is no APK.

`version.json` already says version code **2924**. Leave it there. The next
build of this tree is 2924.

Resume with:

```
python3 tools/test_overlay_color.py
python3 tools/build_embedded_adb.py --output artifacts/unbound-launcher-2924
```

If the partial `build/` directory makes the build fail, delete
`artifacts/unbound-launcher-2924/build` and run the build again. Install only
after an APK exists:

```
adb -s DEVICE_SERIAL install --no-incremental -r artifacts/unbound-launcher-2924/the-unbound-launcher.apk
adb -s DEVICE_SERIAL shell am force-stop dev.markpronkin.unbound
```

Wireless debugging restarts change the port. On 2026-10-27 the Pixel 8 Pro
(`husky`) was reached over wireless debugging. Host adb is the Android SDK
`platform-tools/adb`. Do not use the other paired phone. The earlier wireless
port was already dead.

Ask the wearer to look at YouTube again, and at one ordinary window
(Calendar or Files) so a decode that is too broad shows up.

## What the wearer has already rejected

| Build | What it changed | Result |
| --- | --- | --- |
| 2922 | IEC decode in the static OES blit shader at `0x6911dd`, and NOP of the two `GL_TEXTURE_EXTERNAL_OES` sRGB-override calls (`0x14eec2c`, `0x14ef1f4`) | YouTube unchanged |
| 2923 | Same, plus an IEC decode appended after composition type 2 and type 3 samples | YouTube unchanged. The cave is in the installed library |

2923 APK: `artifacts/unbound-launcher-2923/the-unbound-launcher.apk`, SHA-256
`e87d7050eb67c0d7be9aac3653db4dcde4bf94b94d79a045b1e6ec011c83d7d3`.
Pulled library: `/tmp/device-libnr-2923.so` (not in the repo). Pristine
library: `/tmp/wash/pristine-libnr.so`, SHA-256
`0fe3a49cc73bee642cbe1398a1ef7b124aaadf7e98b8319e2a25a6a6e1ebff4b`.

Do not ship another edit of the type-2 or type-3 sample. Those bytes are on
the phone and did not move the picture.

## What the live frames actually are

Checked while YouTube was playing, glasses on, launcher 2923.

- Glasses display is XREAL One, display id 3, 3840×1080 @ 60, color mode
  SRGB. HWC device-composes exactly one layer:
  `a7214c4 SurfaceView[](BLAST)#263`, full frame, `RGBA_8888`, protected,
  dataspace `V0_SRGB` (`0x08810000`, BT.709 + sRGB + full range).
  `screencap` of that display is black because the buffer is protected.
- YouTube itself is virtual display id 4,
  `com.xreal.virtualdisplay-com.google.android.youtube/.app.honeycomb.Shell$HomeActivity`,
  900×1200, owned by `com.android.shell` uid 2000. SurfaceFlinger
  client-composes it. Output dataspace is `UNKNOWN`, color mode `NATIVE`.
- The video SurfaceView on that display is format `0x301`, dataspace
  `0x10810000` (BT.709 + sRGB transfer + **limited** range), crop 854×480.
  The YouTube UI layer behind it is `V0_SRGB`.
- SurfaceFlinger logs `Use dataspace 281083904as it is` every frame.
  `281083904` is `0x10C10000` (BT.709 + SMPTE 170M + limited). `treat170mAsSrgb`
  is true. The log is the video buffer being used without a conversion.
- A normal `screencap` of the virtual display looks like ordinary YouTube
  (the capture was an aquarium video under the usual YouTube UI). That PNG
  is `/tmp/wash/yt.png`. It is not proof the buffer XREAL samples is the same
  colors: screencap converts to sRGB, and the virtual display's own consumer
  dataspace is unknown.
- `libnr_api.so` never passes `GL_FRAMEBUFFER_SRGB` (`0x8DB9`) to GL. The
  only `0x8db9` immediate is the itoa divisor at `0x1dd8e6c`. An sRGB EGL
  window can still encode on write without that call.

So the washed picture is the protected glasses surface. The two shipped
decodes never changed it. The unpatched sample that still matches a
virtual-display RGBA window is the composition shader's **sampler2D**
fall-through.

## The 2924 patch, uncommitted and uninstalled

`tools/overlay_color.py` now does this on top of 2922 and 2923:

- Type 2 still branches from `0x14d692c` to the cave at `0x7547ec`.
- Type 3 still calls the trampoline at `0x754858`.
- The shared sample at `0x14d6a20` used to be `ldrb w8, [x20, #0xac]`
  (`0x3942b288`). 2924 replaces that one instruction with `b 0x754918`.
  The rest of the sample (`0x14d6a24` through `0x14d6a6c`) stays in place
  and is not executed.
- The new cave at `0x754918` is 27 instructions (108 bytes) and ends at
  `0x754984`. It repeats the sample, appends the same IEC line, and branches
  to `0x14d6a70`. The string is still the one at `0x754888`. The zero gap
  ends at `0x754bf0`; 620 bytes remain.
- Decode line, unchanged:

```
color_top.rgb=mix(pow((color_top.rgb+vec3(.055))/vec3(1.055),vec3(2.4)),color_top.rgb/vec3(12.92),lessThanEqual(color_top.rgb,vec3(.04045)));
```

`python3 tools/test_overlay_color.py` passed: 4 tests, exact restore, no
unrelated bytes, and the cave disassembly. That was before the build, on the
host library only.

Not decoded, on purpose:

- sampler2DArray (composition type 1)
- static warp shaders
- the `GL_TEXTURE_2D` sRGB override at `0x14eed50` (Mali still returns
  `GL_INVALID_ENUM`; removing it does not fix color)
- protected-texture pname `0x8BFA`

## If 2924 is also wrong

- **Darker, including Calendar or Files:** the sampler2D decode ran on a
  layer that was already linear, or it stacked on the blit decode. Revert
  the `0x14d6a20` branch and keep type 2/3 as they are.
- **YouTube unchanged again:** GetComposeShader is still not the last writer.
  The next single change is the static one-layer warp at file offset
  `0x3b31cc` (489 bytes, `0x1e9`). It ends in
  `outColor = textureProj(texs0, warped_coordinate_array[0]);` with no
  decode. Do not edit the four-layer warp at `0x681e5d`: its last line is
  `outColor = 0.0*outColor`, so it is not the picture. Do not decode every
  sampler2D shader in the same build.
- **Limited range instead of gamma:** the video dataspace above is the other
  live theory. A last-stage IEC decode would still have been obvious if it
  had run. Do not patch dataspace until the writer of `0x10C10000` /
  `0x10810000` is identified. It was not found as an instruction immediate
  in `libnr_api.so`.

## Files already edited for 2924

- `tools/overlay_color.py`
- `tools/test_overlay_color.py`
- `version.json` (2924)
- `reports/universal-app-colors.md` (Protected native overlays)
- `embedded-adb/README.md` (one sentence on the overlay decode)

No commit and no pull request.

## Do not repeat

- Another Kimi review unless asked. The earlier PROCEED was for the blit
  shader, and the wearer rejected that result.
- OpenCode.
- NOP of `0x8BFA`.
- Flipping the IEC curve from decode to encode.
- Installing over the running launcher until the wearer asks, or until a
  finished 2924 APK is ready and they want it on the phone.
