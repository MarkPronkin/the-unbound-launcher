"""Unlock orientation on the four adaptive phone pages, preserving AR activities."""
import struct

from build_port_bootstrap import chunks
from branding import _pool_strings

PHONE_ACTIVITIES = {
    "ai.nreal.nebula.activity.setting.SettingActivity",
    "ai.nreal.nebula.activity.setting.permission_manager.PermissionManagerActivity",
    "dev.beampro.settings.PermissionSetupActivity",
    "dev.beampro.settings.AppMenuActivity",
}


def patch_adaptive_manifest(manifest):
    nodes = list(chunks(manifest))
    _, start, _, size = next(node for node in nodes if node[0] == 1)
    strings = _pool_strings(manifest[start:start + size])
    output = bytearray(manifest)
    found = set()
    resizable = False
    for kind, start, header, _ in nodes:
        if kind != 0x102:
            continue
        tag = strings[struct.unpack_from("<I", manifest, start + header + 4)[0]]
        offset, stride, count = struct.unpack_from("<HHH", manifest, start + header + 8)
        if stride != 20:
            raise ValueError("Unexpected XML attribute size")
        attrs = {strings[struct.unpack_from("<I", manifest, start + header + offset + index * stride + 4)[0]]:
                 start + header + offset + index * stride for index in range(count)}
        if tag == "application" and "resizeableActivity" in attrs:
            at = attrs["resizeableActivity"]
            resizable = manifest[at + 15] == 0x12 and struct.unpack_from("<I", manifest, at + 16)[0] != 0
        if tag != "activity" or "name" not in attrs:
            continue
        name = strings[struct.unpack_from("<I", manifest, attrs["name"] + 16)[0]]
        if name not in PHONE_ACTIVITIES:
            continue
        if name in found:
            raise ValueError("Duplicate adaptive phone activity")
        found.add(name)
        at = attrs.get("screenOrientation")
        if at is None or manifest[at + 15] != 0x10 or struct.unpack_from("<I", manifest, at + 16)[0] not in (1, 0xFFFFFFFF):
            raise ValueError("Unexpected phone orientation declaration: " + name)
        if "resizeableActivity" in attrs:
            raise ValueError("Expected phone pages to inherit application resizability")
        # SCREEN_ORIENTATION_UNSPECIFIED; keep the binary XML and all resource IDs intact.
        struct.pack_into("<I", output, at + 16, 0xFFFFFFFF)
    if found != PHONE_ACTIVITIES or not resizable:
        raise ValueError("Expected all four phone pages in a resizable application")
    return bytes(output)
