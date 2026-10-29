"""Brand the port without rebuilding or renumbering the original Android resources.

Only app_name values and the existing launcher icon backing files are replaced.
The package, component names, resource IDs and unrelated localized strings stay intact.
"""
from pathlib import Path
import shutil
import struct
import subprocess
import sys


APP_NAME = "Unbound Launcher"
APP_NAME_RESOURCE = 0x7F100024
OLD_APP_NAMES = frozenset({
    "The Unbound Launcher",
    "My Glasses", "マイグラス", "Meine Brille", "我的眼镜", "내 글래스",
    "Mes lunettes", "Mis gafas", "I miei occhiali",
})
ADAPTIVE_BACKGROUND = "000000"

# Resource IDs and paths are pinned to the verified My Glasses 2.2.2 baseline.
# Keep the adaptive-icon XML and every resource ID unchanged.
ICON_PATHS = {
    "legacy": ("res/YB.webp", "res/2-.webp", "res/pY.webp", "res/Vd.webp", "res/xC.webp"),
    "round": ("res/co.webp", "res/3c.webp", "res/iP.webp", "res/7E.webp", "res/iN.webp"),
    "background": ("res/hH.webp", "res/D2.webp", "res/53.webp", "res/AA.webp", "res/Qx.webp"),
    "foreground": ("res/2U.webp", "res/Ya.webp", "res/vG.webp", "res/Cf.webp", "res/1C.webp"),
}
LEGACY_SIZES = (48, 72, 96, 144, 192)
ADAPTIVE_SIZES = (108, 162, 216, 324, 432)


def _chunks(blob, start, end):
    while start < end:
        if start + 8 > end:
            raise ValueError("Truncated Android resource chunk")
        kind, header, size = struct.unpack_from("<HHI", blob, start)
        if header < 8 or size < header or start + size > end:
            raise ValueError("Invalid Android resource chunk")
        yield kind, start, header, size
        start += size
    if start != end:
        raise ValueError("Resource chunks do not fill their parent")


def _length8(blob, position):
    value = blob[position]
    return (((value & 0x7f) << 8) | blob[position + 1], position + 2) if value & 0x80 else (value, position + 1)


def _length16(blob, position):
    value = struct.unpack_from("<H", blob, position)[0]
    return (((value & 0x7fff) << 16) | struct.unpack_from("<H", blob, position + 2)[0], position + 4) if value & 0x8000 else (value, position + 2)


def _pool_strings(pool):
    kind, header, size = struct.unpack_from("<HHI", pool)
    if kind != 1 or size != len(pool):
        raise ValueError("Expected a complete Android string pool")
    count, _, flags, strings_start, _ = struct.unpack_from("<IIIII", pool, 8)
    values = []
    for index in range(count):
        offset = struct.unpack_from("<I", pool, header + 4 * index)[0]
        position = strings_start + offset
        if flags & 0x100:
            _, position = _length8(pool, position)
            length, position = _length8(pool, position)
            values.append(pool[position:position + length].decode("utf-8"))
        else:
            length, position = _length16(pool, position)
            values.append(pool[position:position + length * 2].decode("utf-16le"))
    return values


def _encode_length8(length):
    if length > 0x7fff:
        raise ValueError("String is too long for Android's UTF-8 pool")
    return bytes((0x80 | (length >> 8), length & 0xff)) if length > 0x7f else bytes((length,))


def _append_pool_string(pool, value):
    """Append a string while retaining all existing indices and encoded contents."""
    _, header, _ = struct.unpack_from("<HHI", pool)
    count, styles, flags, strings_start, styles_start = struct.unpack_from("<IIIII", pool, 8)
    if strings_start != header + 4 * (count + styles):
        raise ValueError("Unexpected padding before Android pool strings")
    utf16 = value.encode("utf-16le")
    if flags & 0x100:
        utf8 = value.encode("utf-8")
        encoded = _encode_length8(len(utf16) // 2) + _encode_length8(len(utf8)) + utf8 + b"\0"
    else:
        if len(utf16) // 2 >= 0x8000:
            raise ValueError("Brand name exceeds the supported UTF-16 length")
        encoded = struct.pack("<H", len(utf16) // 2) + utf16 + b"\0\0"
    encoded += b"\0" * (-len(encoded) % 4)
    strings_end = styles_start or len(pool)
    string_data = pool[strings_start:strings_end]
    offsets_end = header + count * 4
    result = bytearray(pool[:offsets_end])
    result += struct.pack("<I", len(string_data))
    result += pool[offsets_end:strings_start] + string_data + encoded + pool[strings_end:]
    struct.pack_into("<I", result, 4, len(result))
    struct.pack_into("<I", result, 8, count + 1)
    # Appending does not preserve lexical ordering if a pool advertised it.
    struct.pack_into("<I", result, 16, flags & ~1)
    struct.pack_into("<I", result, 20, strings_start + 4)
    if styles_start:
        struct.pack_into("<I", result, 24, styles_start + 4 + len(encoded))
    return bytes(result)


def _table_details(resources):
    kind, header, size = struct.unpack_from("<HHI", resources)
    if kind != 2 or size != len(resources):
        raise ValueError("Expected a complete Android resource table")
    nodes = list(_chunks(resources, header, size))
    pools = [(position, chunk_size) for kind, position, _, chunk_size in nodes if kind == 1]
    if len(pools) != 1:
        raise ValueError("Expected one global resource string pool")
    pool_position, pool_size = pools[0]
    strings = _pool_strings(resources[pool_position:pool_position + pool_size])
    values = []
    for kind, package, package_header, package_size in nodes:
        if kind != 0x200:
            continue
        package_id = struct.unpack_from("<I", resources, package + 8)[0]
        type_pool_offset = struct.unpack_from("<I", resources, package + 268)[0]
        key_pool_offset = struct.unpack_from("<I", resources, package + 276)[0]
        def read_package_pool(offset):
            at = package + offset
            length = struct.unpack_from("<I", resources, at + 4)[0]
            return _pool_strings(resources[at:at + length])
        type_names = read_package_pool(type_pool_offset)
        key_names = read_package_pool(key_pool_offset)
        type_id_offset = struct.unpack_from("<I", resources, package + 284)[0] if package_header >= 288 else 0
        for child_kind, position, child_header, _ in _chunks(resources, package + package_header, package + package_size):
            if child_kind != 0x201:
                continue
            type_id, flags, _, count, entries_start = struct.unpack_from("<BBHII", resources, position + 8)
            if type_id == 0 or type_id > len(type_names) or type_names[type_id - 1] != "string":
                continue
            if flags:
                raise ValueError("Sparse/offset16 resource types require a fresh branding audit")
            for index in range(count):
                entry_offset = struct.unpack_from("<I", resources, position + child_header + index * 4)[0]
                if entry_offset == 0xffffffff:
                    continue
                entry = position + entries_start + entry_offset
                entry_size, entry_flags, key = struct.unpack_from("<HHI", resources, entry)
                if key >= len(key_names) or key_names[key] != "app_name":
                    continue
                resource_id = (package_id << 24) | ((type_id + type_id_offset) << 16) | index
                if resource_id != APP_NAME_RESOURCE or entry_flags:
                    raise ValueError("Unexpected app_name resource identity or entry format")
                value = entry + entry_size
                value_size, _, value_type, data = struct.unpack_from("<HBBI", resources, value)
                if value_size != 8 or value_type != 3 or data >= len(strings):
                    raise ValueError("app_name must be a plain string resource")
                values.append((value + 4, strings[data]))
    if not values:
        raise ValueError("The app_name resource was not found")
    return header, nodes, pool_position, pool_size, strings, values


def app_name_values(resources):
    """Return the configured app labels, for packaging checks and tests."""
    return tuple(value for _, value in _table_details(resources)[-1])


def patch_resources(resources):
    """Rename app_name in every locale without touching other resource values."""
    header, nodes, pool_position, pool_size, strings, values = _table_details(resources)
    unknown = {value for _, value in values} - OLD_APP_NAMES - {APP_NAME}
    if unknown:
        raise ValueError(f"Unexpected original app labels: {sorted(unknown)}")
    if all(value == APP_NAME for _, value in values):
        return resources
    pool = resources[pool_position:pool_position + pool_size]
    if APP_NAME in strings:
        string_index = strings.index(APP_NAME)
    else:
        string_index = len(strings)
        pool = _append_pool_string(pool, APP_NAME)
    patched = bytearray(resources)
    for position, _ in values:
        struct.pack_into("<I", patched, position, string_index)
    output = bytearray(resources[:header])
    for kind, position, _, size in nodes:
        output += pool if kind == 1 else patched[position:position + size]
    struct.pack_into("<I", output, 4, len(output))
    return bytes(output)


def icon_payloads(source_archive, app_icon_path, build_directory, symbol_path=None):
    """Create WebP replacements for every existing launcher icon density.

    The supplied square artwork is resized without color conversion. The transparent
    white symbol is inset into the central 66/108 region, on the black backdrop,
    so round and other Android masks retain the whole mark. Android still applies
    its normal adaptive-icon clipping, motion and scaling through the original XML.
    """
    sips, cwebp = shutil.which("sips"), shutil.which("cwebp")
    if not sips or not cwebp:
        raise RuntimeError("Brand icon packaging requires sips and cwebp")
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "artifacts/tools/python"))
    from PIL import Image
    app_icon_path = Path(app_icon_path).resolve()
    if not app_icon_path.is_file():
        raise FileNotFoundError(app_icon_path)
    symbol_path = Path(symbol_path).resolve() if symbol_path else app_icon_path.with_name("symbol-white.png")
    if not symbol_path.is_file():
        raise FileNotFoundError(symbol_path)
    names = set(source_archive.namelist())
    missing = {name for paths in ICON_PATHS.values() for name in paths} - names
    if missing:
        raise ValueError(f"Baseline launcher icon files changed: {sorted(missing)}")
    for name in ("res/uF.xml", "res/oy.xml"):
        if name not in names:
            raise ValueError(f"Baseline adaptive icon XML is missing: {name}")
    build_directory = Path(build_directory)
    build_directory.mkdir(parents=True, exist_ok=True)

    def run(*args):
        subprocess.run([str(arg) for arg in args], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    # A native WebP encoder expands this one solid color; no raster manipulation
    # library is needed in the build's Python environment.
    background_ppm = build_directory / "background.ppm"
    rgb = tuple(int(ADAPTIVE_BACKGROUND[index:index + 2], 16) for index in (0, 2, 4))
    background_ppm.write_bytes(b"P6\n1 1\n255\n" + bytes(rgb))
    result = {}
    # sips color conversion lifts zero-valued black to near-black on this host.
    # Preserve exact OLED black in the legacy and round icon pixels.
    with Image.open(app_icon_path) as source:
        square = source.convert("RGBA")
    for density, (legacy_size, adaptive_size) in enumerate(zip(LEGACY_SIZES, ADAPTIVE_SIZES)):
        legacy_png = build_directory / f"legacy-{legacy_size}.png"
        legacy_webp = legacy_png.with_suffix(".webp")
        resized = square.resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
        resized.info.clear(); resized.save(legacy_png)
        run(cwebp, "-quiet", "-lossless", legacy_png, "-o", legacy_webp)
        # The complete symbol is contained inside Android's guaranteed safe area.
        inset_size = round(adaptive_size * 66 / 108)
        foreground_png = build_directory / f"foreground-{adaptive_size}.png"
        foreground_webp = foreground_png.with_suffix(".webp")
        background_webp = build_directory / f"background-{adaptive_size}.webp"
        run(sips, "-s", "format", "png", "-z", inset_size, inset_size, symbol_path, "--out", foreground_png)
        # With an RGBA source, sips pads with transparent pixels by default.
        run(sips, "--padToHeightWidth", adaptive_size, adaptive_size, foreground_png, "--out", foreground_png)
        run(cwebp, "-quiet", "-lossless", foreground_png, "-o", foreground_webp)
        run(cwebp, "-quiet", "-lossless", "-resize", adaptive_size, adaptive_size, background_ppm, "-o", background_webp)
        for role in ("legacy", "round"):
            result[ICON_PATHS[role][density]] = legacy_webp
        result[ICON_PATHS["background"][density]] = background_webp
        result[ICON_PATHS["foreground"][density]] = foreground_webp
    return result
