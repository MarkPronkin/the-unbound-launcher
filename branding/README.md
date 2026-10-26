# The Unbound Launcher artwork

- `logo.png`: default Dot Portal horizontal logo, black artwork on transparency.
- `logo-dark.svg`: white horizontal logo for dark backgrounds; a self-contained
  SVG embeds `logo.png` and recolors its artwork while preserving its exact alpha
  and geometry. The root README selects it automatically for dark mode.
- `app-icon.png`: default full square Android launcher icon, white dots on true black.
- `symbol-white.png`: transparent white Dot Portal mark for the adaptive foreground.
- `symbol.png`: original transparent black mark, also tinted white in the phone UI.
- `generation.json`: the complete prompts and generation method for all three assets.

The approved default is **Dot Portal**, selected from the
[industrial black-and-white concepts](options/industrial-bw/README.md).
Created with the built-in ImageGen tool, the open U uses circular dots and a rising
right stroke. The wordmark reads “THE UNBOUND LAUNCHER”. The
[previous blue ribbon artwork](archive/blue-ribbon-2026-10-25/README.md) is archived.

The OLED variant preserves the approved dot geometry, with its colors reversed.
The previous white icon is in `archive/dot-portal-light-2026-10-26/`; the original
generation prompts remain in `generation.json`. See `dark-variant.json` for the
color-variant record.

`tools/branding.py` uses Pillow, macOS `sips`, and `cwebp` to package five icon densities.
Adaptive foregrounds use transparent padding and a separate black `#000000` background;
the mark stays within Android's safe area. The builder retains the original
adaptive XML, resource IDs and unrelated resources, and changes the app-name
resource in every locale. Source package names, saved preferences and application
ID retain their existing values to support updates to the installed port.

Build the signed app with:

```sh
python3 tools/build_settings_ui.py
```

The output is `artifacts/unbound-launcher-model-aware-default/the-unbound-launcher.apk`. The existing
preview fixture remains a separate app named “The Unbound Launcher Preview”.

All rights to the original XREAL software remain with XREAL. This project is fan-made.
