# The Unbound Launcher artwork

- `logo.png`: transparent horizontal logo with dark lettering for light backgrounds.
- `app-icon.png`: full square master for the Android launcher icon.
- `symbol.png`: transparent standalone mark and adaptive foreground source.
- `generation.json`: the complete prompts and generation method for all three assets.

Created with the built-in ImageGen tool. The open blue-to-cyan U ribbon has a rising
right stroke, suggesting an open portal and launch into space. The wordmark reads
“THE UNBOUND LAUNCHER”.

`tools/branding.py` uses macOS `sips` and `cwebp` to package five icon densities.
Adaptive foregrounds use transparent padding and a separate `#000A2A` background;
the mark stays within Android's safe area. The builder retains the original
adaptive XML, resource IDs and unrelated resources, and changes the app-name
resource in every locale. Source package names, saved preferences and application
ID retain their existing values to support updates to the installed port.

Build the signed app with:

```sh
python3 tools/build_settings_ui.py --output artifacts/unbound-launcher
```

The output is `artifacts/unbound-launcher/the-unbound-launcher.apk`. The existing
preview fixture remains a separate app named “The Unbound Launcher Preview”.

All rights to the original XREAL software remain with XREAL. This project is fan-made.
