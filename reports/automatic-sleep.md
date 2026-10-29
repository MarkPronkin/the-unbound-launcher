# Automatic sleep menu

Automatic sleep now opens in the shared native menu Activity with the Signature
OLED app bar, typography, flat divided rows, monochrome radio controls and fade
navigation. It is available in Experience while glasses are disconnected.

The original choices and SDK values are retained: 30 seconds, 5 minutes,
10 minutes, 20 minutes, 1 hour and Never (9000 seconds). G-series glasses use
the corresponding level indexes instead of seconds. The menu reads the current
hardware value when connected and no local choice is waiting to be applied.

Offline changes are saved in app preferences before any SDK call. A pending
choice is applied by the existing settings connection hook and cleared only
after the SDK accepts it. Failed writes and unsupported H-series 10/11 models
retain the choice for a later supported connection. The open menu updates on
connection changes and remains usable after a disconnect.

## Verification

Only `emulator-5580` received the preview APK. Nothing was installed on the Pixel.
Production settings sources compile in `tools/build_settings_ui.py --preview-only`.

- 55 targeted Android checks cover offline access, persistence, reopening,
  recreation, accessible radio semantics, both SDK mappings, reconnect,
  external hardware changes, rejected writes, exceptions and unsupported models.
- 19 existing settings checks and 47 native menu navigation checks pass.
- UI interaction confirms the offline choice survives force-stop and a fresh
  process. The screenshots were captured from the running emulator.

The emulator uses a fixture implementing the audited SDK method contracts;
physical glasses behavior has not been tested in this change.

Evidence: `artifacts/sleep-menu-ui/`, including `sleep-checks.log`,
`SettingsUiChecks.log`, `MenuPageChecks.log`, `automatic-sleep-offline.png` and
`settings-disconnected.png`. Build command:

```sh
python3 tools/build_settings_ui.py --preview-only --output artifacts/sleep-menu-ui
```
