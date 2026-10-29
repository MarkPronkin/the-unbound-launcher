"""Execute all capability-routing trampolines and verify the private EGL probe."""
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

from app_color import LIBRARY, patch_color_native
from app_surface_routing import native_spec, patch_surface_routing, verify_surface_routing
from build_embedded_adb import BASE, ROOT
from experimental_stereo import patch_stereo_native
from overlay_routing import patch_overlay_routing
from startup_tutorial import patch_startup_tutorial
from xreal_telemetry import file_offset
from test_universal_color import GL_STUB, object_text

sys.path.insert(0,str(ROOT/'artifacts/tools/python'))
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_INTR
from unicorn.arm64_const import *


class AppSurfaceRoutingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as archive:
            cls.base=archive.read(LIBRARY)
        cls.original=patch_overlay_routing(patch_stereo_native(patch_startup_tutorial(patch_color_native(cls.base))))
        cls.spec=native_spec()
        cls.patched=patch_surface_routing(cls.original)

    def test_scope_restoration_and_rejection(self):
        self.assertEqual(patch_surface_routing(self.patched,restore=True),self.original)
        targets=self.spec['targets']
        spans=[(file_offset(self.original,t['address']),len(bytes.fromhex(t['replacement']))) for t in targets]
        cursor=0
        for at,size in sorted(spans):
            self.assertEqual(self.patched[cursor:at],self.original[cursor:at]);cursor=at+size
        self.assertEqual(self.patched[cursor:],self.original[cursor:])
        self.assertEqual(self.patched[0x12ca1e4:0x12ca1e8],bytes.fromhex('c0035fd6'))
        self.assertLess(self.spec['cave']+self.spec['code_bytes'],self.spec['cave_limit'])
        self.assertTrue(verify_surface_routing(self.patched)['hardware_override_gpus_preserve_overlay'])
        with self.assertRaisesRegex(ValueError,'Unexpected native library'):patch_surface_routing(self.patched)
        damaged=bytearray(self.patched);damaged[-1]^=1
        with self.assertRaisesRegex(ValueError,'Unrelated native changes'):patch_surface_routing(damaged,restore=True)
        restored=patch_overlay_routing(patch_surface_routing(self.patched,restore=True),restore=True)
        restored=patch_startup_tutorial(patch_stereo_native(restored,restore=True),restore=True)
        self.assertEqual(patch_color_native(restored,restore=True),self.base)

    def test_assembly_is_exact_and_has_no_unresolved_relocations(self):
        with tempfile.TemporaryDirectory() as work:
            obj=Path(work)/'routing.o'
            subprocess.run(['clang','-target','aarch64-linux-android','-c',str(ROOT/'tools/app_surface_routing.S'),'-o',str(obj)],check=True)
            self.assertEqual(object_text(obj.read_bytes()),bytes.fromhex(self.spec['targets'][-1]['replacement']))

    def test_every_native_entry_preserves_registers_flags_and_original_hardware_behavior(self):
        cases=[None,b'',b'\0',b'\1',b'\2',b'\xff',b'\1\0',b'\1\1','read-error']
        for index,site in enumerate(self.spec['targets'][:-1]):
            for protected in (0,1):
                for flag in cases:
                    with self.subTest(entry=index,protected=protected,flag=flag):
                        self.execute(site,index,protected,flag)

    def execute(self,site,index,protected,flag):
        cpu=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
        cave=self.spec['cave'];code=bytes.fromhex(self.spec['targets'][-1]['replacement'])
        cpu.mem_map(cave&~0xfff,0x1000);cpu.mem_write(cave,code)
        at=site['address'];cpu.mem_map(at&~0xfff,0x1000);cpu.mem_write(at,bytes.fromhex(site['replacement']))
        owner=0x700000;cpu.mem_map(owner,0x1000);cpu.mem_write(owner,b'\x7f'*0x1000)
        cpu.mem_map(0x800000,0x10000);cpu.reg_write(UC_ARM64_REG_SP,0x808000)
        regs=[UC_ARM64_REG_X0+i for i in range(29)]+[UC_ARM64_REG_X29,UC_ARM64_REG_X30]
        for number,reg in enumerate(regs):cpu.reg_write(reg,0x100000000+number*0x10101)
        cpu.reg_write(regs[(19,20,20,21,21,22)[index]],owner)
        cpu.reg_write(UC_ARM64_REG_X8,protected)
        cpu.reg_write(UC_ARM64_REG_NZCV,0xa0000000)
        before=[cpu.reg_read(reg) for reg in regs]
        calls=[]
        def syscall(machine,interrupt,data):
            op=machine.reg_read(UC_ARM64_REG_X8);calls.append(op)
            if op==56:
                path=bytes(machine.mem_read(machine.reg_read(UC_ARM64_REG_X1),100)).split(b'\0')[0]
                self.assertEqual(path,b'/data/user/0/com.xreal.evapro.nebula/files/unbound-app-surface-fallback')
                result=-2 if flag is None else 17
            elif op==63:
                self.assertEqual(machine.reg_read(UC_ARM64_REG_X0),17)
                self.assertEqual(machine.reg_read(UC_ARM64_REG_X2),2)
                if flag=='read-error':result=-5
                else:
                    value=flag[:2];result=len(value)
                    if value:machine.mem_write(machine.reg_read(UC_ARM64_REG_X1),value)
            elif op==57:
                self.assertEqual(machine.reg_read(UC_ARM64_REG_X0),17);result=0
            else:raise AssertionError('Unexpected syscall '+str(op))
            machine.reg_write(UC_ARM64_REG_X0,result&0xffffffffffffffff)
        cpu.hook_add(UC_HOOK_INTR,syscall)
        cpu.emu_start(at,at+4,count=250)
        self.assertEqual(cpu.reg_read(UC_ARM64_REG_PC),at+4)
        self.assertEqual([cpu.reg_read(reg) for reg in regs],before)
        self.assertEqual(cpu.reg_read(UC_ARM64_REG_SP),0x808000)
        self.assertEqual(cpu.reg_read(UC_ARM64_REG_NZCV),0xa0000000)
        expected=0 if protected and flag==b'\1' else protected
        data=bytes(cpu.mem_read(owner,0x1000));reference=bytearray(b'\x7f'*0x1000);reference[0x72]=expected
        self.assertEqual(data,bytes(reference))
        self.assertEqual(calls,[] if not protected else ([56] if flag is None else [56,63,57]))

    def test_java_capability_probe_and_persistence(self):
        java=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip())/'bin'
        stubs={
            'android/content/Context.java':'package android.content; public class Context {public java.io.File dir;public java.io.File getFilesDir(){return dir;}}',
            'android/util/Log.java':'package android.util; public class Log {public static int i(String a,String b){return 0;}public static int w(String a,String b){return 0;}public static int w(String a,String b,Throwable c){return 0;}}',
            'android/util/AtomicFile.java':'''package android.util; import java.io.*; public class AtomicFile {
                File f;public AtomicFile(File v){f=v;}public FileOutputStream startWrite()throws Exception{return new FileOutputStream(f);}
                public void finishWrite(FileOutputStream s)throws Exception{s.close();}public void failWrite(FileOutputStream s){try{s.close();}catch(Exception e){}}}''',
            'android/opengl/GLES20.java':GL_STUB,
            'android/opengl/EGLConfig.java':'package android.opengl;public class EGLConfig{}',
            'android/opengl/EGLContext.java':'package android.opengl;public class EGLContext{}',
            'android/opengl/EGLDisplay.java':'package android.opengl;public class EGLDisplay{}',
            'android/opengl/EGLSurface.java':'package android.opengl;public class EGLSurface{}',
            'android/opengl/EGL14.java':'''package android.opengl;public class EGL14 {
              public static final int EGL_DEFAULT_DISPLAY=0,EGL_RENDERABLE_TYPE=1,EGL_SURFACE_TYPE=2,EGL_PBUFFER_BIT=3,EGL_RED_SIZE=4,EGL_GREEN_SIZE=5,EGL_BLUE_SIZE=6,EGL_NONE=7,EGL_CONTEXT_CLIENT_VERSION=8,EGL_WIDTH=9,EGL_HEIGHT=10;
              public static final EGLDisplay EGL_NO_DISPLAY=new EGLDisplay();public static final EGLContext EGL_NO_CONTEXT=new EGLContext();public static final EGLSurface EGL_NO_SURFACE=new EGLSurface();
              public static String fail="";public static int destroyedContexts,destroyedSurfaces,terminated,released;
              public static void reset(String f){fail=f;destroyedContexts=destroyedSurfaces=terminated=released=0;}
              public static EGLDisplay eglGetDisplay(int n){return fail.equals("display")?EGL_NO_DISPLAY:new EGLDisplay();}
              public static boolean eglInitialize(EGLDisplay d,int[] a,int x,int[] b,int y){return !fail.equals("initialize");}
              public static boolean eglChooseConfig(EGLDisplay d,int[] a,int x,EGLConfig[] c,int at,int size,int[] count,int y){c[0]=new EGLConfig();count[0]=1;return !fail.equals("config");}
              public static EGLContext eglCreateContext(EGLDisplay d,EGLConfig c,EGLContext share,int[] a,int x){if(share!=EGL_NO_CONTEXT)throw new AssertionError();return fail.equals("context")?EGL_NO_CONTEXT:new EGLContext();}
              public static EGLSurface eglCreatePbufferSurface(EGLDisplay d,EGLConfig c,int[] a,int x){return fail.equals("surface")?EGL_NO_SURFACE:new EGLSurface();}
              public static boolean eglMakeCurrent(EGLDisplay d,EGLSurface a,EGLSurface b,EGLContext c){return c==EGL_NO_CONTEXT||!fail.equals("current");}
              public static boolean eglDestroySurface(EGLDisplay d,EGLSurface s){destroyedSurfaces++;return true;}
              public static boolean eglDestroyContext(EGLDisplay d,EGLContext c){destroyedContexts++;return true;}
              public static boolean eglTerminate(EGLDisplay d){terminated++;return true;}
              public static boolean eglReleaseThread(){released++;return true;}
            }''',
        }
        with tempfile.TemporaryDirectory() as directory:
            work=Path(directory)
            for name,source in stubs.items():
                path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source)
            sources=list(work.rglob('*.java'))+[ROOT/'port-client/src/dev/beampro/port/AppColorCompat.java',
                ROOT/'port-client/src/dev/beampro/port/AppSurfacePolicy.java',ROOT/'tools/AppSurfacePolicyTest.java']
            subprocess.run([str(java/'javac'),'--release','8','-d',work,*map(str,sources)],check=True)
            result=subprocess.check_output([str(java/'java'),'-cp',work,'dev.beampro.port.AppSurfacePolicyTest',directory],text=True)
            self.assertIn('PASS app surface capability',result);print(result.strip())


if __name__=='__main__':unittest.main()
