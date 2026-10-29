# OPPO CPH2791 / Air 2 Pro: stretched experimental 3D on 0.2.0

The wearer reports: “3d was unusable so i had to turn it off” and “i could
see things but they were stretched.” The supplied
`unbound-debug-20261029-004047-ce4d0df1.zip` identifies **0.2.0 alpha, build
2946**, OPPO CPH2791, Android 17 / API 37, with XREAL Air 2 Pro (SDK model 3).
The archive was created on October 28 at 20:40:47 EDT, October 29 in UTC.
Local evidence and checked line references are under
`artifacts/oppo-stereo-2946/`.

**The experimental switch reaches native SBS output, but usable 3D is not
confirmed.** The retained surface is already the correct size and Android
does not stretch it during composition. A native per-eye layout or projection
mismatch remains a hypothesis; this archive does not establish its exact cause.
No renderer correction is justified by this evidence alone.

## What the archive establishes

- `app.log:1491` starts the first phone-owned Unity session on display 100 at
  **1920 × 1080 / 90 Hz**, at 20:37:22.245. NRSDK reads the same size and
  refresh rate at line 2611. The provider explicitly reports **Run in mono
  mode** at line 2701; the later home configuration is `Mode_2D_90Hz` at
  line 2949.
- `app.log:20542` starts a new Unity process on display 104 at **3840 × 1080 /
  60 Hz**, at 20:40:19.503. Its final home configuration is
  **`Mode_3D_60Hz`** at line 22099. The preceding selector's `Mode_2D_90Hz`
  message at line 22094 occurs before the existing experimental tail filter;
  it does not show that the final home override failed.
- `display.txt:279–280` contains a real SurfaceFlinger hardware mode and active
  mode at **3840 × 1080 / 60 Hz**. Both the display's layer-stack rectangle and
  output rectangle are `[0,0–3840,1080]` at lines 255–256. The external input
  viewport also maps 3840 × 1080 logical pixels to 3840 × 1080 physical pixels.
  `FLAG_SIZE_OVERRIDE` alone does not negate this independent mode evidence.
- `surfaceflinger.txt:505–523` records the native renderer SurfaceView on that
  display, owned by the new `:space` process, with buffer **3840 × 1080**,
  content crop **3840 × 1080**, bounds **3840 × 1080**, and an **identity**
  layer transform. Its frame counter is 1516. These are submitted full-size
  renderer buffers, rather than a 1920-pixel window enlarged by Android. A
  frame counter and correct bounds cannot establish that the pixels inside
  each eye have the correct proportions.

The export still contains the 3D session. It does not capture the wearer's later
return to 2D, or establish whether the distortion affected the whole scene or
only application windows, or in which direction it stretched.

## Why the framebuffer error is not a diagnosis

Both sessions report `UnSupported`, error 4, for
`NativeRendering+InitSetFrameBufferMode`: `app.log:3415` in the mono session and
line 22525 in the 3D session. The mono startup continues through rendering and
the provider's mono-mode decision after the same error. It therefore cannot
by itself explain the 3D-only symptom.

The unmodified XR provider at `libXREALXRPlugin.so:0xC9814` first attempts the
framebuffer setting, starts native rendering, then calls
`NativeRendering::GetFrameBufferMode` at `0xC98A4`. That readback determines
the provider's mono/stereo branch. The optional home-selector filter is in
`libil2cpp.so` and does not replace this provider decision. Consequently,
`GlassConfig=Mode_3D_60Hz` does not independently prove which eye layout the
native provider selected.

At `app.log:21740`, OPPO explicitly reports **131 dropped log rows** during
the 3D renderer-start interval (`LOGS OVER PROC QUOTA(300)`). Other startup
intervals also lose rows. Unlike the first session, the second contains no
retained `GfxThreadStart`, native framebuffer readback, or `Run in stereo/mono
mode` result. Their absence cannot be treated as an absent renderer startup.
The later SurfaceFlinger buffer demonstrates that rendering did proceed.

## Relationship to the earlier blank-output report

This is the same phone model and glasses combination as the
[0.1.2 blank-output investigation](oppo-air2-pro-v012.md), but a different
failure stage. The wearer now sees content. The local release APK for build
2946 contains the previously audited GLES colorspace correction and the
repackaged experimental preference path. The new bundle does not repeat the
earlier `Failed to create window surface:12292` loop. Correct surface creation
does not establish correct stereo geometry.

## Next diagnostic target

Keep experimental 3D disabled for this affected combination until a physical
test confirms usable proportions. Preserve the model's default behavior.

The next diagnostic candidate should retain the provider's **final framebuffer
mode and eye layout** outside the throttled Android log, together with per-eye
render-target dimensions, viewport rectangles and projection/FOV values. It
must distinguish the SDK's automatic mode from the Unity home-mode setting;
forcing another display resolution cannot resolve the missing observation.
Also establish whether the distortion affects launcher UI and app windows
equally, whether it is horizontal or vertical, and whether both eyes agree.
Those observations distinguish a renderer-wide projection problem from an
application-surface aspect problem before changing either path.

The ZIP's SHA-256 is
`8838a035d787ad749c655590fb8c84fee933cff2ba1aaf6adda9f919912f20af`.
All ten recorded evidence anchors were checked against the extracted files.
The initial investigation changed documentation only; no hardware validation or
renderer fix is claimed.

## Diagnostic capture added after this investigation — build 2947

The next diagnostic build adds **`renderer-geometry.txt`** to the existing debug
ZIP. It reads Unity state directly on UnityPlayer's own job thread and saves
the result through a separate file worker, bypassing Android's logcat quota.
It requires both existing debug switches and samples every two seconds while
AR Space owns its Unity Activity. Unchanged geometry is retained every 30
seconds; geometry changes are retained immediately at the next sample.

Each sample contains the Android display sizes, Force 3D preference, initialized
home/current glass modes, mono-support setting, Unity stereo rendering mode,
eye texture size and scale, main-camera aspect/FOV and pixel rectangle. It also
records each live XR render pass's target size, array depth, render-parameter
count, per-parameter viewport, texture-array slice and projection matrix.
Matrices include their storage order and scale-aspect ratio. No head poses,
view matrices, screenshots or application content are captured.

The private native framebuffer enum has no public readback in this build. The
report explicitly distinguishes that unavailable value from Unity's observed
render-pass/parameter layout. Reading the actual layout can establish a
one-eye versus two-eye mismatch without assuming that a requested home mode
determines the native provider's result. This capture does not yet inspect the
SDK's later time-warp output or establish optical correctness in the glasses.

Storage retains the first sample and recent complete samples from up to four
renderer sessions, so one collection can include a mono baseline and a stereo
reconnect. The report is capped at 330 KB and reserves space inside the existing
15 MB ZIP limit. Turning collection off invalidates queued samples; starting a
new collection clears old renderer evidence. A stalled Unity queue gets an
explicit timeout record and cannot accumulate additional sampling jobs. Missing
metadata or managed exceptions are recorded as unavailable data. The original
SDK/Unity binaries, mode selectors, projection settings and render lifecycle
are unchanged by this instrumentation.

For the affected wearer:

1. Enable **Debug mode** and **Collect debug data** before connecting.
2. Enter AR Space with Force 3D off and leave it running for about ten seconds.
3. Leave collection on, enable Force 3D, reconnect, and keep the stretched view
   running for about ten seconds.
4. Create the debug file while the glasses are still connected. Then disable
   Force 3D and reconnect to restore the usable mode.

Host tests exercise mono, instanced stereo and multipass readback against
synthetic IL2CPP metadata, plus unavailable metadata and exception paths.
Storage tests cover opt-in/reset generations, stale jobs, restart, retention,
UTF-8 limits and concurrent export. The archive stress test includes the full
renderer budget alongside maximum existing diagnostics. An isolated ARM64
Android fixture exercises the production Java collector and JNI library with
synthetic Unity data, including debug-off behavior, both stereo layouts,
stopping, a stalled Unity job and rejection of an old collection's queued job.
These are diagnostic transport checks; physical OPPO validation is pending.

The signed diagnostic APK is
`artifacts/unbound-launcher-renderer-diagnostics-2947/the-unbound-launcher.apk`,
SHA-256
`ce52d99394358af8433f4e7f80053674bf98d6c492cb64bb3d99e8b0426db5ef`.
Its signing certificate matches public build 2946 for an in-place update.
`libXREALXRPlugin.so`, `libil2cpp.so` and `libnr_api.so` are byte-for-byte
identical to that release. The exact new JNI library from the signed APK also
passes the eight Android fixture assertions. Local evidence is under
`artifacts/renderer-diagnostics/`, including the fixture's exported geometry.
The full signed-APK audit passes, including package/version, signing/alignment,
unique classes, corresponding sources and exact restoration of the existing
native and DEX patches. Consolidated validation is in
`artifacts/renderer-diagnostics/validation.json`.
