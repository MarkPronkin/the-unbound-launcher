"""Audit the stock shader substitution and compile every affected GLES variant."""
import copy
import math
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

from app_color import APP_SHADER, SOFTWARE_SHADER, software_app_shader, verify_app_shader
from build_embedded_adb import BASE, ROOT

sys.path.insert(0,str(ROOT/'artifacts/tools/python'))
import UnityPy
from UnityPy.helpers import CompressionHelper
from UnityPy.streams import EndianBinaryReader
from UnityPy.export.ShaderConverter import ShaderProgram


class Archive:
    def __init__(self,values):self.values=values
    def read(self,name):return self.values[name]


class AppColorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as z:
            cls.raw={n:z.read(n) for n in (APP_SHADER,SOFTWARE_SHADER)}
        cls.objects={n:UnityPy.load(raw).objects[0] for n,raw in cls.raw.items()}
        cls.programs={}
        for name,obj in cls.objects.items():
            shader=obj.read(check_read=False)
            blob=CompressionHelper.decompress_lz4(bytes(shader.compressedBlob),shader.decompressedLengths[0][0])
            cls.programs[name]=ShaderProgram(EndianBinaryReader(blob,endian='<'),obj.version).m_SubPrograms

    def test_opt_in_and_exact_stock_payload(self):
        base=Archive(self.raw)
        self.assertFalse(verify_app_shader(base,base)['pixel_software_decode_enabled'])
        candidate=Archive(dict(self.raw,**{APP_SHADER:software_app_shader(base)}))
        self.assertTrue(verify_app_shader(candidate,base)['pixel_software_decode_enabled'])
        candidate.values[APP_SHADER]=candidate.values[APP_SHADER][:-1]+b'!'
        with self.assertRaises(ValueError):verify_app_shader(candidate,base)

    def test_all_48_variants_only_add_rgb_decode(self):
        before=self.programs[APP_SHADER];after=self.programs[SOFTWARE_SHADER]
        # Entry zero is the Unity China baked-shader metadata record, not a shader.
        self.assertEqual(len(before),49);self.assertEqual(len(after),49)
        for old,new in zip(before[1:],after[1:]):
            self.assertEqual(old.m_Keywords,new.m_Keywords)
            self.assertEqual(old.m_ProgramType,new.m_ProgramType)
            original=old.m_ProgramCode
            needle=b'gl_FragColor = vec4(result, 1.0f);'
            self.assertEqual(original.count(needle),1)
            self.assertEqual(original.replace(needle,b'gl_FragColor = vec4(GammaToLinearSpace(result), 1.0f);'),new.m_ProgramCode)

    def test_shader_state_and_properties_are_unchanged(self):
        values=[]
        for obj in self.objects.values():
            tree=copy.deepcopy(obj.read_typetree())
            for key in ('compressedBlob','compressedLengths','decompressedLengths'):tree.pop(key)
            for sub in tree['m_ParsedForm']['m_SubShaders']:
                for shader_pass in sub['m_Passes']:
                    shader_pass.pop('m_EditorDataHash')
                    shader_pass['m_State'].pop('gpuProgramID')
            values.append(tree)
        self.assertEqual(*values)

    def test_software_decode_restores_sdr_ramp(self):
        def encode(x):return 12.92*x if x<=0.0031308 else 1.055*x**(1/2.4)-0.055
        def decode(x):return x*(x*(x*0.305306011+0.682171111)+0.012522878)
        errors=[abs(encode(decode(i/255))*255-i) for i in range(256)]
        self.assertLess(max(errors),5) # Stock SDK's inexpensive approximation, strongest error near black.
        self.assertLess(abs(encode(decode(128/255))*255-128),1)
        self.assertGreater(encode(128/255)*255,180) # The measured missing-decode path lifts midtones.

    def test_original_software_shader_compiles_every_variant(self):
        bundled=Path.home()/'Library/Android/sdk/emulator/lib64/vulkan/glslangValidator'
        with tempfile.TemporaryDirectory() as tmp:
            work=Path(tmp);compiler=work/'glslangValidator'
            shutil.copyfile(bundled,compiler);compiler.chmod(0o755)
            for index,program in enumerate(self.programs[SOFTWARE_SHADER][1:],1):
                source=program.m_ProgramCode.decode('utf-8')
                for stage,macro in [('vert','VERTEX'),('frag','FRAGMENT')]:
                    version,rest=source.split('\n',1)
                    file=work/(str(index)+'.'+stage)
                    file.write_text(version+'\n#define '+macro+' 1\n'+rest)
                    result=subprocess.run([str(compiler),'-S',stage,str(file)],text=True,capture_output=True)
                    self.assertEqual(result.returncode,0,f'variant {index} {stage}: '+result.stdout+result.stderr)


if __name__=='__main__':unittest.main()
