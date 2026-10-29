"""Keep Android package and About versions in sync; derive the base from its APK."""
import json
from pathlib import Path
import struct
from branding import _append_pool_string, _chunks, _pool_strings

ROOT = Path(__file__).resolve().parents[1]


def _manifest_details(manifest):
    header = struct.unpack_from("<H", manifest, 2)[0]
    nodes = list(_chunks(manifest, header, len(manifest)))
    _, pool_at, _, pool_size = next(n for n in nodes if n[0] == 1)
    strings = _pool_strings(manifest[pool_at:pool_at + pool_size])
    _, at, resource_header, size = next(n for n in nodes if n[0] == 0x180)
    ids = struct.unpack_from("<" + "I" * ((size - resource_header) // 4), manifest, at + resource_header)
    attrs = {}
    for kind, at, node_header, _ in nodes:
        if kind != 0x102 or strings[struct.unpack_from("<I", manifest, at + node_header + 4)[0]] != "manifest":
            continue
        offset, stride, count = struct.unpack_from("<HHH", manifest, at + node_header + 8)
        if stride != 20:
            raise ValueError("Unexpected manifest attribute size")
        for index in range(count):
            position = at + node_header + offset + index * stride
            name = struct.unpack_from("<I", manifest, position + 4)[0]
            if name < len(ids):
                attrs[ids[name]] = position
    return header, nodes, pool_at, pool_size, strings, attrs


def manifest_version(manifest):
    *_, strings, attrs = _manifest_details(manifest)
    code, name = attrs[0x0101021B], attrs[0x0101021C]
    if manifest[code + 15] != 0x10 or manifest[name + 15] != 3:
        raise ValueError("Expected literal Android package versions")
    return {"version_name": strings[struct.unpack_from("<I", manifest, name + 16)[0]],
            "version_code": struct.unpack_from("<I", manifest, code + 16)[0]}


def load_version():
    version = json.loads((ROOT / "version.json").read_text())
    if not isinstance(version["version_name"], str) or not version["version_name"].strip():
        raise ValueError("An app version name is required")
    if type(version["version_code"]) is not int or not 0 < version["version_code"] <= 2100000000:
        raise ValueError("Invalid Android version code")
    return version


def patch_manifest_version(manifest, version):
    header, nodes, pool_at, pool_size, strings, attrs = _manifest_details(manifest)
    pool = manifest[pool_at:pool_at + pool_size]
    if version["version_name"] in strings:
        name_index = strings.index(version["version_name"])
    else:
        name_index = len(strings)
        pool = _append_pool_string(pool, version["version_name"])
    changed = bytearray(manifest)
    name, code = attrs[0x0101021C], attrs[0x0101021B]
    struct.pack_into("<I", changed, name + 8, name_index)
    struct.pack_into("<I", changed, name + 16, name_index)
    struct.pack_into("<I", changed, code + 8, 0xFFFFFFFF)
    struct.pack_into("<I", changed, code + 16, version["version_code"])
    output = bytearray(changed[:header])
    for kind, at, _, size in nodes:
        output += pool if at == pool_at else changed[at:at + size]
    struct.pack_into("<I", output, 4, len(output))
    if manifest_version(output) != version:
        raise ValueError("Package version patch did not round-trip")
    return bytes(output)


def write_java_version(directory, version, base):
    path = directory / "dev/beampro/settings/AppVersion.java"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("package dev.beampro.settings;\n\n"
                    "/** Generated from version.json and the verified base APK. */\n"
                    "public final class AppVersion {\n"
                    f"    public static final String NAME = {json.dumps(version['version_name'])};\n"
                    f"    public static final int CODE = {version['version_code']};\n"
                    f"    public static final String BASE_NAME = {json.dumps(base['version_name'])};\n"
                    "    private AppVersion() {}\n}\n")
    return path
