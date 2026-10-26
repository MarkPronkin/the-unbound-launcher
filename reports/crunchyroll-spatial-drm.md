# Crunchyroll in the OnePlus spatial desktop — investigation, 2026-10-25

The current ADB-assisted port cannot provide the secure virtual display used by
stock Beam Pro playback. This is a confirmed privilege barrier, not an HDCP
configuration problem. Preserving the existing spatial desktop faithfully would
require an authorized system component to supply that display, plus validation of
XREAL's protected rendering path on the OnePlus.

A different design that lets Android's compositor place protected app layers
directly on the physical glasses display is worth a small rootless experiment.
It has not been implemented or shown to work on the OnePlus. It would bypass
Unity's texture-rendering stage while preserving buffer protection, and may only
support a restricted, flat movie-window mode.

## Scope and live reference results

The OnePlus was offline. At the user's request, live testing used Beam Pro
(X4000, Android 14) with XREAL One. Saved OnePlus measurements are from the same
day. The user started Crunchyroll in Beam Pro's AR Space and confirmed playback.
Crunchyroll reports version **3.126.0** on the reference device.

| Measurement | Result and evidence |
| --- | --- |
| Widevine | `securityLevel=L1`; [capability query](../artifacts/drm-investigation/beam-pro-live/capabilities.json) |
| HDCP | Maximum and connected levels both report **2.3** while the glasses are attached |
| Physical glasses display | Secure, supports protected buffers, 3840 × 1080 stereo |
| Crunchyroll's app display | Virtual display 20, owned by My Glasses **UID 1000**, with `FLAG_SECURE` and `FLAG_SUPPORTS_PROTECTED_BUFFERS`; [playing display dump](../artifacts/drm-investigation/beam-pro-live/playing-display.txt) |
| Decoder | Active `c2.qti.avc.decoder.secure` resources; [resource dump](../artifacts/drm-investigation/beam-pro-live/playing-resources.txt) |
| Protected buffers | Crunchyroll's video SurfaceView and the final 3840 × 1080 stereo SurfaceView both have `hasProtectedContent=true`; [playing compositor dump](../artifacts/drm-investigation/beam-pro-live/playing-surfaceflinger.txt) |
| Rootless graphics capability | A shell process successfully created and made current a protected EGL context and protected 1 × 1 pbuffer. Attribute readbacks are included in the capability report. This is an empty graphics test, not playback. |
| Shell privilege | UID 2000 lacks `CAPTURE_SECURE_VIDEO_OUTPUT`, including on Beam Pro |

The idle snapshot had no protected layers; the playback snapshot had the two
protected layers above. This is evidence of the working protected output chain.
It does not independently trace every intermediate buffer or prove that the
OnePlus drivers support the same path. HDCP API readbacks are diagnostic;
Android's DRM system enforces the actual license output policy.

## What the extracted implementation shows

Stock `LaunchManager.createVirtualDisplay` uses **1485 / 0x5CD**. The port's
`tools/PatchLaunchDex.java` changes that to **1481 / 0x5C9**, removing only
`VIRTUAL_DISPLAY_FLAG_SECURE`. Saved OnePlus tests rejected the original secure
mask and successfully rendered ordinary app fixtures with the modified mask.
See the [porting assessment](porting-assessment.md) and
[final contract test](oneplus-15-bridge-contract-final.json).

Beam Pro's My Glasses package runs under the system shared UID, **1000**. Our
patched app runs under its own UID and delegates window operations to **shell
UID 2000**. Neither copying the APK nor starting its helper through ADB transfers
the system UID's privileges.

The stock software already contains more than an ordinary texture-import path:

- `EvaProOverlayFactory.CreateUIOverlay` accepts `isProtectedContent`.
- Both native app-window implementations have `CheckAppHasProtectContent` and
  `CreateTemporaryNROverlay` methods.
- The app-launch listener has `onDrmSupport(packageName)`.
- `ProtectSessionService` starts a separate `ProtectedCodec`; its fallback sink
  uses a PRIVATE ImageReader with usage **16384 / `USAGE_PROTECTED_CONTENT`**.

These references are in the saved IL2CPP metadata dump and the newly extracted
[protection classes](../artifacts/drm-investigation/stock-protected-selected/ai/nreal/protect/session/).
They establish that a protected rendering route exists in the original software.
They do not establish that the port currently selects it for Crunchyroll or that
it will initialize correctly on OxygenOS. The additional protection service is
separate from Crunchyroll's own decoder and is not a replacement for its license.

Simply importing a protected HardwareBuffer into our ordinary Unity texture
path is insufficient. Android supports protected textures, contexts, surfaces,
and GPU processing for XR, but those protections have to extend through the
rendering chain and output. [AOSP protected texture playback](https://source.android.com/docs/core/graphics/arch-st#secure_texture_video_playback)

## Rootless options and their limits

| Route | Assessment |
| --- | --- |
| Restore secure flag 1485 in the existing helper | Already rejected on OnePlus. A protected EGL context does not grant secure-display permission. |
| ADB permission grant, Shizuku, or ordinary APK manifest changes | Do not supply a platform signing identity or an authorized secure-display permission. Shizuku in ADB mode would have the same relevant shell privilege limit. |
| MediaProjection or companion app-streaming display | No secure-capture solution identified. Android 16 still checks secure-display permission separately. AOSP MediaProjection's `canProjectSecureVideo()` returns false. |
| Android 16 role grant | The permission is `signature\|role` in AOSP. The role definition granting it is `SYSTEM_AUTOMOTIVE_PROJECTION`, marked static and system-only. This is not an ordinary user-grantable route for our app. OxygenOS's exact role implementation has not been collected. |
| Crunchyroll in a browser window | A low-effort compatibility test. It would help only if that browser's actual licensed playback path can render on our display. Supported browser status does not guarantee this; no playback test or resolution claim has been made. |
| Protected task layers on the physical display | A plausible rootless research direction. It needs a compositor prototype and OnePlus playback tests; it is not a fix demonstrated by this investigation. |
| OEM/system integration | The direct route to retaining the current secure-virtual-display architecture without rooting the user's phone. It needs an authorized system service and protected graphics support, rather than a distributable APK alone. |

Android 16 source inspection verified the secure virtual-display check, the
MediaProjection result, permission protection level, and role definition. The
downloaded release source and URLs are recorded in
[sources.json](../artifacts/drm-investigation/aosp/sources.json).
The saved runtime OnePlus denial remains the stronger evidence about this phone.

## What a compositor experiment would require

The saved OnePlus shell package has `ACCESS_SURFACE_FLINGER`,
`READ_FRAME_BUFFER`, and `MANAGE_ACTIVITY_TASKS`. Android's source provides
`SurfaceControl.mirrorSurface`, WindowManager display mirroring, task-organizer
surface leashes, and transactions for position, crop, and affine transforms.
These are the ingredients for a prototype, not proof of DRM compatibility.

The experiment would keep the app's decoded buffers protected and have the
system compositor place them directly on the secure physical glasses display.
It would not read back the video or send its pixels into an ordinary Unity
texture. A source task on a real protected display may be necessary: starting
from our existing non-secure virtual display can already cause playback to fail.

To coexist with the present stereo desktop, this would need separate placement
for the two eyes, correct window ordering and clipping, input coordinate mapping,
and clean task/window restoration when the movie closes or glasses disconnect.
Task-organizer registration can affect existing system task management, so the
prototype must establish isolated ownership and restoration before using it for
the desktop.

SurfaceControl's ordinary transform is affine. It does not directly reproduce
the XR renderer's perspective, curved surfaces, or reprojection. An initial
successful result could therefore be a flat movie window with reduced spatial
behavior. Full anchored-window parity would require further rendering work even
if protected playback succeeds.

## Recommended next tests, in order

1. Run the new capability query on OnePlus with the glasses attached. Confirm
   the connected HDCP level and protected EGL context/surface support. Its saved
   physical display already advertises secure/protected-buffer support, but that
   is not a measurement of the negotiated HDCP level.
2. Compare the same Crunchyroll episode on the phone, on the normal physical
   glasses display, and in the current spatial window. This distinguishes an
   external-output or app restriction from the port's capture/rendering problem.
3. Try normal licensed browser playback in an existing spatial browser window.
   A browser success would offer the smallest practical solution. A failure is
   not proof that every browser or playback policy behaves identically.
4. If native app playback remains required, prototype direct compositor placement
   with our own protected test content, then test Crunchyroll on the physical
   output. Require two-eye visibility, preserved protection, input, and reliable
   cleanup before integrating it with the desktop.
5. For faithful stock-style spatial playback, obtain an authorized secure-display
   provider and validate XREAL's existing protected-overlay selection, auxiliary
   protection service, output buffers, and reconnect lifecycle on OnePlus.

## Diagnostic and changes made

[tools/inspect_drm.py](../tools/inspect_drm.py) builds and runs
[DrmCapabilities.java](../tools/drm/DrmCapabilities.java) through ADB shell. It
queries public Widevine properties, HDCP readbacks, display flags, permissions,
and ordinary/protected empty EGL contexts. It requests no content licenses,
reads no DRM device-unique identifier, and does not capture screens or media.

Example for an already connected device:

```sh
python3 tools/inspect_drm.py --server-port 5038 --serial DEVICE_SERIAL --output artifacts/drm-investigation/oneplus-live/capabilities.json
```

The Java query compiled and executed on Beam Pro. No production APK, helper
build, device permissions, roles, firmware, or bootloader state was changed.
A temporary diagnostic JAR was uploaded to `/data/local/tmp/`; the query exits
after completing and leaves no running helper. There is no installed Crunchyroll
fix from this investigation. Machine-readable results are in
[findings.json](../artifacts/drm-investigation/findings.json).
