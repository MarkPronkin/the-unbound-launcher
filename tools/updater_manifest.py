"""Register the app's private installer and read-only APK provider in binary XML."""
import struct
from branding import _append_pool_string, _pool_strings
from build_port_bootstrap import chunks

INSTALLER = "dev.beampro.settings.UpdateInstallActivity"
PROVIDER = "dev.beampro.settings.UpdateFileProvider"


def patch_updater_manifest(original):
    return patch_private_components(original, [
        ("activity", INSTALLER, [("theme", 1, 16974382)]),
        ("provider", PROVIDER, [("authorities", 3, "{package}.unbound.updates"), ("grantUriPermissions", 0x12, 0xFFFFFFFF)])
    ], {"android.permission.INTERNET", "android.permission.REQUEST_INSTALL_PACKAGES"})


def patch_private_components(original, components, required_permissions=()):
    nodes = list(chunks(original))
    _, pool_at, _, pool_size = next(node for node in nodes if node[0] == 1)
    pool = original[pool_at:pool_at + pool_size]
    strings = _pool_strings(pool)
    _, resource_at, header, size = next(node for node in nodes if node[0] == 0x180)
    ids = struct.unpack_from("<" + "I" * ((size - header) // 4), original, resource_at + header)
    package = None
    declared, permissions = set(), set()
    for kind, at, header, _ in nodes:
        if kind != 0x102:
            continue
        tag = strings[struct.unpack_from("<I", original, at + header + 4)[0]]
        offset, stride, count = struct.unpack_from("<HHH", original, at + header + 8)
        if stride != 20:
            raise ValueError("Unexpected manifest attribute size")
        for index in range(count):
            position = at + header + offset + index * stride
            name = strings[struct.unpack_from("<I", original, position + 4)[0]]
            if original[position + 15] != 3:
                continue
            value = strings[struct.unpack_from("<I", original, position + 16)[0]]
            if tag == "manifest" and name == "package":
                package = value
            if name == "name" and tag in ("activity", "provider"):
                declared.add(value)
            if name == "name" and tag == "uses-permission":
                permissions.add(value)
    if not package:
        raise ValueError("Missing application package")
    required = set(required_permissions)
    if not required <= permissions:
        raise ValueError("The updater requires Internet and package-install permissions")
    missing = [component for component in components if component[1] not in declared]
    if not missing:
        return original

    def index(value):
        nonlocal pool, strings
        if value not in strings:
            pool = _append_pool_string(pool, value)
            strings = _pool_strings(pool)
        return strings.index(value)

    ns = index("http://schemas.android.com/apk/res/android")

    def element(tag, attrs):
        def resource_id(attr):
            name = attr[0]
            if name not in strings or strings.index(name) >= len(ids) or not ids[strings.index(name)]:
                raise ValueError("Missing Android attribute resource: " + name)
            return ids[strings.index(name)]
        encoded = []
        for name, kind, value in sorted(attrs, key=resource_id):
            value = index(value) if kind == 3 else value
            encoded.append(struct.pack("<IIIHBBI", ns, index(name), value if kind == 3 else 0xFFFFFFFF, 8, 0, kind, value))
        begin = struct.pack("<HHIII", 0x102, 16, 36 + 20 * len(encoded), 0, 0xFFFFFFFF)
        begin += struct.pack("<IIHHHHHH", 0xFFFFFFFF, index(tag), 20, 20, len(encoded), 0, 0, 0) + b"".join(encoded)
        return begin + struct.pack("<HHIIIII", 0x103, 16, 24, 0, 0xFFFFFFFF, 0xFFFFFFFF, index(tag))

    additions = b""
    for tag, name, attributes in missing:
        resolved = [(key, kind, value.replace("{package}", package) if kind == 3 else value)
                    for key, kind, value in attributes]
        additions += element(tag, [("name", 3, name), ("exported", 0x12, 0), *resolved])
    output = bytearray(original[:8])
    inserted = False
    for kind, at, _, size in nodes:
        if kind == 0x103 and strings[struct.unpack_from("<I", original, at + 20)[0]] == "application":
            output += additions
            inserted = True
        output += pool if kind == 1 else original[at:at + size]
    if not inserted:
        raise ValueError("Missing application element")
    struct.pack_into("<I", output, 4, len(output))
    return bytes(output)
