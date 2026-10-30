"""Add official Shizuku discovery/permission declarations to binary Android XML."""
import struct
from branding import _append_pool_string, _pool_strings
from build_port_bootstrap import chunks

PROVIDER = "rikka.shizuku.ShizukuProvider"
PERMISSION = "moe.shizuku.manager.permission.API_V23"
MANAGER = "moe.shizuku.privileged.api"
METADATA = "moe.shizuku.client.V3_SUPPORT"


def patch_shizuku_manifest(original):
    nodes = list(chunks(original))
    _, pool_at, _, pool_size = next(n for n in nodes if n[0] == 1)
    pool = original[pool_at:pool_at + pool_size]
    strings = _pool_strings(pool)
    _, resource_at, resource_header, resource_size = next(n for n in nodes if n[0] == 0x180)
    ids = list(struct.unpack_from('<' + 'I' * ((resource_size - resource_header) // 4), original, resource_at + resource_header))
    package = None
    declared = set()
    has_queries = False
    for kind, at, header, _ in nodes:
        if kind != 0x102:
            continue
        tag = strings[struct.unpack_from('<I', original, at + header + 4)[0]]
        has_queries |= tag == 'queries'
        offset, stride, count = struct.unpack_from('<HHH', original, at + header + 8)
        if stride != 20:
            raise ValueError('Unexpected manifest attribute size')
        for i in range(count):
            pos = at + header + offset + i * stride
            name = strings[struct.unpack_from('<I', original, pos + 4)[0]]
            if original[pos + 15] == 3:
                value = strings[struct.unpack_from('<I', original, pos + 16)[0]]
                if tag == 'manifest' and name == 'package':
                    package = value
                if name == 'name':
                    declared.add((tag, value))
    if not package:
        raise ValueError('Missing package')

    def index(value):
        nonlocal pool, strings
        if value not in strings:
            pool = _append_pool_string(pool, value)
            strings = _pool_strings(pool)
        return strings.index(value)

    attributes = {'name': 0x01010003, 'permission': 0x01010006, 'enabled': 0x0101000e,
                  'exported': 0x01010010, 'authorities': 0x01010018,
                  'multiprocess': 0x01010013, 'value': 0x01010024}
    for name, resource_id in attributes.items():
        i = index(name)
        ids.extend([0] * max(0, i + 1 - len(ids)))
        if ids[i] not in (0, resource_id):
            raise ValueError('Conflicting Android attribute: ' + name)
        ids[i] = resource_id
    ns = index('http://schemas.android.com/apk/res/android')

    def element(tag, attrs):
        encoded = []
        for name, kind, value in sorted(attrs, key=lambda attr: attributes[attr[0]]):
            value = index(value) if kind == 3 else value
            encoded.append(struct.pack('<IIIHBBI', ns, index(name), value if kind == 3 else 0xffffffff, 8, 0, kind, value))
        begin = struct.pack('<HHIII', 0x102, 16, 36 + 20 * len(encoded), 0, 0xffffffff)
        begin += struct.pack('<IIHHHHHH', 0xffffffff, index(tag), 20, 20, len(encoded), 0, 0, 0) + b''.join(encoded)
        return begin + struct.pack('<HHIIIII', 0x103, 16, 24, 0, 0xffffffff, 0xffffffff, index(tag))

    permission = b'' if ('uses-permission', PERMISSION) in declared else element('uses-permission', [('name', 3, PERMISSION)])
    provider = b'' if ('provider', PROVIDER) in declared else element('provider', [
        ('name', 3, PROVIDER), ('authorities', 3, package + '.shizuku'),
        ('multiprocess', 0x12, 0), ('enabled', 0x12, 0xffffffff), ('exported', 0x12, 0xffffffff),
        ('permission', 3, 'android.permission.INTERACT_ACROSS_USERS_FULL')])
    metadata = b'' if ('meta-data', METADATA) in declared else element('meta-data', [('name', 3, METADATA), ('value', 0x12, 0xffffffff)])
    query = b'' if ('package', MANAGER) in declared else element('package', [('name', 3, MANAGER)])
    if not has_queries and query:
        wrapper = element('queries', [])
        permission += wrapper[:-24] + query + wrapper[-24:]
        query = b''
    resource_map = struct.pack('<HHI', 0x180, resource_header, resource_header + 4 * len(ids)) + struct.pack('<' + 'I' * len(ids), *ids)
    output = bytearray(original[:8])
    for kind, at, _, size in nodes:
        tag = strings[struct.unpack_from('<I', original, at + 20)[0]] if kind in (0x102, 0x103) else None
        if kind == 0x102 and tag == 'application':
            output += permission
        if kind == 0x103 and tag == 'application':
            output += provider + metadata
        if kind == 0x103 and tag == 'queries':
            output += query
        output += pool if kind == 1 else resource_map if kind == 0x180 else original[at:at + size]
    struct.pack_into('<I', output, 4, len(output))
    return bytes(output)
