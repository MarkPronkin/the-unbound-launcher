"""Export the original launcher's flattened built-in app artwork for the phone UI."""
import hashlib
from pathlib import Path
import sys

# These eph_* textures are the launcher's complete icons, including backgrounds.
# The separate BG_*, FG_* and FrontModelAtlas assets are animated headset layers.
ICONS = {
    "TutorialWidgetContent": ("bdc9b18496968864ab19661b3deb5c3c", "c4cd5b26921a1846fc42abe484ee45a3c0e8ad846a38cde1b12f86ef29625098"),
    "LiveWindowWidgetContent": ("927ff713464795847a8fee7db4597187", "8164949a569f8276c233024c3d9aa9fbb93a0e37d7116b261b34a4f0f68d654d"),
    "PhotoWidgetContent": ("652db94b8093e7d498919f0e952a1940", "905809af0d3c5aebc4b2b1a352353ab105c8624d57857e45191e50233ef22779"),
}


def export_launcher_icons(archive, output):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "artifacts/tools/python"))
    import UnityPy
    output.mkdir(parents=True, exist_ok=True)
    evidence = {}
    for app, (asset, expected) in ICONS.items():
        path = "assets/bin/Data/" + asset
        raw = archive.read(path)
        if hashlib.sha256(raw).hexdigest() != expected:
            raise ValueError(f"Launcher artwork changed for {app}; recheck the original texture")
        env = UnityPy.load(raw)
        texture = next(obj for obj in env.objects if obj.type.name == "Texture2D")
        # The Chinese Unity 2021.3 fork omits 24 bytes of trailing metadata that
        # UnityPy's generated reader expects. Its RGBA32 pixels are complete.
        data = texture.read(check_read=False)
        if (data.m_Name, data.m_Width, data.m_Height, data.m_TextureFormat) != ("eph_" + app, 432, 432, 4):
            raise ValueError(f"Unexpected launcher texture for {app}")
        if data.m_MipCount != 9 or len(data.image_data) != sum(max(1, 432 >> level) ** 2 * 4 for level in range(9)):
            raise ValueError(f"Incomplete launcher pixels for {app}")
        target = output / (app + ".png")
        data.image.save(target)
        evidence[app] = {"source": path, "source_sha256": expected, "texture": data.m_Name,
                         "size": [432, 432], "png_sha256": hashlib.sha256(target.read_bytes()).hexdigest()}
    return evidence
