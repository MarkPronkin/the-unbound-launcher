# Build 2971 app-launch crash in AR Space

On 2026-11-11, the wearer reported that opening an app from the AR launcher
closed AR Space on a OnePlus 15 running development build 2971. Wireless ADB
crash logs contain repeated `SIGSEGV` failures in `dev.markpronkin.unbound:space`
on UnityMain. The installed package reports 0.2.5 alpha, version code 2971.
Raw logs remain local under `artifacts/app-launch-crash-2971/`.

## Cause

The revised immersive 3D picker updates its Half SBS and Full SBS labels while
creating app-window controls. `mode_label` loaded the string-literal metadata
slots and passed their contents directly to `Localization.GetText`. These slots
can still contain encoded IL2CPP metadata tokens before the stock hover handler
initializes them. Neither app-window creation nor the patch's label refresh
ensured that initialization happened first.

Repeated crash stacks lead through the injected label refresh at native offset
`0x12cfe40` or `0x12cfe5c`, then `Localization.GetText`, then
`String.IsNullOrEmpty` at `0x1a28644`. The faulting string argument is
`0xa000b005` or `0xa000b003`, with a read at token + `0x10`. This directly
identifies an unresolved metadata token being used as a managed string pointer.
It is separate from app rendering, the ADB helper and the new experimental
photo-sound/screen-off preferences.

## Correction and regression

The label routine now calls the existing IL2CPP metadata initializer on the
selected literal slot before dereferencing it and calling localization. Its
wrapper preserves the slot and return address across that call. The native
payload digest is updated; the pinned baseline and patch locations stay the same.

A new ARM64 execution regression starts with both labels unresolved, using the
same token values captured in the crash. It exercises app-window menu creation
and update for mono, Half SBS, Full SBS, Half Top/Bottom and the migrated old
Full Top/Bottom selection. The localization boundary rejects unresolved tokens.
The regression fails on the previous code and passes after the correction.
Existing picker, saved-format, shader and exact patch-restoration checks pass.

The earlier fixture preinitialized these labels and therefore missed the cold
metadata state. The strengthened fixture now requires initialized strings at
every localization call.

## Hardware retest

The corrected **0.2.6 alpha / build 2972** APK was installed on the OnePlus 15
as an in-place update from 2971. On 2026-11-11, the wearer entered AR Space,
opened an app from the launcher and confirmed: **AR Space stays open**. The
renderer was running when checked afterward, and the crash buffer contains no
new crash since installation. The physical display reports XREAL One output.
The local `hardware-retest.json` records the result and the tested build.

This confirms the reported app-launch regression is fixed on that combination.
It does not newly verify every immersive format, repeated reconnects, other
phone/glasses combinations or the other experimental controls. A separate visual
check of all three immersive choices and Close remains pending.
