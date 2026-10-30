# OPPO CPH2791 / Air 2 Pro: experimental 3D investigation

## Follow-up: 0.2.2 / build 2953, October 29

The follow-up archive `unbound-debug-20261029-181803-f27e01b7 (1).zip`
identifies **0.2.2 alpha, build 2953**, on the same OPPO CPH2791 / Android 17
and Air 2 Pro (SDK model 3) combination. It was created at **14:18:03 EDT
(18:18:03 UTC) on October 29, 2026**. The report says only that 3D is not
working properly; the wearer did not describe the visual symptom. Do not
assume this session repeats the earlier stretching, affects the whole scene,
or fails only inside app windows. Extracted evidence and file hashes are in
`artifacts/oppo-stereo-2953/`.

**The new readback confirms a live two-eye Unity layout. It does not establish
correct final composition or optical output, and does not yet justify a
renderer correction.**

### What the new capture establishes

- `app.log:50929` starts the phone-owned Unity session, PID 25788, on
  display 126 at **3840 × 1080 / 60 Hz**, at 14:17:14.282. The Java SDK
  requests legacy mode 3 at line 50472; the MCU reports a successful switch
  to mode 3 at line 50491. Unity's final home configuration is
  `Mode_3D_60Hz` at line 52426. These are application/SDK observations;
  this export does not retain a connected hardware-mode dump.
- `renderer-geometry.txt:74–116`, sampled at **14:17:20.370 EDT**, records
  initialized current/home modes of 3, active XR, and a **1968 × 1134
  two-slice texture array**. Its single render pass has **two render
  parameters**, using slices 0 and 1 with full normalized viewports. The
  per-eye projection scale-aspect ratios are **1.742510** and **1.737568**;
  the camera/target aspect is approximately **1.73545**. This directly
  resolves the previous uncertainty about whether Unity supplied one or
  two eyes. It does not show an obvious factor-of-two aspect error in the
  sampled eye targets or projections. A normalized `[0,0,1,1]` viewport
  spans its texture-array slice; its reported ratio of 1 is not a square
  eye texture.
- `renderer-geometry.txt:163–206`, sampled at **14:17:42.407 EDT**, retains
  both eyes and their projection ratios after the SDK changes resolution
  scale to 0.8. The target becomes **1574 × 907**, preserving approximately
  the same aspect. This later scaling does not by itself explain a distorted
  image. Display 127 at 1920 × 1080 appears after an app opens; it coexists
  with glasses display 126 and is not evidence of a return to mono output.
- `immersive-windows.txt:19–30` preserves a connected SDK Presentation
  readback at **14:17:40.526 EDT**: **3840 × 1080**, display 126, preferred
  mode 140, no fit insets, and system bars reported hidden. This establishes
  the client window's dimensions, not its submitted buffer, SurfaceFlinger
  transform, or optical proportions.
- `glasses-startup.txt:112` reaches `event=ready` at 14:17:19.909. Native
  frame metrics continue until the disconnect. There is no retained fatal
  exception or native fatal signal in this attempt. Startup readiness and
  frame metrics do not establish that the wearer sees a usable scene.

### Evidence that must not be attributed to this attempt

The 10.5 MB app log retains older October 28 sessions alongside the new one.
Its `composition shader error!` and vertex-stage storage-block link errors
belong to an older PID 30898 session. None of those messages is retained in
the October 29 14:17 attempt. They are not evidence of a compositor shader
failure in this new test. Log throttling still drops startup messages,
including 207 rows at `app.log:52682`, so the missing native framebuffer
readback remains unavailable rather than a confirmed successful result.

`NativeRendering+InitSetFrameBufferMode` still reports `UnSupported`, error 4,
at `app.log:52810`. The earlier mono session also reported that error, and
this session subsequently supplies two Unity eyes. That error alone is not
a diagnosis of the visual problem or proof that Unity remained in mono.

The wearer **unplugs at 14:17:46.389** (`glasses-startup.txt:118`), before
the 14:18:03 export. `device-and-session.txt`, `display.txt`, and the active
SurfaceFlinger display list therefore contain only the phone display. The
last renderer sample is also shutdown state. These must not be read as a
stereo-startup failure. Unlike the earlier 2946 export, this archive cannot
establish the glasses layer's final buffer/crop/transform while connected.
There is also no initialized Force-3D-off baseline in the retained geometry.

### Remaining diagnostic target

The earlier one-eye-versus-two-eye uncertainty is resolved on the Unity side.
The native compositor's final per-eye output mapping and the visible symptom
remain unknown. Final composition, an app-surface issue, and a headset/link
issue remain possibilities; none is established as the cause. Do not change
projection, FOV, eye-texture size, or the physical mode based on this capture
alone. This investigation changes documentation only; no fix or new APK is
claimed, and updating to 0.2.3 is not a validated correction for this report.

The useful next reproduction is a short description of the symptom and a
debug export **while the bad 3D view is still running and the glasses are
connected**, preferably after collecting a Force-3D-off baseline in the same
collection. A description should distinguish the launcher from app windows,
stretching from doubling/blank output, and one eye from both. Native output
viewport/framebuffer readback is the next instrumentation target if the
connected dump still leaves composition ambiguous. Until there is a confirmed
correction, keep Force 3D off on this affected combination and reconnect.

The follow-up ZIP's SHA-256 is
`93cd654d042a6b5d522d6c95f13911484449844e4d0e40cfed78644ed354f2cf`.

## Original report: stretched 3D on 0.2.0 / build 2946

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
