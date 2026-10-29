"""Keep multi-layer app windows on the Unity copy path.

Opening an app window stores AppInfo.useMultiLayer as the app's flag OR
Application2DManager.APPLICATIONN_USE_MULTILAYER. A set flag makes
RequestOpenNativeApp create a native compositor quad layer
(CreateTemporaryNROverlay) instead of CreateTemporaryAndroidSurfaceTexture.
On Mali that layer stays sRGB-encoded (YouTube looks washed out), while the
copy path has the hardware-or-shader decode. Each store writes false instead.
isProtected and the protected overlay are unchanged.
"""
import hashlib

from app_color import LIBRARY
from xreal_telemetry import file_offset

# Launcher library after patch_color_native, patch_startup_tutorial and patch_stereo_native.
BASELINE_SHA256 = '95cc1867d098ce26946f05b11db87cfeb9db52f8c56a7bd48f05dee02178a4a2'
FIELD = 0x71  # AppInfo.useMultiLayer
# Store address -> (method, AppInfo register).
SITES = {
    0x32FF3B4: ('EvaProAppWidgetContent.OpenNativeApp', 19),
    0x32FFCB8: ('EvaProAppWidgetContent.OpenNativeAppWithIntent', 20),
    0x33000E4: ('EvaProAppWidgetContent.FlowNativeAppInSpace', 20),
    0x330944C: ('EvaProAppWidgetContent.OnNativeApplicationChange', 21),
    0x34EE2F0: ('EvaProAppSeparateWidgetContent.OpenNativeApp', 21),
    0x34EE5D4: ('EvaProAppSeparateWidgetContent.OpenNativeAppWithIntent', 22),
}


def store(source, base):
    """STRB W<source>, [X<base>, #0x71]; source 31 is WZR."""
    return (0x39000000 | FIELD << 10 | base << 5 | source).to_bytes(4, 'little')


def patch_overlay_routing(original, *, restore=False):
    if not restore and hashlib.sha256(original).hexdigest() != BASELINE_SHA256:
        raise ValueError('Unexpected native library for overlay routing')
    blob = bytearray(original)
    for address, (method, base) in SITES.items():
        offset = file_offset(blob, address)
        old, new = store(8, base), store(31, base)
        expected, replacement = (new, old) if restore else (old, new)
        if blob[offset:offset + 4] != expected:
            raise ValueError(f'Unexpected useMultiLayer store in {method}')
        blob[offset:offset + 4] = replacement
    if restore and hashlib.sha256(blob).hexdigest() != BASELINE_SHA256:
        raise ValueError('Unrelated native changes in overlay routing')
    return bytes(blob)


def verify_overlay_routing(blob):
    patch_overlay_routing(blob, restore=True)
    return {'multilayer_stores_cleared': len(SITES), 'patch_bytes': 4 * len(SITES),
            'protected_overlay_preserved': True, 'library': LIBRARY,
            'hardware_validation': 'pending'}
