"""Append one non-exported permission Activity without rebuilding original resources."""
import struct
from build_port_bootstrap import chunks

ACTIVITY = 'dev.beampro.settings.PermissionSetupActivity'


def patch_manifest(original, activity_name=ACTIVITY):
    nodes = list(chunks(original))
    _, start, header, size = next(node for node in nodes if node[0] == 1)
    count, styles, flags, string_start, style_start = struct.unpack_from('<IIIII', original, start + 8)
    assert not styles and not style_start and not flags & (256 | 1)
    pool = bytearray(original[start:start + size])
    offsets = list(struct.unpack_from('<' + 'I' * count, pool, header))
    strings = []
    for offset in offsets:
        length = struct.unpack_from('<H', pool, string_start + offset)[0]
        assert length < 0x8000
        strings.append(pool[string_start + offset + 2:string_start + offset + 2 + length * 2].decode('utf-16le'))
    assert activity_name not in strings
    tail = bytes(pool[string_start:])
    encoded = struct.pack('<H', len(activity_name)) + activity_name.encode('utf-16le') + b'\0\0'
    new_pool = pool[:header] + struct.pack('<' + 'I' * (count + 1), *offsets, len(tail)) + tail + encoded
    new_pool += b'\0' * (-len(new_pool) % 4)
    struct.pack_into('<I', new_pool, 4, len(new_pool))
    struct.pack_into('<I', new_pool, 8, count + 1)
    struct.pack_into('<I', new_pool, 20, string_start + 4)
    namespace = strings.index('http://schemas.android.com/apk/res/android')
    activity = strings.index('activity')
    attrs = []
    for name, kind, value in [('name', 3, count), ('exported', 0x12, 0), ('screenOrientation', 0x10, 1)]:
        attrs.append(struct.pack('<IIIHBBI', namespace, strings.index(name), value if kind == 3 else 0xFFFFFFFF, 8, 0, kind, value))
    begin = struct.pack('<HHIII', 0x102, 16, 36 + len(attrs) * 20, 0, 0xFFFFFFFF)
    begin += struct.pack('<IIHHHHHH', 0xFFFFFFFF, activity, 20, 20, len(attrs), 0, 0, 0) + b''.join(attrs)
    end = struct.pack('<HHIIIII', 0x103, 16, 24, 0, 0xFFFFFFFF, 0xFFFFFFFF, activity)
    output = bytearray(original[:8])
    added = False
    for kind, position, _, chunk_size in nodes:
        if kind == 0x103 and strings[struct.unpack_from('<I', original, position + 20)[0]] == 'application':
            assert not added
            output += begin + end
            added = True
        output += new_pool if kind == 1 else original[position:position + chunk_size]
    assert added
    struct.pack_into('<I', output, 4, len(output))
    return bytes(output)
