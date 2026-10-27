"""Add an opaque black launch theme without renumbering existing resources.

SplashActivity uses this theme; the connection builder also applies it to the
phone connection screen. The original transparent theme and the glasses
controller windows retain their behavior.
"""
import struct
from branding import _append_pool_string, _chunks, _pool_strings

STYLE_ID = 0x7F1102E6
STYLE_NAME = "Unbound.Startup"
PARENT = 0x7F11019D  # Theme.AppCompat.NoActionBar
SPLASH = "ai.nreal.nebula.activity.splash.SplashActivity"
BLACK = 0xFF000000
# Android framework attribute IDs from the project's installed API 34 android.jar.
ATTRS = {
    0x01010031: (0x1C, BLACK),  # colorBackground
    0x01010054: (0x1C, BLACK),  # windowBackground
    0x01010058: (0x12, 0),  # windowIsTranslucent
    0x01010451: (0x1C, BLACK),  # statusBarColor
    0x01010452: (0x1C, BLACK),  # navigationBarColor
    0x010104E0: (0x12, 0),  # windowLightStatusBar
    0x0101056C: (0x12, 0),  # windowLightNavigationBar
    0x0101062C: (0x1C, BLACK),  # windowSplashScreenBackground
    0x0101062D: (0x01, 0x7F0D0001),  # windowSplashScreenAnimatedIcon: adaptive launcher icon
    0x01010630: (0x1C, BLACK),  # windowSplashScreenIconBackgroundColor
}


def add_startup_theme(resources):
    kind, header, size = struct.unpack_from("<HHI", resources)
    if kind != 2 or size != len(resources):
        raise ValueError("Expected a complete Android resource table")
    output = bytearray(resources[:header])
    edits = set()
    for kind, start, package_header, package_size in _chunks(resources, header, size):
        package = resources[start:start + package_size]
        if kind != 0x200:
            output += package
            continue
        if struct.unpack_from("<I", package, 8)[0] != 0x7F:
            raise ValueError("Unexpected resource package")
        key_offset = struct.unpack_from("<I", package, 276)[0]
        key_size = struct.unpack_from("<I", package, key_offset + 4)[0]
        keys = _pool_strings(package[key_offset:key_offset + key_size])
        if STYLE_NAME in keys:
            raise ValueError("Startup theme already exists")
        key_index = len(keys)
        entry = struct.pack("<HHIII", 16, 1, key_index, PARENT, len(ATTRS))
        entry += b"".join(struct.pack("<IHBBI", attr, 8, 0, kind, value)
                          for attr, (kind, value) in sorted(ATTRS.items()))
        rebuilt = bytearray(package[:package_header])
        for child_kind, at, child_header, child_size in _chunks(package, package_header, len(package)):
            child = bytearray(package[at:at + child_size])
            if at == key_offset:
                child = bytearray(_append_pool_string(bytes(child), STYLE_NAME)); edits.add("key")
            elif child_kind in (0x201, 0x202) and child[8] == 0x11:
                count = struct.unpack_from("<I", child, 12)[0]
                if count != STYLE_ID & 0xFFFF or child[9]:
                    raise ValueError("Pinned style resource entries changed")
                if child_kind == 0x202:
                    struct.pack_into("<I", child, 12, count + 1)
                    child += struct.pack("<I", 0); edits.add("spec")
                elif not any(child[24:child_header]):
                    entries_start = struct.unpack_from("<I", child, 16)[0]
                    if entries_start != child_header + count * 4:
                        raise ValueError("Unexpected default style offsets")
                    data = child[entries_start:]
                    child = child[:entries_start] + struct.pack("<I", len(data)) + data + entry
                    struct.pack_into("<I", child, 12, count + 1)
                    struct.pack_into("<I", child, 16, entries_start + 4)
                    edits.add("default")
            struct.pack_into("<I", child, 4, len(child))
            rebuilt += child
        struct.pack_into("<I", rebuilt, 4, len(rebuilt))
        output += rebuilt
    if edits != {"key", "spec", "default"}:
        raise ValueError("Expected one new key, style specification and default style")
    struct.pack_into("<I", output, 4, len(output))
    return bytes(output)


def patch_splash_theme_manifest(manifest):
    return patch_activity_theme_manifest(manifest, SPLASH)


def patch_activity_theme_manifest(manifest, activity, *, unlock_orientation=False):
    """Reuse the opaque startup theme for an explicitly selected original translucent activity."""
    header = struct.unpack_from("<H", manifest, 2)[0]
    nodes = list(_chunks(manifest, header, len(manifest)))
    _, at, _, size = next(n for n in nodes if n[0] == 1)
    strings = _pool_strings(manifest[at:at + size])
    _, at, resource_header, size = next(n for n in nodes if n[0] == 0x180)
    ids = struct.unpack_from("<" + "I" * ((size - resource_header) // 4), manifest, at + resource_header)
    output = bytearray(manifest)
    edits = 0
    for kind, at, node_header, size in nodes:
        if kind != 0x102 or strings[struct.unpack_from("<I", manifest, at + node_header + 4)[0]] != "activity":
            continue
        offset, stride, count = struct.unpack_from("<HHH", manifest, at + node_header + 8)
        if stride != 20:
            raise ValueError("Unexpected XML attribute size")
        attrs = {}
        for index in range(count):
            position = at + node_header + offset + index * stride
            name = struct.unpack_from("<I", manifest, position + 4)[0]
            if name < len(ids): attrs[ids[name]] = position
        name = attrs.get(0x01010003)
        if name is None or manifest[name + 15] != 3 or strings[struct.unpack_from("<I", manifest, name + 16)[0]] != activity:
            continue
        theme = attrs.get(0x01010000)
        if theme is None or manifest[theme + 15] != 1 or struct.unpack_from("<I", manifest, theme + 16)[0] != 0x7F1101DA:
            raise ValueError(f"Unexpected original {activity} theme")
        struct.pack_into("<I", output, theme + 16, STYLE_ID); edits += 1
        if unlock_orientation:
            orientation = attrs.get(0x0101001E)
            if orientation is None or manifest[orientation + 15] != 0x10 or struct.unpack_from("<I", manifest, orientation + 16)[0] != 1:
                raise ValueError(f"Unexpected original {activity} orientation")
            struct.pack_into("<I", output, orientation + 16, 0xFFFFFFFF)
    if edits != 1:
        raise ValueError(f"Expected exactly one {activity} theme replacement")
    return bytes(output)
