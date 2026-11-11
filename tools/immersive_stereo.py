"""Three directly selectable immersive input formats, with pinned native changes."""
from functools import lru_cache
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

from xreal_telemetry import RETURN, file_offset

ROOT = Path(__file__).resolve().parents[1]
CODE_ADDRESS = 0x12CFCFC
BASELINE_SHA256 = "1b161e15cec73aef9b01b9a1944b13b58fdddfa233a186536a63269dc4acfefd"
MODES = {1: "Half SBS", 2: "Full SBS", 3: "Half Top/Bottom"}
SHADERS = {
    "assets/bin/Data/9170089354b04c2409fde6362cb92e81": "b015288f93924385309a30f1fda39b58897c54219fd2799344b53f3410a0135a",
    "assets/bin/Data/43d051e68c87e984d8d418c0e050a164": "31083553ca6bd18f5784d2b0e1148659ac7a5a31afef6a5de4509aac0f56c53e",
    "assets/bin/Data/7ba7a3e1df792664cb3985472446e5fc": "76b40d893e1ad90244c1e493525651763f86789d7291b734063d93b1150e7ac9",
}
# New input modes leave the shader's existing mono/SBS selection, edge mask,
# color, blending and soft-mask coordinates intact. Only the sample is cropped.
TOP_BOTTOM_UV = b"""
vec2 unboundTopBottomUV(vec2 uv, bool leftEye)
{
    if (_StereoMode == 3)
        return vec2(uv.x, uv.y * 0.5 + (leftEye ? 0.5 : 0.0));
    return uv;
}
"""


def native_spec():
    return json.loads(Path(__file__).with_name("immersive_stereo_native.json").read_text())


@lru_cache(maxsize=1)
def assemble_native():
    """Assemble resolved ELF text and its public entry offsets, without linking."""
    with tempfile.TemporaryDirectory() as temporary:
        obj = Path(temporary) / "immersive.o"
        subprocess.run(["clang", "-target", "aarch64-linux-android", "-c",
                        str(Path(__file__).with_suffix(".S")), "-o", str(obj)], check=True)
        raw = obj.read_bytes()
    start = struct.unpack_from("<Q", raw, 40)[0]
    size, count, names_index = struct.unpack_from("<HHH", raw, 58)
    sections = [struct.unpack_from("<IIQQQQIIQQ", raw, start + i * size) for i in range(count)]
    names = sections[names_index]
    strings = raw[names[4]:names[4] + names[5]]
    code = None
    symbols = {}
    for section in sections:
        if section[1] in (4, 9) and section[5]:
            raise ValueError("Unresolved immersive stereo relocation")
        name = strings[section[0]:strings.index(b"\0", section[0])]
        if name == b".text":
            code = raw[section[4]:section[4] + section[5]]
        if section[1] == 2:
            table = sections[section[6]]
            labels = raw[table[4]:table[4] + table[5]]
            for at in range(section[4], section[4] + section[5], section[9]):
                name_at, info, _, defined, value, _ = struct.unpack_from("<IBBHQQ", raw, at)
                if info >> 4 == 1 and defined:
                    symbols[labels[name_at:labels.index(b"\0", name_at)].decode()] = value
    if code is None:
        raise ValueError("Missing immersive stereo instructions")
    return code, symbols


def branch(address, target, link=False):
    delta = target - address
    if delta % 4 or not -0x8000000 <= delta < 0x8000000:
        raise ValueError("Invalid immersive stereo branch")
    return struct.pack("<I", (0x94000000 if link else 0x14000000) | ((delta >> 2) & 0x3ffffff))


def native_targets():
    spec = native_spec()
    code, symbols = assemble_native()
    if hashlib.sha256(code).hexdigest() != spec["payload_sha256"]:
        raise ValueError("Immersive stereo assembly differs from the audited payload")
    result = []
    for target in spec["targets"]:
        if "entry" in target:
            after = branch(target["address"], CODE_ADDRESS + symbols[target["entry"]], target.get("link", False))
        elif target.get("payload"):
            after = code
        else:
            after = bytes.fromhex(target["replacement"])
        before = bytes.fromhex(target["original"])
        if len(before) != len(after):
            raise ValueError("Immersive stereo target length differs: " + target["name"])
        result.append((target["name"], target["address"], before, after))
    return result


def patch_immersive_stereo(original, *, restore=False):
    if not restore and hashlib.sha256(original).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unexpected native library for immersive stereo")
    blob = bytearray(original)
    for guard in native_spec()["disabled_entries"]:
        at = file_offset(blob, guard)
        if blob[at:at + 4] != RETURN:
            raise ValueError("Immersive stereo requires disabled analytics entries")
    for name, address, before, after in native_targets():
        expected, replacement = (after, before) if restore else (before, after)
        at = file_offset(blob, address)
        if blob[at:at + len(expected)] != expected:
            raise ValueError("Unexpected immersive stereo instructions: " + name)
        blob[at:at + len(expected)] = replacement
    if restore and hashlib.sha256(blob).hexdigest() != BASELINE_SHA256:
        raise ValueError("Unrelated native changes in immersive stereo")
    return bytes(blob)


def verify_immersive_stereo(blob):
    patch_immersive_stereo(blob, restore=True)
    return {"immersive_input_modes": list(MODES.values()), "selector": "three visible direct choices plus Close",
            "top_eye": "left", "full_top_bottom_removed": True,
            "saved_full_top_bottom_migrates_to_half": True,
            "half_top_bottom_hardware_validation": "wearer-confirmed in build 2970",
            "picker_hardware_validation": "pending"}


def _patch_fragment(code):
    sample = b"texture(_MainTex, u_xlat1.yw)"
    if sample not in code:
        return code
    # Each GLES record contains separately guarded vertex and fragment stages.
    if code.count(b"#ifdef FRAGMENT") != 1:
        raise ValueError("Unexpected immersive fragment guard")
    vertex, fragment = code.split(b"#ifdef FRAGMENT", 1)
    return vertex + b"#ifdef FRAGMENT" + _patch_fragment_stage(fragment)


def _patch_fragment_stage(code):
    sample = b"texture(_MainTex, u_xlat1.yw)"
    if code.count(sample) != 1 or code.count(b"void main()") != 1 or b"uniform \tint _StereoMode;" not in code:
        raise ValueError("Unexpected immersive app fragment")
    if b"vs_BLENDINDICES0" in code:
        eye = b"(vs_BLENDINDICES0 == uint(0))"
    else:
        if b"hlslcc_mtx4x4unity_MatrixInvV" not in code:
            raise ValueError("Missing multipass eye selection")
        # Use the same left/right camera test as the original SBS shader.
        eye = (b"(distance(_WorldCameraPosition - _WorldCameraRight, hlslcc_mtx4x4unity_MatrixInvV[3].xyz)"
               b" < distance(_WorldCameraPosition + _WorldCameraRight, hlslcc_mtx4x4unity_MatrixInvV[3].xyz))")
    return code.replace(b"void main()", TOP_BOTTOM_UV + b"void main()", 1).replace(
        sample, b"texture(_MainTex, unboundTopBottomUV(u_xlat1.yw, " + eye + b"))", 1)


def shader_records(raw):
    """Read the GPU table directly; UnityPy's exporter misreads these headers."""
    sys.path.insert(0, str(ROOT / "artifacts/tools/python"))
    import UnityPy
    from UnityPy.helpers import CompressionHelper
    env = UnityPy.load(raw)
    obj = next(o for o in env.objects if o.type.name == "Shader")
    tree = obj.read_typetree()
    if tree["platforms"] != [9] or tree["offsets"] != [[0]]:
        raise ValueError("Unexpected immersive shader platform layout")
    blob = CompressionHelper.decompress_lz4(bytes(tree["compressedBlob"]), tree["decompressedLengths"][0][0])
    entries = [struct.unpack_from("<III", blob, 4 + index * 12)
               for index in range(struct.unpack_from("<I", blob)[0])]
    for offset, length, segment in entries:
        if segment or offset + length > len(blob):
            raise ValueError("Invalid immersive shader program extent")
    return env, obj, tree, blob, entries


def program_source(record):
    starts = [at for marker in (b"#ifdef VERTEX", b"#ifdef FRAGMENT")
              if (at := record.find(marker)) >= 4]
    if not starts:
        return None
    start = min(starts)
    length = struct.unpack_from("<I", record, start - 4)[0]
    end = (start + length + 3) & ~3
    if end > len(record):
        raise ValueError("Immersive GLSL source extends beyond its program")
    return start, record[start:start + length], record[end:]


def rewrite_program(record):
    source = program_source(record)
    if source is None:
        return record
    start, code, tail = source
    rewritten = _patch_fragment(code)
    if rewritten == code:
        return record
    body = record[:start - 4] + struct.pack("<I", len(rewritten)) + rewritten
    return body + bytes((-len(body)) % 4) + tail


@lru_cache(maxsize=3)
def patch_immersive_shader(original):
    digest = hashlib.sha256(original).hexdigest()
    if digest not in SHADERS.values():
        raise ValueError("Unexpected immersive app shader")
    env, obj, tree, blob, entries = shader_records(original)
    from UnityPy.helpers import CompressionHelper
    result = bytearray(blob[:entries[0][0]])
    changed = 0
    for index, (offset, length, segment) in enumerate(entries):
        record = blob[offset:offset + length]
        rewritten = rewrite_program(record)
        changed += rewritten != record
        record = rewritten
        struct.pack_into("<III", result, 4 + index * 12, len(result), len(record), segment)
        result.extend(record)
    expected = 96 if digest == SHADERS["assets/bin/Data/9170089354b04c2409fde6362cb92e81"] else 32
    # Each hardware tier includes normal and soft-mask fragments, in mono,
    # multipass, instanced and multiview configurations.
    if changed != expected:
        raise ValueError(f"Unexpected immersive fragment count: {changed} (expected {expected})")
    compressed = CompressionHelper.compress_lz4(bytes(result))
    tree["compressedBlob"] = list(compressed)
    tree["compressedLengths"] = [[len(compressed)]]
    tree["decompressedLengths"] = [[len(result)]]
    for sub in tree["m_ParsedForm"]["m_SubShaders"]:
        for shader_pass in sub["m_Passes"]:
            digest_bytes = hashlib.md5(result).digest()
            shader_pass["m_EditorDataHash"] = [{f"bytes[{i}]": v for i, v in enumerate(digest_bytes)}]
            shader_pass["m_State"]["gpuProgramID"] = 10031 + list(SHADERS.values()).index(digest)
    obj.save_typetree(tree)
    return env.file.save()


def verify_immersive_shaders(candidate, base):
    from unity_shader_loader import ENGINE, UnityProgramReader
    reader = UnityProgramReader(candidate.read(ENGINE))
    counts = {}
    for name in SHADERS:
        raw = candidate.read(name)
        if raw != patch_immersive_shader(base.read(name)):
            raise ValueError("Immersive top/bottom shader was not packaged: " + name)
        _, _, _, blob, entries = shader_records(raw)
        loaded = 0
        for offset, length, _ in entries:
            record = blob[offset:offset + length]
            # The first sixteen records describe constant buffers, not GLSL.
            if program_source(record) is None:
                continue
            if not reader.accepts(record):
                raise ValueError("Unity native loader rejects an immersive GLSL record")
            loaded += 1
        counts[name] = loaded
    return {"top_bottom_app_shaders": list(SHADERS), "unity_native_programs_loaded": counts,
            "mono_sbs_masks_and_colors_preserved": True}
