"""Exercise GPU decisions, shader variants, DEX scope and native selector ABI."""
import copy
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

from app_color import (APP_SHADER,SOFTWARE_SHADER,LIBRARY,OES_UI_SHADER,OES_STEREO_SHADER,
                       automatic_app_shader,direct_oes_shader,patch_color_native,patch_color_dex,native_spec)
from build_embedded_adb import BASE,ROOT
sys.path.insert(0,str(ROOT/'artifacts/tools/python'))
import UnityPy
from UnityPy.helpers import CompressionHelper
from UnityPy.streams import EndianBinaryReader
from UnityPy.export.ShaderConverter import ShaderProgram
from capstone import Cs,CS_ARCH_ARM64,CS_MODE_ARM


GL_STUB='''package android.opengl;
public class GLES20 {
 public static final int GL_TEXTURE_MIN_FILTER=10241,GL_TEXTURE_MAG_FILTER=10240,GL_TEXTURE_WRAP_S=10242,GL_TEXTURE_WRAP_T=10243,GL_LINEAR=9729,GL_CLAMP_TO_EDGE=33071,GL_NO_ERROR=0,GL_EXTENSIONS=7939,GL_RENDERER=7937;
 public static int calls,bound,filters,override,overrides,error;static String extensions;static boolean accept,query;
 public static void reset(String s,boolean a,boolean q){calls=bound=filters=override=overrides=error=0;extensions=s;accept=a;query=q;}
 public static void glGenTextures(int n,int[] ids,int at){calls++;ids[at]=41;}
 public static void glBindTexture(int target,int id){calls++;bound=target;if(id!=41)throw new AssertionError();}
 public static void glTexParameteri(int target,int name,int value){calls++;if(target!=bound)throw new AssertionError();if(name==0x8fbf){overrides++;if(value==0||accept)override=value;else error=1280;}else filters++;}
 public static int glGetError(){calls++;int result=error;error=0;return result;}
 public static String glGetString(int name){calls++;return name==GL_EXTENSIONS?extensions:"Test GPU";}
 public static void glGetTexParameteriv(int target,int name,int[] out,int at){calls++;if(query)out[at]=override;else error=1280;}
}'''


class Archive:
    def __init__(self,values):self.values=values
    def read(self,name):return self.values[name]


def shader(raw):
    obj=UnityPy.load(raw).objects[0];tree=obj.read_typetree()
    blob=CompressionHelper.decompress_lz4(bytes(tree['compressedBlob']),tree['decompressedLengths'][0][0])
    return tree,ShaderProgram(EndianBinaryReader(blob,endian='<'),obj.version).m_SubPrograms


def object_text(raw):
    offset=struct.unpack_from('<Q',raw,40)[0]
    size,count,names_index=struct.unpack_from('<HHH',raw,58)
    sections=[struct.unpack_from('<IIQQQQIIQQ',raw,offset+i*size) for i in range(count)]
    names=sections[names_index];strings=raw[names[4]:names[4]+names[5]];text=None
    for section in sections:
        if section[1] in (4,9) and section[5]:raise AssertionError('Unresolved native relocation')
        if strings[section[0]:strings.index(b'\0',section[0])]==b'.text':text=raw[section[4]:section[4]+section[5]]
    if text is None:raise AssertionError('Missing native text')
    return text


class UniversalColorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as archive:
            cls.raw={name:archive.read(name) for name in (APP_SHADER,SOFTWARE_SHADER,OES_UI_SHADER,OES_STEREO_SHADER)}
            cls.native=archive.read(LIBRARY)
            cls.dex=archive.read('classes2.dex')
        cls.auto=automatic_app_shader(Archive(cls.raw))

    def test_gpu_capabilities_without_device_model_rules(self):
        java=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip())
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);(p/'android/opengl').mkdir(parents=True);(p/'android/util').mkdir(parents=True)
            (p/'android/opengl/GLES20.java').write_text(GL_STUB)
            (p/'android/util/Log.java').write_text('package android.util; public class Log {public static int i(String a,String b){return 0;}public static int w(String a,String b){return 0;}}')
            subprocess.run([str(java/'bin/javac'),'--release','8','-d',tmp,*map(str,p.rglob('*.java')),
                str(ROOT/'port-client/src/dev/beampro/port/AppColorCompat.java'),str(ROOT/'tools/AppColorCompatTest.java')],check=True)
            result=subprocess.check_output([str(java/'bin/java'),'-cp',tmp,'dev.beampro.port.AppColorCompatTest'],text=True)
            self.assertIn('app-color capability assertions',result)
            print(result.strip())

    def test_shader_uses_exact_stock_hardware_and_software_programs(self):
        before,hardware=shader(self.raw[APP_SHADER]);_,software=shader(self.raw[SOFTWARE_SHADER]);after,auto=shader(self.auto)
        self.assertEqual(len(auto),49)
        counts={False:0,True:0}
        for index,(h,s,a) in enumerate(zip(hardware,software,auto)):
            if index==0:continue # Unity China baked metadata, not a GPU program.
            decode='GAMMA_TO_LINEAR' in h.m_Keywords
            self.assertEqual(a.__dict__,(s if decode else h).__dict__)
            if index:counts[decode]+=1
        self.assertEqual(counts,{False:24,True:24})
        for tree in (before,after):
            for key in ('compressedBlob','compressedLengths','decompressedLengths'):tree.pop(key)
            for sub in tree['m_ParsedForm']['m_SubShaders']:
                for shader_pass in sub['m_Passes']:
                    shader_pass.pop('m_EditorDataHash');shader_pass['m_State'].pop('gpuProgramID')
        self.assertEqual(before,after)

    def test_all_auto_shader_stages_compile(self):
        _,programs=shader(self.auto)
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);compiler=p/'glslangValidator'
            shutil.copyfile(Path.home()/'Library/Android/sdk/emulator/lib64/vulkan/glslangValidator',compiler);compiler.chmod(0o755)
            for index,program in enumerate(programs[1:],1):
                version,rest=program.m_ProgramCode.decode().split('\n',1)
                for stage,macro in [('vert','VERTEX'),('frag','FRAGMENT')]:
                    source=p/f'{index}.{stage}';source.write_text(version+'\n#define '+macro+' 1\n'+rest)
                    result=subprocess.run([str(compiler),'-S',stage,str(source)],text=True,capture_output=True)
                    self.assertEqual(result.returncode,0,result.stdout+result.stderr)

    def test_dex_factory_and_getter_restore_all_original_definitions(self):
        java=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip())
        with tempfile.TemporaryDirectory() as tmp:
            out=patch_color_dex(self.dex,Path(tmp)/'patch',java)
            self.assertEqual(patch_color_dex(out,Path(tmp)/'restore',java,restore=True,baseline=self.dex),self.dex)
            with self.assertRaises(subprocess.CalledProcessError):patch_color_dex(out,Path(tmp)/'twice',java)

    def test_native_patch_is_reversible_and_does_not_reenable_analytics(self):
        out=patch_color_native(self.native)
        self.assertEqual(patch_color_native(out,restore=True),self.native)
        self.assertEqual(out[0x12caf68:0x12caf6c],bytes.fromhex('c0035fd6'))
        self.assertEqual(out[0x12cb274:0x12cb278],bytes.fromhex('c0035fd6'))
        self.assertLessEqual(0x12cb04c+native_spec()['oes_code_bytes'],0x12cb274)
        with self.assertRaises(ValueError):patch_color_native(out)
        corrupt=bytearray(out);corrupt[-1]^=1
        with self.assertRaises(ValueError):patch_color_native(corrupt,restore=True)

    def test_native_source_assembles_to_the_pinned_selector(self):
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp)/'selector.o'
            subprocess.run(['clang','-target','aarch64-linux-android','-c',str(ROOT/'tools/app_color_select.S'),'-o',str(output)],check=True)
            self.assertEqual(object_text(output.read_bytes()),bytes.fromhex(native_spec()['targets'][2]['replacement']))

    def test_native_selector_branches_and_abi(self):
        # Interpret the actual AArch64 instructions with mocked engine/JNI calls.
        # This bounded selector uses no floating point or arithmetic on image data.
        from color_selector_machine import exercise_selector
        code=bytes.fromhex(native_spec()['targets'][2]['replacement'])
        for mirror in (False,True):
            for fallback in (False,True):
                for present in (False,True):
                    exercise_selector(self,code,mirror,fallback,present)

    def test_direct_oes_windows_keep_hardware_decode_until_software_is_selected(self):
        active=b'col.xyz = mix(col.xyz, gammaToLinear(col.xyz), _DecodeGamma);'
        comment=b'//\t\t\t\t\tcol.xyz = gammaToLinear(col.xyz);'
        uniform=b'uniform float _DecodeGamma;'
        for name,program_id in ((OES_UI_SHADER,10011),(OES_STEREO_SHADER,10012)):
            original=self.raw[name];patched=direct_oes_shader(original)
            self.assertNotEqual(patched,original)
            stock,_=shader(original);stock_blob=CompressionHelper.decompress_lz4(bytes(stock['compressedBlob']),stock['decompressedLengths'][0][0])
            self.assertEqual(stock_blob.count(comment),6);self.assertNotIn(active,stock_blob)
            tree,programs=shader(patched)
            props=tree['m_ParsedForm']['m_PropInfo']['m_Props']
            self.assertEqual([prop['m_Name'] for prop in props],['_MainTex','_AlphaEdge','_Color','_DecodeGamma'])
            decode=props[-1]
            self.assertEqual(decode['m_Type'],2)
            self.assertEqual([decode[f'm_DefValue[{index}]'] for index in range(4)],[0.0,0.0,0.0,0.0])
            for sub in tree['m_ParsedForm']['m_SubShaders']:
                for shader_pass in sub['m_Passes']:self.assertEqual(shader_pass['m_State']['gpuProgramID'],program_id)
            blob=CompressionHelper.decompress_lz4(bytes(tree['compressedBlob']),tree['decompressedLengths'][0][0])
            self.assertEqual(struct.unpack_from('<I',blob,0)[0],7)
            self.assertEqual(blob.count(active),6);self.assertEqual(blob.count(uniform),6);self.assertNotIn(comment,blob)
            gles=[program for program in programs if int(program.m_ProgramType)==4]
            self.assertEqual(len(gles),6)
            for program in gles:
                source=bytes(program.m_ProgramCode)
                self.assertTrue(source.startswith(b'#version 300 es\n'))
                self.assertEqual(source.count(active),1);self.assertEqual(source.count(uniform),1);self.assertNotIn(comment,source)
                vertex,fragment=source.split(b'#ifdef FRAGMENT',1)
                self.assertNotIn(b'_DecodeGamma',vertex);self.assertEqual(fragment.count(uniform),1);self.assertIn(active,fragment)

    def test_direct_oes_fragment_stages_compile(self):
        with tempfile.TemporaryDirectory() as tmp:
            compiler=Path(tmp)/'glslangValidator'
            shutil.copyfile(Path.home()/'Library/Android/sdk/emulator/lib64/vulkan/glslangValidator',compiler);compiler.chmod(0o755)
            compiled=0
            for name in (OES_UI_SHADER,OES_STEREO_SHADER):
                _,stock=shader(self.raw[name]);_,programs=shader(direct_oes_shader(self.raw[name]))
                for index,program in enumerate(programs):
                    if int(program.m_ProgramType)!=4:continue
                    text=bytes(program.m_ProgramCode).decode();version,rest=text.split('\n',1)
                    # Stereo vertices already use layout(binding). This host glslang rejects that in the stock shader.
                    stages=[('frag','FRAGMENT')]
                    if 'layout(binding' not in text:stages.insert(0,('vert','VERTEX'))
                    else:self.assertEqual(bytes(stock[index].m_ProgramCode).decode().count('layout(binding'),text.count('layout(binding'))
                    for stage,macro in stages:
                        source=Path(tmp)/f'{Path(name).name}-{index}.{stage}'
                        source.write_text(version+'\n#define '+macro+' 1\n'+rest)
                        result=subprocess.run([str(compiler),'-S',stage,str(source)],text=True,capture_output=True)
                        self.assertEqual(result.returncode,0,result.stdout+result.stderr);compiled+=1
            self.assertEqual(compiled,18)

    def test_direct_oes_source_assembles_inside_the_analytics_gap(self):
        spec=native_spec();base=0x12cb04c
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp)/'oes.o'
            subprocess.run(['clang','-target','aarch64-linux-android','-c',str(ROOT/'tools/app_color_oes.S'),'-o',str(output)],check=True)
            text=object_text(output.read_bytes())
        code=bytes.fromhex(spec['targets'][6]['replacement'])
        self.assertEqual(text,code);self.assertEqual(len(text),spec['oes_code_bytes'])
        self.assertEqual(spec['targets'][6]['address'],base)
        self.assertLessEqual(base+len(text),0x12cb274)
        def branch(pc,target,link):
            word=(0x94000000 if link else 0x14000000)|(((target-pc)>>2)&0x03ffffff)
            return struct.pack('<I',word).hex()
        for index,entry,link in ((3,spec['app_oes_offset'],True),(4,spec['mirror_oes_offset'],True),(5,spec['change_oes_offset'],False)):
            target=spec['targets'][index]
            self.assertEqual(target['replacement'],branch(target['address'],base+entry,link))
        cpu=Cs(CS_ARCH_ARM64,CS_MODE_ARM);cpu.detail=True
        taken={instruction.address:instruction for instruction in cpu.disasm(text[:0x20],base)}
        self.assertEqual(taken[base+0x1c].mnemonic,'b')
        self.assertEqual(taken[base+0x1c].operands[0].imm,0x34ddd6c)

    def test_direct_oes_selector_sets_gamma_only_for_software_decode(self):
        from color_selector_machine import exercise_oes_selector
        code=bytes.fromhex(native_spec()['targets'][6]['replacement'])
        for kind in ('app','mirror','change'):
            for software in (False,True):
                for present in (False,True):
                    exercise_oes_selector(self,code,kind,software,present)


if __name__=='__main__':unittest.main()
