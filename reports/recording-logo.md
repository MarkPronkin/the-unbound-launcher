# Video recording logo removal

Development build **2961** disables the launcher's branded overlay in new Eye
camera and AR screen recordings. It follows the current local development
changes; public 0.2.3 alpha build 2957 is unchanged. Previously saved videos
retain their existing pixels.

## Implementation

`VideoRecordView.Start` selects a model logo, replacing it with
`m_WaterMarkEyeLogo` when camera blending is supported, and passes that sprite
to the video capture builder. `CaptureToolBase.SetLogo` already returns without
constructing an overlay when the capture tool's sprite is null.

`tools/recording_logo.py` changes the video builder's sprite argument at
`0xF0B22C` from `mov x1, x24` to `mov x1, xzr`. The existing shared builder
copies this empty sprite into the video capture tool, so the original
null-sprite path skips overlay construction. This also omits the model logo in
AR screen recordings. Photo capture, shared logo-rendering code and the logo
assets are preserved. Encoding, audio, resolution, blending, elapsed-time
display, manual Stop and gallery insertion retain their existing code.

The four-byte instruction patch runs after anchored-window placement and
before package-identity rewriting. It pins the full input library hash, checks
the original instruction and supports exact restoration. The builder bundles
the patch source and verifies the packaged instructions. The independent
signed-APK audit restores this patch before auditing the earlier native
changes, rejecting missing, repeated or unrelated modifications.

## Validation

All **244 host regression tests pass**, with no failures or skips, including
the four focused logo checks. These execute the real ARM64 Eye/model
selection, video sprite argument setup, shared builder setter, sprite transfer,
`StartRecordingAsync` virtual logo dispatch and logo renderer's null check and
return. The stock negative control reaches
overlay construction with both the Eye sprite and a model sprite. Patched
videos return without constructing that overlay, retaining scale handling,
callee-saved registers and stack state. A populated sprite still reaches the
shared renderer's construction path, preserving the photo path. Exact binary
restoration and composition with all preceding native patches and package
identity are also checked.

Run the focused checks with:

```sh
python3 -m unittest discover -s tools -p 'test_recording_logo.py' -v
```

The local APK is built under
`artifacts/unbound-launcher-no-recording-logo-2961/`. Its signature and 16 KB
ZIP alignment checks pass, and its signing certificate matches public build
2957 for an in-place update. Comparing its Unity library with the earlier local
anchored-window build 2960 confirms that only the video-logo instruction differs.
`build.json`, `certificate-comparison.json` and `native-comparison.json` in that
directory record these checks.

The independent signed-APK audit also passes, including bundled patch-source
verification and exact restoration of the full native library to its pinned
baseline. `verification.json`, `host-tests.json` and `validation.json` record
the completed audit and regression results.

Physical Eye recording and gallery playback validation are pending. The G4
emulator is reserved for another agent and was not accessed for this work.
