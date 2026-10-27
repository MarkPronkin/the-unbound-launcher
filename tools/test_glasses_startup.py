"""Reproduce the Sony/xbx a01+ unsupported query and retain real failure paths."""
import hashlib
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib

from build_embedded_adb import BASE
from glasses_startup import patch_glasses_startup
from test_display_startup import ROOT, JAVA, STUBS


class GlassesStartupTests(unittest.TestCase):
    def test_real_adapter_requires_native_unsupported_error_type_11_and_live_xreal_output(self):
        stubs = dict(STUBS)
        stubs.update({
            'com/xreal/glasses/api/Startup.java': '''package com.xreal.glasses.api;
              public class Startup {public static int error,reads;public static boolean broken;
              public static int nativeGetApiErrorCode(){reads++;if(broken)throw new IllegalStateException();return error;}}''',
            'com/xreal/glassesdisplayplugevent/GlassesInitSetting.java': '''package com.xreal.glassesdisplayplugevent;
              public class GlassesInitSetting {public static int type=11;public static boolean broken;
              public static GlassesInitSetting getInstance(){return new GlassesInitSetting();}
              public int checkGlassesType(){if(broken)throw new IllegalStateException();return type;}}''',
        })
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, code in stubs.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(code)
                sources.append(path)
            sources += [ROOT / 'port-client/src/dev/beampro/port' / name
                        for name in ('DisplayStartupCompat.java', 'GlassesStartupCompat.java')]
            sources.append(ROOT / 'tools/GlassesStartupCompatTest.java')
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp, *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp, 'dev.beampro.port.GlassesStartupCompatTest'],
                                    capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS glasses startup assertions:', result.stdout)
            print(result.stdout.strip())

    def test_sdk_query_hook_restores_exactly_and_rejects_other_changes(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes2.dex')
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            patched = patch_glasses_startup(original, work / 'patch', JAVA)
            self.assertNotEqual(patched, original)
            self.assertEqual(patch_glasses_startup(patched, work / 'restore', JAVA,
                                                  restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_glasses_startup(patched, work / 'double', JAVA, baseline=original)
            damaged = bytearray(patched.replace(b'NRServiceControl.java', b'NRServiceControf.java', 1))
            self.assertNotEqual(damaged, patched)
            damaged[12:32] = hashlib.sha1(damaged[32:]).digest()
            struct.pack_into('<I', damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_glasses_startup(damaged, work / 'damaged', JAVA, restore=True, baseline=original)


if __name__ == '__main__':
    unittest.main()
