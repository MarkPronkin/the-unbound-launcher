# Firmware gate

Implemented for build 2948. Physical glasses validation is pending.

The connection adapter checks firmware immediately after USB access and model
identification, before SKU queries, Ethernet readiness, native SDK initialization,
SDK display-mode commands or AR launch. The existing OTA manager supplies read-only
USB firmware queries. Firmware flashing and boot-mode entry remain disabled.

`FirmwarePolicy` pins each model's stable components to the public XREAL updater
response checked on 2026-10-29. The source endpoint is
`https://app-api.xreal.com/api/nebula/v1/isc/device/package?packageName=ai.nreal.web&hardwareCode=MODEL&versionCode=1`.
The updater's public JavaScript calls this endpoint without beta enrollment.
Matching is exact, including software build timestamps and prerelease suffixes;
only filename wrappers, terminal padding and an omitted MCU/DSP packaging date
are normalized. Recommendations need an explicit source update for future releases.
There is no network dependency during connection.

For One, One Pro and 1S, the recommended Pilot/software version is
`1.9.1.20260914175625`, with model-specific MCU/DSP components. A model whose
official stable version is 2.0.0 (currently native model 20, XREAL 1 Pro+) has
its own profile. Version 2.0.0 is not classified as beta across all models.
Air-family profiles check MCU, DP and DSP. Native models 10/11 also check LUT
and OSD. Unknown models, including experimental ROG R1, require an explicit
override rather than inheriting the One Pro recommendation.

The warning includes the model, actual mismatching component, recommended
version, official updater link, prominent Exit button and small override text.
A read that exceeds 15 seconds also opens the warning. The override remains
disabled until the native read completes, preventing overlapping commands.
Exit works immediately, and a late response cannot resume a cancelled session.
Waiting at the warning does not expire the normal startup deadline. Duplicate
USB callbacks, normal retry controls and asynchronous AR launches cannot dismiss it.

The override is stored only in the connection session. A new connection checks
again. The SDK's deliberate MTP/USB reconfiguration remains part of the same
connection and preserves the decision for that model's USB boot/app identity
pair. A changed device during a firmware read or before an override cannot
inherit the result. Disconnecting closes the paused screen.

Validation uses the production adapter with simulated USB/native callbacks,
per-model firmware policy tests, Android runtime view checks, and a packaged DEX
audit that checks the gate precedes SKU/SDK startup. These checks do not establish
that each hardware family's firmware reader behaves identically on real glasses.
