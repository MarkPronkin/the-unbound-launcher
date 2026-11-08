"""Audit optional stereo mode namespaces, selector patch scope and native bytes."""
from pathlib import Path
import hashlib
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib

from app_color import LIBRARY, patch_color_native
from build_embedded_adb import BASE
from experimental_stereo import ROOT, native_spec, patch_stereo_dex, patch_stereo_native
from startup_tutorial import patch_startup_tutorial
from test_display_startup import JAVA
from trackpad_taps import branch
from xreal_telemetry import file_offset

sys.path.insert(0, str(ROOT / 'artifacts/tools/python'))
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_INTR
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2,
                               UC_ARM64_REG_X8, UC_ARM64_REG_X29, UC_ARM64_REG_X30,
                               UC_ARM64_REG_SP, UC_ARM64_REG_PC)


def text_section(path):
    data = Path(path).read_bytes()
    table = struct.unpack_from('<Q', data, 40)[0]
    size, count, names_index = struct.unpack_from('<HHH', data, 58)
    names_section = struct.unpack_from('<IIQQQQIIQQ', data, table + size * names_index)
    names = data[names_section[4]:names_section[4] + names_section[5]]
    for index in range(count):
        section = struct.unpack_from('<IIQQQQIIQQ', data, table + size * index)
        if names[section[0]:].split(b'\0')[0] == b'.text':
            return data[section[4]:section[4] + section[5]]
    raise ValueError('Missing .text')


class ExperimentalStereoTests(unittest.TestCase):
    def test_mode_namespaces_and_flag_persistence(self):
        stubs = {
            'android/content/Context.java': '''package android.content; public class Context {
                public java.io.File dir; public String pkg="preview";
                public java.io.File getFilesDir(){return dir;} public String getPackageName(){return pkg;}}''',
            'android/util/Log.java': '''package android.util; public class Log {
                public static int w(String a,String b,Throwable c){return 0;}}''',
            'android/util/AtomicFile.java': '''package android.util; import java.io.*; public class AtomicFile {
                final File file;public AtomicFile(File f){file=f;}
                public byte[] readFully()throws Exception{return java.nio.file.Files.readAllBytes(file.toPath());}
                public FileOutputStream startWrite()throws Exception{return new FileOutputStream(file);}
                public void finishWrite(FileOutputStream s)throws Exception{s.close();}
                public void failWrite(FileOutputStream s)throws Exception{s.close();}}''',
        }
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            sources = []
            for name, code in stubs.items():
                path = work / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(code); sources.append(path)
            runner = work / 'StereoChecks.java'
            runner.write_text('''import android.content.Context;import dev.beampro.settings.ExperimentalStereo;import java.nio.file.*;
              public class StereoChecks {
                static class Base {Context mContext;Control mNRServiceControl=new Control();}
                static class Manager extends Base {} static class Control {boolean isEthernetSeries;}
                static void check(boolean value){if(!value)throw new AssertionError();}
                public static void main(String[] args)throws Exception {
                  Context context=new Context();context.dir=new java.io.File(args[0]);Manager manager=new Manager();manager.mContext=context;
                  check(!ExperimentalStereo.enabled(context));
                  for(boolean ethernet:new boolean[]{false,true}) {
                    manager.mNRServiceControl.isEthernetSeries=ethernet;
                    for(int mode=-1;mode<16;mode++)check(ExperimentalStereo.selectConfig(mode,manager)==mode);
                    ExperimentalStereo.save(context,true);check(ExperimentalStereo.enabled(context));
                    for(int mode=-1;mode<16;mode++) {
                      boolean mono=ethernet?mode>=1&&mode<=4:mode==1||mode==5||mode==10||mode==11;
                      check(ExperimentalStereo.selectConfig(mode,manager)==(mono?(ethernet?5:3):mode));
                    }
                    ExperimentalStereo.saveMono60(context,true);
                    check(ExperimentalStereo.mono60Enabled(context)&&!ExperimentalStereo.enabled(context));
                    for(int mode=-1;mode<16;mode++) {
                      boolean mono=ethernet?mode>=1&&mode<=4:mode==1||mode==5||mode==10||mode==11;
                      check(ExperimentalStereo.selectConfig(mode,manager)==(mono?1:mode));
                    }
                    ExperimentalStereo.save(context,false);check(ExperimentalStereo.mono60Enabled(context));
                    ExperimentalStereo.save(context,true);check(!ExperimentalStereo.mono60Enabled(context));
                    ExperimentalStereo.saveMono60(context,false);check(ExperimentalStereo.enabled(context));
                    ExperimentalStereo.save(context,false);check(!ExperimentalStereo.enabled(context));
                    for(int mode=-1;mode<16;mode++)check(ExperimentalStereo.selectConfig(mode,manager)==mode);
                  }
                  check(ExperimentalStereo.selectConfig(10,new Object())==10);
                  Path flag=context.dir.toPath().resolve("unbound-experimental-stereo");
                  Files.write(flag,new byte[]{2});
                  check(ExperimentalStereo.mono60Enabled(context)&&!ExperimentalStereo.enabled(context));
                  ExperimentalStereo.saveMono60(context,false);check(!ExperimentalStereo.mono60Enabled(context));
                  for(byte[] bad:new byte[][]{new byte[0],{3},{-1},{1,1},{2,2}}) {
                    Files.write(flag,bad);check(!ExperimentalStereo.enabled(context));
                    check(!ExperimentalStereo.mono60Enabled(context));
                    for(boolean ethernet:new boolean[]{false,true}) {
                      manager.mNRServiceControl.isEthernetSeries=ethernet;
                      for(int mode=-1;mode<16;mode++)check(ExperimentalStereo.selectConfig(mode,manager)==mode);
                    }
                    ExperimentalStereo.save(context,true);check(ExperimentalStereo.enabled(context));
                    ExperimentalStereo.save(context,false);check(!ExperimentalStereo.enabled(context));
                  }
                  context.pkg="dev.markpronkin.unbound";
                  try {ExperimentalStereo.save(context,true);throw new AssertionError("unsupported profile");}
                  catch(IllegalStateException expected){}
                  try {ExperimentalStereo.saveMono60(context,true);throw new AssertionError("unsupported profile");}
                  catch(IllegalStateException expected){}
                  System.out.println("PASS mode namespaces, unknown modes, opt-in persistence and invalid flags");
                }}''')
            sources += [runner, ROOT / 'settings-ui/src/dev/beampro/settings/ExperimentalStereo.java']
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', str(work), *map(str, sources)], check=True)
            result = subprocess.check_output([str(JAVA / 'bin/java'), '-cp', str(work), 'StereoChecks', str(work)], text=True)
            self.assertIn('PASS', result)

    def test_native_choices_and_file_failures_preserve_registers_and_stack(self):
        spec = native_spec()
        for flag in (None, b'', b'\0', b'\1', b'\2', b'\3', b'\xff', b'\1\1', b'\2\2', 'read-error'):
            for mode in range(-1, 16):
                with self.subTest(flag=flag, mode=mode):
                    cpu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
                    for address in (spec['wrapper'], spec['return']):
                        cpu.mem_map(address & ~4095, 4096)
                    cpu.mem_write(spec['wrapper'], bytes.fromhex(spec['replacement']))
                    cpu.mem_write(spec['return'], branch(spec['return'], spec['wrapper']))
                    cpu.mem_map(0x800000, 0x10000)
                    cpu.reg_write(UC_ARM64_REG_SP, 0x808000)
                    registers = [UC_ARM64_REG_X0 + index for index in range(29)] + [UC_ARM64_REG_X29, UC_ARM64_REG_X30]
                    for index, register in enumerate(registers):
                        cpu.reg_write(register, 0x100000000 + index * 0x10101)
                    cpu.reg_write(UC_ARM64_REG_X0, mode & 0xffffffff)
                    cpu.reg_write(UC_ARM64_REG_X30, 0x9000000)
                    before = [cpu.reg_read(register) for register in registers]
                    calls = []

                    def syscall(machine, interrupt, data):
                        op = machine.reg_read(UC_ARM64_REG_X8)
                        calls.append(op)
                        if op == 56:
                            path = bytes(machine.mem_read(machine.reg_read(UC_ARM64_REG_X1), 100)).split(b'\0')[0]
                            self.assertEqual(path, b'/data/user/0/com.xreal.evapro.nebula/files/unbound-experimental-stereo')
                            result = -2 if flag is None else 17
                        elif op == 63:
                            self.assertEqual(machine.reg_read(UC_ARM64_REG_X0), 17)
                            self.assertEqual(machine.reg_read(UC_ARM64_REG_X2), 2)
                            if flag == 'read-error':
                                result = -5
                            else:
                                value = flag[:2]
                                result = len(value)
                                if value:
                                    machine.mem_write(machine.reg_read(UC_ARM64_REG_X1), value)
                        elif op == 57:
                            self.assertEqual(machine.reg_read(UC_ARM64_REG_X0), 17)
                            result = 0
                        else:
                            raise AssertionError('Unexpected syscall ' + str(op))
                        machine.reg_write(UC_ARM64_REG_X0, result & 0xffffffffffffffff)

                    cpu.hook_add(UC_HOOK_INTR, syscall)
                    cpu.emu_start(spec['return'], 0x9000000, count=100)
                    expected = before[:]
                    mono = mode in (1, 5, 10, 11)
                    if mono and flag in (b'\1', b'\2'):
                        expected[0] = 3 if flag == b'\1' else 1
                    self.assertEqual([cpu.reg_read(register) for register in registers], expected)
                    self.assertEqual(cpu.reg_read(UC_ARM64_REG_SP), 0x808000)
                    self.assertEqual(cpu.reg_read(UC_ARM64_REG_PC), 0x9000000)
                    self.assertEqual(calls, [] if not mono else [56] if flag is None else [56, 63, 57])

    def test_dex_exact_scope_round_trip_and_double_patch_rejected(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes.dex')
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            patched = patch_stereo_dex(original, work / 'patch', JAVA)
            self.assertEqual(patch_stereo_dex(patched, work / 'restore', JAVA, restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_stereo_dex(patched, work / 'double', JAVA, baseline=original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_stereo_dex(patched, work / 'double-unpinned', JAVA)
            damaged = bytearray(patched.replace(b'XREALManager.java', b'XREALManager.javx', 1))
            self.assertNotEqual(damaged, patched)
            damaged[12:32] = hashlib.sha1(damaged[32:]).digest()
            struct.pack_into('<I', damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_stereo_dex(damaged, work / 'unrelated', JAVA, restore=True, baseline=original)

    def test_native_exact_scope_assembly_and_reject_unrelated_changes(self):
        with zipfile.ZipFile(BASE) as archive:
            original = patch_startup_tutorial(patch_color_native(archive.read(LIBRARY)))
        spec = native_spec()
        self.assertEqual(hashlib.sha256(original).hexdigest(), spec['baseline_sha256'])
        patched = patch_stereo_native(original)
        self.assertEqual(patch_stereo_native(patched, restore=True), original)
        # The original decision executes in full; only its final RET is replaced.
        start, end = file_offset(original, 0x22e2e3c), file_offset(original, spec['return'])
        self.assertEqual(patched[start:end], original[start:end])
        wrapper = file_offset(original, spec['wrapper'])
        self.assertEqual(patched[:wrapper], original[:wrapper])
        self.assertEqual(patched[wrapper + len(bytes.fromhex(spec['replacement'])):end],
                         original[wrapper + len(bytes.fromhex(spec['replacement'])):end])
        self.assertEqual(patched[end + 4:], original[end + 4:])
        with tempfile.TemporaryDirectory() as directory:
            obj = Path(directory) / 'filter.o'
            subprocess.run(['clang', '-target', 'aarch64-linux-android29', '-c',
                            str(ROOT / 'tools/experimental_stereo.S'), '-o', str(obj)], check=True)
            self.assertEqual(text_section(obj), bytes.fromhex(spec['replacement']))
        with self.assertRaises(ValueError): patch_stereo_native(patched)
        corrupt = bytearray(patched); corrupt[-1] ^= 1
        with self.assertRaises(ValueError): patch_stereo_native(corrupt, restore=True)


if __name__ == '__main__': unittest.main()
