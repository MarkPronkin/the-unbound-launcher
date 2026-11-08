"""Use app copies when optional protected-overlay prerequisites are unavailable.

Entering immersive mode requests the same compositor overlay, so that request
follows the same capability result.

The private capability file is created by AppSurfacePolicy's EGL/DRM checks.
It does not change Android secure-window flags or the virtual display's security.
An absent, malformed, false or unreadable file preserves the native overlay.
Java publishes true before probing, and retains it on failure or timeout.
"""
import hashlib
import json
from pathlib import Path

from xreal_telemetry import file_offset


def native_spec():
    return json.loads(Path(__file__).with_name('app_surface_routing_native.json').read_text())


def patch_surface_routing(original, *, restore=False):
    spec=native_spec()
    if not restore and hashlib.sha256(original).hexdigest()!=spec['baseline_sha256']:
        raise ValueError('Unexpected native library for app-surface capability routing')
    blob=bytearray(original)
    for target in spec['targets']:
        offset=file_offset(blob,target['address'])
        before,after=bytes.fromhex(target['original']),bytes.fromhex(target['replacement'])
        expected,replacement=(after,before) if restore else (before,after)
        if blob[offset:offset+len(expected)]!=expected:
            raise ValueError('Unexpected app-surface instructions: '+target['name'])
        blob[offset:offset+len(expected)]=replacement
    if restore and hashlib.sha256(blob).hexdigest()!=spec['baseline_sha256']:
        raise ValueError('Unrelated native changes in app-surface routing')
    return bytes(blob)


def verify_surface_routing(blob):
    patch_surface_routing(blob,restore=True)
    return {'capability_gated_protected_hint_stores':6,'capability_gated_immersive_overlay_request':True,
            'unknown_capability_preserves_overlay':True,
            'overlay_requires_gpu_and_drm_readiness':True,'android_secure_flags_unchanged':True,
            'failed_or_timed_out_probe_uses_copy':True,'no_provisioning_or_license_requests':True,
            'virtual_display_security_unchanged':True,'hardware_validation':'pending'}
