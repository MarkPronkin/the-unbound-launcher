"""Verify the full native launcher storage chain, including AppDetails."""
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
LAYOUT_PATH = 'AppDetails/EPHInfoConfig/LayoutConfig.json'


def verify_launcher_storage(archive):
    blob = archive.read('lib/arm64-v8a/libil2cpp.so')
    def branch(address):
        word = struct.unpack_from('<I', blob, address)[0]
        if word >> 26 not in (0b100101, 0b000101): raise ValueError('Unexpected path utility call')
        displacement = word & 0x3ffffff
        if displacement & 0x2000000: displacement -= 0x4000000
        return address + displacement * 4
    chain = {0x3507e94: 0x25b3ae8, 0x25b3b2c: 0x25b37e0, 0x25b3824: 0x2dff470}
    if any(branch(call) != target for call, target in chain.items()):
        raise ValueError('Launcher storage call chain changed')
    header = struct.unpack_from('<Q', blob, 32)[0]
    entry_size, count = struct.unpack_from('<HH', blob, 54)
    def pointer(address):
        for i in range(count):
            kind, flags, offset, virtual, _, length, _, _ = struct.unpack_from('<IIQQQQQQ', blob, header + i * entry_size)
            if kind == 1 and virtual <= address < virtual + length:
                return struct.unpack_from('<Q', blob, offset + address - virtual)[0]
        raise ValueError('Path literal outside native library segments')
    strings = {item['Address']: item['Value'] for item in json.loads((ROOT/'artifacts/tools/il2cppdumper-6.7.46/script.json').read_text())['ScriptString']}
    actual = '/'.join(strings[pointer(slot)] for slot in [0x45b1f68, 0x454eee8, 0x45e65e0])
    if actual != LAYOUT_PATH: raise ValueError('Launcher storage folder literals changed: ' + actual)
    source = (ROOT/'settings-ui/src/dev/beampro/settings/AppMenuStore.java').read_text()
    if 'LAYOUT_PATH = "' + actual + '";' not in source:
        raise ValueError('Phone editor does not use the native launcher storage path')
    return {'path': actual, 'base': 'Unity Application.persistentDataPath / Android getExternalFilesDir(null)',
            'native_chain': ['EPHAppInfoProxy.CacheFolder', 'PathUtility.AppDetailsPath', 'PathUtility.PersistentDataPath', 'Application.persistentDataPath'],
            'call_sites': {hex(k): hex(v) for k, v in chain.items()}}
