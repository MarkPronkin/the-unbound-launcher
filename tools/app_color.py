"""Capability-selected app colors: retain hardware decode or enable shader decode.

The original software payload remains available for auditing older Pixel builds.
Universal builds pair a GL-state probe, per-material selection, and the existing
GAMMA_TO_LINEAR variants. No manufacturer or model names participate.
"""
import hashlib
import json
from functools import lru_cache
from pathlib import Path
import struct
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]
LIBRARY='lib/arm64-v8a/libil2cpp.so'

APP_SHADER = 'assets/bin/Data/49c045fd3d4b4ce45b97f3111d8a0ed8'
SOFTWARE_SHADER = 'assets/bin/Data/1296c2c63c9cea8469aa31beb05d3a93'
APP_SHA256 = '753801c2eced5d1cf391937f0c94e8371038bcebe934bace68089924e3ebfbd7'
SOFTWARE_SHA256 = 'fe9da9474cd6881db846df540f959184d618219b6df543cf8e99c7b76f58f851'


def software_app_shader(base):
    original=base.read(APP_SHADER);software=base.read(SOFTWARE_SHADER)
    if hashlib.sha256(original).hexdigest()!=APP_SHA256 or hashlib.sha256(software).hexdigest()!=SOFTWARE_SHA256:
        raise ValueError('Unexpected app copy shaders; refusing an unpinned color replacement')
    return software


def verify_app_shader(candidate,base):
    software=software_app_shader(base)
    original=base.read(APP_SHADER);actual=candidate.read(APP_SHADER)
    automatic=automatic_app_shader(base)
    if actual not in (original,software,automatic):raise ValueError('Unexpected app-window shader changes')
    if candidate.read(SOFTWARE_SHADER)!=software:raise ValueError('Original software shader was modified')
    return {'pixel_software_decode_enabled':actual==software,'automatic_decode_selection':actual==automatic,'resource':APP_SHADER,
            'software_variant_source':SOFTWARE_SHADER,'native_material_selector_changed':actual==automatic,
            'scope':'Per-texture GL capability; hardware or shader decode' if actual==automatic else
            ('Legacy Pixel-only software decode' if actual==software else 'Original hardware-decoding path')}


def automatic_app_shader(base):
    software=software_app_shader(base)
    return _automatic_shader(base.read(APP_SHADER),software)


@lru_cache(maxsize=1)
def _automatic_shader(original,software):
    sys.path.insert(0,str(ROOT/'artifacts/tools/python'))
    import UnityPy
    from UnityPy.helpers import CompressionHelper
    from UnityPy.streams import EndianBinaryReader
    from UnityPy.export.ShaderConverter import ShaderProgram
    env=UnityPy.load(original);obj=env.objects[0];tree=obj.read_typetree()
    alt=UnityPy.load(software).objects[0].read_typetree()
    assert tree['platforms']==[9] and tree['offsets']==[[0]]
    old=CompressionHelper.decompress_lz4(bytes(tree['compressedBlob']),tree['decompressedLengths'][0][0])
    new=CompressionHelper.decompress_lz4(bytes(alt['compressedBlob']),alt['decompressedLengths'][0][0])
    programs=ShaderProgram(EndianBinaryReader(old,endian='<'),obj.version).m_SubPrograms
    assert len(programs)==49
    first=struct.unpack_from('<I',old,4)[0]
    result=bytearray(old[:first]);changed=0
    for index,program in enumerate(programs):
        decode='GAMMA_TO_LINEAR' in program.m_Keywords
        source=new if decode else old
        offset,length,segment=struct.unpack_from('<III',source,4+index*12)
        assert segment==0
        struct.pack_into('<III',result,4+index*12,len(result),length,segment)
        result.extend(source[offset:offset+length]);changed+=decode
    assert changed==24
    compressed=CompressionHelper.compress_lz4(bytes(result))
    tree['compressedBlob']=list(compressed)
    tree['compressedLengths']=[[len(compressed)]];tree['decompressedLengths']=[[len(result)]]
    for sub in tree['m_ParsedForm']['m_SubShaders']:
        for shader_pass in sub['m_Passes']:
            digest=hashlib.md5(result).digest()
            shader_pass['m_EditorDataHash']=[{f'bytes[{i}]':v for i,v in enumerate(digest)}]
            shader_pass['m_State']['gpuProgramID']=10003
    obj.save_typetree(tree)
    return env.file.save()


def native_spec():return json.loads(Path(__file__).with_name('app_color_native.json').read_text())


def patch_color_native(original,*,restore=False):
    from xreal_telemetry import file_offset
    spec=native_spec();blob=bytearray(original)
    if not restore and hashlib.sha256(original).hexdigest()!=spec['baseline_sha256']:
        raise ValueError('Unexpected native library for color selection')
    for target in spec['targets']:
        offset=file_offset(original,target['address'])
        before,after=bytes.fromhex(target['original']),bytes.fromhex(target['replacement'])
        expected,replacement=(after,before) if restore else (before,after)
        if blob[offset:offset+len(expected)]!=expected:raise ValueError('Color selector instructions differ: '+target['name'])
        blob[offset:offset+len(expected)]=replacement
    if restore and hashlib.sha256(blob).hexdigest()!=spec['baseline_sha256']:raise ValueError('Unrelated native changes')
    return bytes(blob)


def patch_color_dex(original,build,java,env=None,*,restore=False,baseline=None):
    build=Path(build);build.mkdir(parents=True,exist_ok=True)
    source=build/'color-input.dex';reference=build/'color-baseline.dex';output=build/'color-output.dex'
    source.write_bytes(original);reference.write_bytes(original if baseline is None else baseline)
    command=[str(Path(java)/'bin/java'),'--class-path',str(ROOT/'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar'),str(ROOT/'tools/PatchAppColorDex.java')]
    subprocess.run([*command,'restore' if restore else 'patch',str(source),str(output),str(reference)],check=True,env=env)
    result=output.read_bytes()
    if not restore:
        restored=build/'color-restored.dex'
        subprocess.run([*command,'restore',str(output),str(restored),str(reference)],check=True,env=env)
        if restored.read_bytes()!=original:raise ValueError('Color hooks failed to restore baseline')
    return result
