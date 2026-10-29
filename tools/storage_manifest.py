"""Request Android's all-files access so the photo and video players can open any local file."""
import struct
from branding import _append_pool_string, _pool_strings
from build_port_bootstrap import chunks

PERMISSION = "android.permission.MANAGE_EXTERNAL_STORAGE"


def patch_storage_manifest(original):
    nodes = list(chunks(original))
    _, pool_at, _, pool_size = next(node for node in nodes if node[0] == 1)
    pool = original[pool_at:pool_at + pool_size]
    strings = _pool_strings(pool)
    applications = []
    for kind, at, header, _ in nodes:
        if kind != 0x102:
            continue
        tag = strings[struct.unpack_from("<I", original, at + header + 4)[0]]
        if tag == "application":
            applications.append(at)
        if tag != "uses-permission":
            continue
        offset, stride, count = struct.unpack_from("<HHH", original, at + header + 8)
        if stride != 20:
            raise ValueError("Unexpected manifest attribute size")
        for index in range(count):
            position = at + header + offset + index * stride
            if strings[struct.unpack_from("<I", original, position + 4)[0]] == "name" and original[position + 15] == 3 \
                    and strings[struct.unpack_from("<I", original, position + 16)[0]] == PERMISSION:
                return original
    if len(applications) != 1:
        raise ValueError("Expected one application element")

    def index(value):
        nonlocal pool, strings
        if value not in strings:
            pool = _append_pool_string(pool, value)
            strings = _pool_strings(pool)
        return strings.index(value)

    for existing in ("http://schemas.android.com/apk/res/android", "name", "uses-permission"):
        if existing not in strings:
            raise ValueError("Missing manifest string: " + existing)
    ns, name, tag, value = (index(item) for item in (
        "http://schemas.android.com/apk/res/android", "name", "uses-permission", PERMISSION))
    # <uses-permission> belongs to <manifest>, so it goes directly before <application>.
    begin = struct.pack("<HHIII", 0x102, 16, 56, 0, 0xFFFFFFFF)
    begin += struct.pack("<IIHHHHHH", 0xFFFFFFFF, tag, 20, 20, 1, 0, 0, 0)
    begin += struct.pack("<IIIHBBI", ns, name, value, 8, 0, 3, value)
    end = struct.pack("<HHIIIII", 0x103, 16, 24, 0, 0xFFFFFFFF, 0xFFFFFFFF, tag)
    output = bytearray(original[:8])
    for kind, at, _, size in nodes:
        if at == applications[0]:
            output += begin + end
        output += pool if kind == 1 else original[at:at + size]
    struct.pack_into("<I", output, 4, len(output))
    return bytes(output)
