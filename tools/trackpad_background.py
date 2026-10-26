"""Set the phone controller's screen and trackpad fills to opaque black.

The original vector paths, dot colors/alpha, button fills and touch layouts are
retained. Resource hashes pin these edits to the verified launcher baseline.
"""
import hashlib
import struct

from build_port_bootstrap import chunks


BLACK = 0xFF000000
# path: (Android attribute ID, original color, original SHA-256)
BACKGROUNDS = {
    # Spatial Mouse uses AirMouseFragment, independently of the two trackpads.
    "res/Ji.xml": (0x010100D4, 0xFF111111, "e2c0a7d9c2da5635aa73d9dc04206322fbd54487c0948c88e50dcd7badbe9483"),
    "res/3h.xml": (0x010100D4, 0xFF111111, "ec6bbb103d26ed7d7f0ebf69fac63b33050485664d940b32b4aa8ef7a0d4242f"),
    "res/54.xml": (0x010100D4, 0xFF111111, "46e75569100788bbb049e5ca272868d418aa3f9e3968de80c9d6a24a14d120ce"),
    "res/iJ.xml": (0x01010404, 0xFF161616, "4bbc64b56abd5cdf2af47efd8f5c9955d977ea5eb55af9a481ffdde87cf12f6a"),
    "res/Tp.xml": (0x01010404, 0xFF161616, "20c9e36b7f1b2e505fbe7b4f981161e2ad2fddf2a28e99cec2d78295c30ed7ba"),
}


def _recolor(name, original, restore=False):
    attribute_id, old_color, expected_hash = BACKGROUNDS[name]
    if not restore and hashlib.sha256(original).hexdigest() != expected_hash:
        raise ValueError(f"Unexpected trackpad resource: {name}")
    blob = bytearray(original)
    nodes = list(chunks(original))
    _, resource_start, resource_header, resource_size = next(node for node in nodes if node[0] == 0x180)
    resource_ids = struct.unpack_from("<" + "I" * ((resource_size - resource_header) // 4),
                                      original, resource_start + resource_header)
    edits = 0
    for kind, start, header, size in nodes:
        if kind != 0x102:
            continue
        attribute_start, attribute_size, count = struct.unpack_from("<HHH", original, start + header + 8)
        if attribute_size != 20:
            raise ValueError("Unexpected XML attribute size")
        for index in range(count):
            position = start + header + attribute_start + index * attribute_size
            if position + attribute_size > start + size:
                raise ValueError("XML attribute extends beyond its element")
            name_index = struct.unpack_from("<I", original, position + 4)[0]
            if name_index >= len(resource_ids) or resource_ids[name_index] != attribute_id:
                continue
            value = struct.unpack_from("<I", original, position + 16)[0]
            if 0x1C <= original[position + 15] <= 0x1F and value == (BLACK if restore else old_color):
                struct.pack_into("<I", blob, position + 16, old_color if restore else BLACK)
                edits += 1
    if edits != 1:
        raise ValueError(f"Expected exactly one trackpad background fill in {name}, found {edits}")
    if restore and hashlib.sha256(blob).hexdigest() != expected_hash:
        raise ValueError(f"Unrelated trackpad resource data changed: {name}")
    return bytes(blob)


def patch_trackpad_background(name, original):
    return _recolor(name, original)


def restore_trackpad_background(name, patched):
    return _recolor(name, patched, restore=True)
