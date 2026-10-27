"""Register the private, read-only debug ZIP provider in the production APK."""
from updater_manifest import patch_private_components

PROVIDER = "dev.beampro.settings.DebugFileProvider"


def patch_debug_manifest(original):
    return patch_private_components(original, [("provider", PROVIDER, [
        ("authorities", 3, "{package}.unbound.debug"), ("grantUriPermissions", 0x12, 0xFFFFFFFF)])])
