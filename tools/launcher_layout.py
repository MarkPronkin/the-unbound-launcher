"""Verify the phone preview's grid against the original headset prefab chain."""
import hashlib
from pathlib import Path
import struct
import sys

MANAGER = 'assets/bin/Data/0d9ebdefad393464e865fd30ddf67f6f'
PAGE = 'assets/bin/Data/692f33ac478382f4caa97208a41c396d'
HASHES = {
    MANAGER: '713c0e5c598cffea868272088cf2183cef75aae451185f3d408352045dcd4a6f',
    PAGE: 'd6d7efb8eaa330b9b73196d5c42e4d52e86e74874ace8ab94a6ab63747c4d032',
}


def verify_launcher_layout(archive):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'artifacts/tools/python'))
    import UnityPy
    assets = {}
    for path, expected in HASHES.items():
        raw = archive.read(path)
        if hashlib.sha256(raw).hexdigest() != expected:
            raise ValueError('Launcher prefab changed; recheck the headset preview layout')
        env = UnityPy.load(raw)
        assets[path] = next(iter(env.files.values()))
    manager = assets[MANAGER].objects[216].get_raw_data()
    page_file, page_id = struct.unpack_from('<Iq', manager, 44)
    assert assets[MANAGER].externals[page_file-1].path == PAGE.rsplit('/', 1)[-1]
    assert page_id == 2  # EPHAppPage GameObject, with EPHAppPageView component 10.
    capacity = struct.unpack_from('<I', manager, 156)[0]
    page = assets[PAGE].objects[10].get_raw_data()
    layout_file, layout_id = struct.unpack_from('<Iq', page, 56)
    assert layout_file == 0 and layout_id == 9
    layout = assets[PAGE].objects[layout_id].get_raw_data()
    corner, axis, cell_x, cell_y, gap_x, gap_y, constraint, columns = struct.unpack_from('<IIffffII', layout, 284)
    if (capacity, corner, axis, constraint, columns) != (10, 0, 0, 1, 5):
        raise ValueError('Preview expects ten apps, upper-left, horizontal, five fixed columns')
    return {'columns': columns, 'rows': capacity // columns, 'apps_per_page': capacity,
            'fill': 'left to right, then top to bottom', 'start_corner': 'upper left',
            'cell_size': [cell_x, cell_y], 'spacing': [gap_x, gap_y], 'prefab_sha256': HASHES}
