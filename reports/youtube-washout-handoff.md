# YouTube washout handoff — paused 2026-10-27

The phone is still running **0.1.1 alpha / versionCode 2923**. YouTube still
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
