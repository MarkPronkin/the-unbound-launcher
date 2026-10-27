# Inline terms agreement

Build 2902 adds a third onboarding step after permissions and on-device ADB.
The Ready screen now offers **Continue**. It opens **Terms of service** in the
same Signature OLED interface, with the existing XREAL terms and privacy-policy
links and a pinned **Agree and finish** action. Agreement proceeds directly to
the launcher. The initial flow no longer enters the original half-window legal
prompt or requires the launcher icon to be opened again.

`PermissionSetupActivity` owns the new page. `TermsConsent` reads and commits
the original `nebula_app` acceptance record, then invokes the original
`PPToSHelper.firstAgree()` and application agreement-initialization callbacks.
The original document resolver and later policy-version checks remain in use.
Existing accepted installations skip the page. Viewing documents, leaving the
screen, and Activity recreation do not count as agreement. A pending marker
keeps an interrupted acceptance callback from completing onboarding prematurely.

Automatic USB entry waits for agreement and resumes after the final handoff.
`SetupNavigation.launcher()` restores the normal MAIN action for a phone launch
without restoring launcher task flags. Actual USB actions and their payloads
continue through setup unchanged.

## Validation

- Host navigation tests cover launcher flags, final MAIN restoration, USB payload
  preservation and reflected launch failures; binary manifest tests also pass.
- `tools/verify_terms_setup.py` exercises disclaimer → permissions → ADB → terms
  → launcher using an actual shell helper in an isolated Android 13 emulator.
  It checks interruption/recreation, document routing, explicit acceptance,
  persistence, existing acceptance, and later permission reviews.
- The verifier captures 360 × 800, 360 × 640 at 200% text, 640 × 360 at normal
  and 200% text, and 960 × 600 layouts. Documents scroll independently of the
  pinned agreement action. The progress indicator uses one full-width current
  step label so long labels do not split across narrow columns.
- A fresh install of the full launcher on the emulator reached the new terms
  screen after the live helper became ready, then entered `SettingActivity`
  through the real original acceptance callbacks. No legacy terms popup or
  Android runtime exception appeared. Raw local evidence is under
  `artifacts/terms-setup/`; the candidate APK is under
  `artifacts/unbound-launcher-inline-terms-2902/`.

The physical phone installation and glasses were not changed for this work.
