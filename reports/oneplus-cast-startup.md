# OnePlus automatic glasses output — 2026-10-26

The user reports pressing the phone's **Cast** confirmation twice when connecting
XREAL glasses on the OnePlus 15. Read-only inspection confirms OxygenOS on
Android 16 / API 36, installed launcher 0.1.7 / 2881, and shell permission
`MANAGE_DISPLAYS` granted. Captures are in
`artifacts/oneplus-cast-startup-20261026/`.

The copied OnePlus `IDisplayManager` exposes `getDisplayIds(boolean)` and
`enableConnectedDisplay(int)`, but no `getConnectionPreference` or
`setConnectionPreference`. Its preferred-mode setter has two arguments, so the
helper correctly excludes the Pixel's non-persistent mode-management path.
However, it also gated all enablement on successful Desktop preference setup.
That gate prevented automatic enablement on this phone.

In the copied SystemUI, `OplusConnectingDisplayExImpl` shows a confirmation
without a remember-choice checkbox. Its positive action calls the pending
display's `enable` operation. The existing helper log identifies the glasses in
1920 × 1080 mono on display 3, then 3840 × 1080 stereo on display 4. These two
connections explain the two separate confirmations.

Version 0.1.13 enables identified physical XREAL displays when the framework
supports connected-display enablement but lacks the Desktop preference API.
It uses fresh direct service snapshots and the existing lock and thermal guards.
It enables once per observed connection and retries a failed operation through
normal helper recovery. Other monitors and virtual displays are excluded. This
legacy path never changes settings or mode policy. Pixel behavior is retained.

Sixteen targeted host tests and the signed APK payload audit pass. The production
DEX audit verifies that the policy actually calls the new fallback. See
`artifacts/unbound-launcher-auto-cast/verification.json`.
The new tests run the actual adapter and policy
against the Android 16 interface, with 21 connection assertions, plus unsupported
API and exact copied-framework checks. Existing Pixel policy, recovery, startup
DEX and pairing-manifest regressions also pass.

The candidate is
`artifacts/unbound-launcher-auto-cast/the-unbound-launcher.apk` (0.1.13 / 2887).
After receiving this candidate and the install/reconnect instructions, the user
confirmed “yeah it works”. Automatic startup on the OnePlus 15 is therefore
wearer-confirmed. The evidence is the user's report; no new hardware trace or
resolution measurement was collected. Reboot recovery and repeated reconnect
cycles were not separately reported. AR Space itself remains a user action.
See `artifacts/unbound-launcher-auto-cast/runtime-verification.json`.

The agent did not modify device settings, display policy, installed apps or the
running helper during the initial read-only investigation.
