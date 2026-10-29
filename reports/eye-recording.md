# XREAL Eye recording duration

Development build **2954** removes the launcher's fixed video-recording time
limit. The recording indicator shows elapsed time instead of a countdown.
This is a local development build following 0.2.2 alpha's release build 2953;
the public release package is unchanged.

## Recording and storage

The launcher uses `VideoRecordTool` and the bundled XREAL SDK to capture the
camera feed and encode video on the phone. `VideoRecordTool.SdcardSavePath`
combines Unity's `Application.persistentDataPath` with the video filename.
After recording ends, `VideoRecordView.InsertVideo` passes the finished file
to `NativeGalleryDataProvider.InsertVideo` for insertion into the phone's
gallery. These findings establish the code path; gallery visibility and saving
were not separately described in the user's hardware confirmation.

The deadline belongs to `NREAL.HOME.RecordStatusBar`, shared by Eye camera
recording and AR screen recording. Its default duration is 120 seconds. `Begin`
used to add that duration to the current monotonic clock, and `Update` invoked
the finish callback when the remaining time reached zero. The same code now
records the start time, calculates elapsed time, and skips that deadline callback.
It does not substitute a larger finite limit. Both recording modes inherit the
change.

## Patch scope

`tools/recording_duration.py` changes three ARM64 instructions in the pinned
Unity library, after the existing color/tutorial/stereo/surface patches and
before package-identity rewriting:

| Address | Change |
| --- | --- |
| `0xF0A07C` | Omit adding the configured duration to the start time. |
| `0xF0A0C8` | Calculate `now - start` instead of `deadline - now`. |
| `0xF0A0D4` | Skip the automatic deadline callback. |

The existing elapsed-time formatter, blinking recording indicator, manual Stop
event, encoder, save path and interruption cleanup are preserved. The builder
and independent signed-APK audit verify the patch and restore its exact input
before auditing the earlier native changes. Unknown or partly modified input
libraries are rejected.

## Validation

All **226 host regression tests pass**, with no failures or skips. The local
APK is in `artifacts/unbound-launcher-eye-recording-2954/`; it passes signature
and 16 KB ZIP alignment checks and uses the same signing certificate as release
build 2953, allowing an in-place update.
The independent signed-APK audit also passes, including exact restoration of
the recording patch and all earlier scoped DEX/native changes.

The host regression executes the real ARM64 `Begin`, `Update` and `Stop` code.
The unmodified negative control invokes the stop callback after 120 seconds.
The patched timer keeps updating at 119, 120 and 121 seconds and at one and
three hours, with no deadline callback. It also checks different configured
durations, a second recording's reset, the blinking indicator, hiding the status
on Stop, register preservation, exact binary scope and rejection of unrelated
changes.

Run the focused checks with:

```sh
python3 -m unittest discover -s tools -p 'test_recording_duration.py' -v
```

On **2026-10-29**, the user reported confirmation that the XREAL Eye recording
time-limit change works. Phone/glasses models, firmware, recording duration,
individual Stop controls and gallery playback were not separately reported.
This confirms the recording-limit change without establishing those additional
details. Available phone storage, battery, temperature and camera/encoder
failures can still end a recording; this patch removes the launcher's fixed
time cutoff.
