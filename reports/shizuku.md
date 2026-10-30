# Shizuku connection option

Unbound can start its bundled AR helper through
[Shizuku](https://shizuku.rikka.app/) instead of its own on-device ADB pairing. The
choice appears during setup and under **Settings → AR connection**. Existing
installations stay on ADB. Switching methods keeps ADB pairing data and app settings.

## Use

1. Start Shizuku 13 or newer through Wireless debugging or root.
2. Select **Shizuku** on Unbound's connection screen.
3. Tap **Allow Shizuku and start**, approve Shizuku's dialog and wait for **Ready**.
4. Start Shizuku again after a reboot. Unbound remembers the method and reconnects
   when permission has already been granted.

Shizuku replaces ADB pairing only. Overlay, notification, camera and other ordinary
permissions stay in the Android permission setup.

To revoke access, disable Unbound in Shizuku's authorized apps and force stop
Unbound. The helper is a separate shell process; force stopping Unbound closes its
provider and the helper exits through the existing death link.

## Implementation

- Official [Shizuku API](https://github.com/RikkaApps/Shizuku-API) 13.1.5: pinned
  Maven artifacts in `tools/shizuku_deps.py`, MIT license included. It uses
  UserService, not `newProcess`.
- `ShizukuStarterService` is a temporary UserService with one fixed command. It takes
  no command or path from callers and accepts calls only from this app's UID.
- `HelperStarter` holds the startup command shared by both methods: extract this
  APK's helper, verify its SHA-256, start `LaunchServer`.
- The helper and its provider require shell UID 2000. With root-backed Shizuku, the
  starter runs the command through `su 2000 -c`, and the child checks `id -u` before
  extracting or launching anything.
- Denied permission, missing or stopped Shizuku, unsupported identity, starter
  disconnection and timeout show recovery text. Selecting Shizuku never falls back to
  ADB discovery or pairing.
- Readiness comes from the helper's Binder provider, so a Shizuku grant alone cannot
  complete setup. The UserService is removed after readiness or failure.
- `tools/shizuku_manifest.py` adds the permission, protected provider, meta-data and
  package query. `build_embedded_adb.py` applies it to the release manifest.

## Screen mirroring

`Start in screen mirroring` decides what a new connection does. Settings also shows
glasses as connected when the SDK has not started, because mirroring deliberately
skips SDK startup.

While mirroring, the main button reads **Start AR Space**. The SDK hides its own
button and reads `castScreen` throughout startup, so
`GlassesConnectionCompat.enterArOnce` lifts that flag and records
`unbound-mirroring-restore`. Settings keeps showing the choice as On, and the next USB
attach restores it. Changing the switch cancels the restoration.

## VPNs

A VPN that forbids bypass also rejects `bindProcessToNetwork` for the glasses
network. Such a VPN must exclude Unbound, or the native client cannot reach the
glasses.

## Validation

`python -m unittest discover -s tools -p test_shizuku.py` covers the binary manifest:
preservation of existing declarations, provider protection, attribute order,
idempotence, and adding queries to a minimal manifest. It reads the pinned baseline
`BASE` from `build_embedded_adb.py`.

Hardware: OnePlus CPH2653, Android 16, XREAL One, root-backed Shizuku 13.6.0 under
KernelSU, with a non-bypassable VPN that excludes Unbound. See the pull request for the
exact build tested. The full `build_embedded_adb.py` route has not been run for this
change because its pinned baseline and macOS toolchain were not available; the
tested APK was assembled from the public release's payloads.
