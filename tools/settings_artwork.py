"""Package the original widget pictures for both the app and its emulator fixture."""
from pathlib import Path

# GlassesNameConstant.glassTypeResIdWidget in the pinned original APK.
WIDGET_ARTWORK = {
    "widget_1s": "res/Lg.webp",
    "widget_air": "res/vH.webp",
    "widget_flora": "res/NY.webp",
    "widget_not_air": "res/-4.webp",
    "widget_one": "res/bf.webp",
    "widget_one_pro": "res/jO.webp",
}

# Dedicated original device pictures where the widget reuses another model's art.
MODEL_ARTWORK = {
    "ota_air2": "res/Bk.webp",
    "ota_air2pro": "res/9V.webp",
    "ota_one_pro_plus": "res/ZV.webp",
    "ota_xbx_a01": "res/Gu.webp",
}


def export_settings_artwork(archive, output):
    output.mkdir(parents=True, exist_ok=True)
    assets = {}
    for name, source in {**WIDGET_ARTWORK, **MODEL_ARTWORK}.items():
        destination = output / (name + ".webp")
        destination.write_bytes(archive.read(source))
        assets["assets/settings/" + destination.name] = destination
    mark = output / "brand-mark.png"
    mark.write_bytes((Path(__file__).resolve().parents[1] / "branding/symbol.png").read_bytes())
    assets["assets/settings/brand-mark.png"] = mark
    return assets
