"""Add the single-APK rendezvous and setup UI while preserving existing binary XML."""
import struct
from branding import _append_pool_string, _pool_strings
from build_port_bootstrap import chunks

AUTHORITY = "com.xreal.evapro.nebula.unbound.bridge"


def patch_usb_attach_manifest(original):
    """Keep USB broadcasts, but stop advertising Splash as an Android USB handler."""
    nodes = list(chunks(original))
    _, at, _, size = next(n for n in nodes if n[0] == 1)
    strings = _pool_strings(original[at:at + size])
    stack, elements = [], []
    for index, (kind, at, header, _) in enumerate(nodes):
        if kind == 0x102:
            tag = strings[struct.unpack_from("<I", original, at + header + 4)[0]]
            offset, stride, count = struct.unpack_from("<HHH", original, at + header + 8)
            if stride != 20:
                raise ValueError("Unexpected manifest attribute size")
            attrs = {}
            for number in range(count):
                position = at + header + offset + number * stride
                name = strings[struct.unpack_from("<I", original, position + 4)[0]]
                value = struct.unpack_from("<I", original, position + 16)[0]
                attrs[name] = strings[value] if original[position + 15] == 3 else value
            node = {"tag": tag, "attrs": attrs, "start": index, "children": []}
            if stack:
                stack[-1]["children"].append(node)
            elements.append(node)
            stack.append(node)
        elif kind == 0x103:
            if not stack:
                raise ValueError("Unbalanced manifest elements")
            stack.pop()["end"] = index
    if stack:
        raise ValueError("Unclosed manifest elements")
    splash = [node for node in elements if node["tag"] == "activity"
              and node["attrs"].get("name") == "ai.nreal.nebula.activity.splash.SplashActivity"]
    if len(splash) != 1:
        raise ValueError("Expected the original Splash activity")
    action = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
    removed, filters, metadata = set(), 0, 0
    for child in splash[0]["children"]:
        if child["tag"] == "intent-filter":
            actions = [n["attrs"].get("name") for n in child["children"] if n["tag"] == "action"]
            if action not in actions:
                continue
            if actions != [action] or len(child["children"]) != 1:
                raise ValueError("Unexpected mixed USB activity filter")
            filters += 1
        elif child["tag"] == "meta-data" and child["attrs"].get("name") == action:
            metadata += 1
        else:
            continue
        removed.update(range(child["start"], child["end"] + 1))
    if (filters, metadata) not in ((0, 0), (1, 1)):
        raise ValueError("Unexpected USB handler declaration")
    output = bytearray(original[:8])
    for index, (_, at, _, size) in enumerate(nodes):
        if index not in removed:
            output += original[at:at + size]
    struct.pack_into("<I", output, 4, len(output))
    return bytes(output)


def patch_release_manifest(original):
    """Disable the baseline's development flag without changing other attributes."""
    nodes = list(chunks(original))
    _, at, _, size = next(n for n in nodes if n[0] == 1)
    strings = _pool_strings(original[at:at + size])
    output = bytearray(original)
    found = 0
    for kind, at, header, _ in nodes:
        if kind != 0x102 or strings[struct.unpack_from("<I", original, at + header + 4)[0]] != "application":
            continue
        offset, stride, count = struct.unpack_from("<HHH", original, at + header + 8)
        if stride != 20:
            raise ValueError("Unexpected manifest attribute size")
        for index in range(count):
            position = at + header + offset + index * stride
            name = strings[struct.unpack_from("<I", original, position + 4)[0]]
            if name != "debuggable":
                continue
            if original[position + 15] != 0x12:
                raise ValueError("Expected a boolean debuggable attribute")
            struct.pack_into("<I", output, position + 8, 0xFFFFFFFF)
            struct.pack_into("<I", output, position + 16, 0)
            found += 1
    if found != 1:
        raise ValueError("Expected one explicit application debuggable attribute")
    return bytes(output)


def patch_manifest(original):
    nodes = list(chunks(original))
    _, at, _, size = next(n for n in nodes if n[0] == 1)
    pool = original[at:at + size]
    strings = _pool_strings(pool)
    _, resource_at, resource_header, resource_size = next(n for n in nodes if n[0] == 0x180)
    resource_ids = struct.unpack_from("<" + "I" * ((resource_size - resource_header) // 4), original, resource_at + resource_header)

    def index(value):
        nonlocal pool, strings
        if value not in strings:
            pool = _append_pool_string(pool, value)
            strings = _pool_strings(pool)
        return strings.index(value)

    ns = index("http://schemas.android.com/apk/res/android")

    def element(tag, attrs):
        encoded = []
        # Android's styled-attribute reader advances through resource IDs in order.
        # An out-of-order authorities attribute makes it skip exported/process,
        # even though a raw XML dump still displays their intended values.
        def attribute_id(attr):
            name = attr[0]
            if name not in strings or strings.index(name) >= len(resource_ids):
                raise ValueError(f"Missing mapped Android attribute: {name}")
            return resource_ids[strings.index(name)]
        for name, kind, value in sorted(attrs, key=attribute_id):
            # Attribute names must already have their framework ID in the resource map.
            if name not in strings:
                raise ValueError(f"Missing mapped Android attribute: {name}")
            value = index(value) if kind == 3 else value
            encoded.append(struct.pack("<IIIHBBI", ns, index(name), value if kind == 3 else 0xFFFFFFFF, 8, 0, kind, value))
        begin = struct.pack("<HHIII", 0x102, 16, 36 + 20 * len(encoded), 0, 0xFFFFFFFF)
        begin += struct.pack("<IIHHHHHH", 0xFFFFFFFF, index(tag), 20, 20, len(encoded), 0, 0, 0) + b"".join(encoded)
        end = struct.pack("<HHIIIII", 0x103, 16, 24, 0, 0xFFFFFFFF, 0xFFFFFFFF, index(tag))
        return begin + end

    additions = element("activity", [("name", 3, "dev.beampro.adb.AdbPairingActivity"), ("exported", 0x12, 0)])
    additions += element("activity", [("name", 3, "dev.beampro.port.ArBackdropActivity"), ("exported", 0x12, 0)])
    additions += element("service", [("name", 3, "dev.beampro.adb.PairingOverlayService"), ("exported", 0x12, 0)])
    additions += element("provider", [("name", 3, "dev.beampro.adb.LocalBridgeProvider"),
                                       ("authorities", 3, AUTHORITY), ("exported", 0x12, 0xFFFFFFFF),
                                       ("process", 3, ":unbound_bridge")])
    output = bytearray(original[:8])
    inserted = False
    for kind, position, _, chunk_size in nodes:
        if kind == 0x103 and strings[struct.unpack_from("<I", original, position + 20)[0]] == "application":
            output += additions
            inserted = True
        output += pool if kind == 1 else original[position:position + chunk_size]
    if not inserted:
        raise ValueError("Missing application node")
    struct.pack_into("<I", output, 4, len(output))
    return bytes(output)
