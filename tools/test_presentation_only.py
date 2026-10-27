from pathlib import Path
import hashlib
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib
from build_embedded_adb import BASE
from test_display_startup import JAVA, ROOT
from presentation_only import patch_presentation


class PresentationOnlyTests(unittest.TestCase):
    def test_direct_routing_and_restoration(self):
        with tempfile.TemporaryDirectory() as work:
            subprocess.run([str(JAVA/'bin/javac'),'-d',work,
                str(ROOT/'bridge/src/dev/beampro/bridge/ManagedDisplayPolicy.java'),
                str(ROOT/'tools/ManagedDisplayPolicyTest.java'),str(ROOT/'tools/PresentationPolicyTest.java')],check=True)
            result=subprocess.check_output([str(JAVA/'bin/java'),'-cp',work,'PresentationPolicyTest'],text=True)
            self.assertIn('PASS direct Presentation routing assertions:',result);print(result.strip())

    def test_mode_readiness(self):
        # Derive expectations from the independently dumped SDK enums, not from the adapter.
        import re
        sdk=(ROOT/'artifacts/tools/il2cppdumper-6.7.46/dump.cs').read_text()
        legacy=re.search(r'public enum EGlassMode .*?\n\{(.*?)\n\}',sdk,re.S).group(1)
        ethernet=re.search(r'public enum NREdid .*?\n\{(.*?)\n\}',sdk,re.S).group(1)
        vectors=[]
        for dimension,hz,mode in re.findall(r'Mode_(2D|3D)_(\d+)Hz = (\d+)',legacy):
            vectors.append(('false',int(mode),1920 if dimension=='2D' else 3840,int(hz)))
        for width,hz,mode in re.findall(r'NR_EDID_(1920|3840)_1080_(60|72|90|120) = (\d+)',ethernet):
            vectors.append(('true',int(mode),int(width),int(hz)))
        self.assertEqual(len(vectors),16)
        calls='\n'.join(f'vector({width},{hz});' for _,_,width,hz in vectors)
        # The renderer process cannot see the SDK's mode namespace or (filtered) target, so every
        # native mode of either namespace must be accepted and every fallback/alternative rejected.
        runner='''import dev.beampro.port.PresentationReadiness;
        public class ReadyChecks {static int n;
          static void check(boolean value){n++;if(!value)throw new AssertionError("case "+n);}
          static void vector(int width,float hz){
            check(PresentationReadiness.nativeOutput(width,1080,hz));
            check(PresentationReadiness.nativeOutput(width,1080,hz-.06f));
            check(!PresentationReadiness.nativeOutput(width,1080,hz+1));
            check(!PresentationReadiness.nativeOutput(width,1080,Float.NaN));
            check(!PresentationReadiness.nativeOutput(width,900,hz));
            check(!PresentationReadiness.nativeOutput(width,1080,hz-1.5f));
          }
          public static void main(String[] a){ VECTORS
            // Pixel 8 Pro / Android 17 with an XREAL One: VGA fallbacks and SurfaceFlinger alternative rates.
            check(!PresentationReadiness.nativeOutput(640,480,59.940475f));
            check(!PresentationReadiness.nativeOutput(850,480,59.940475f));
            check(!PresentationReadiness.nativeOutput(823,480,60));
            check(!PresentationReadiness.nativeOutput(3840,1080,20));
            check(!PresentationReadiness.nativeOutput(1920,1080,0));
            check(!PresentationReadiness.nativeOutput(1280,720,60));
            check(!PresentationReadiness.nativeOutput(2560,1080,60));
            System.out.println("PASS SDK-derived native output checks: "+n);
          }}'''.replace('VECTORS',calls)
        with tempfile.TemporaryDirectory() as work:
            path=Path(work)/'ReadyChecks.java';path.write_text(runner)
            subprocess.run([str(JAVA/'bin/javac'),'-d',work,str(path),
                str(ROOT/'port-client/src/dev/beampro/port/PresentationReadiness.java')],check=True)
            print(subprocess.check_output([str(JAVA/'bin/java'),'-cp',work,'ReadyChecks'],text=True).strip())

    def test_sdk_patch_restores_exact_baseline_and_rejects_unrelated_changes(self):
        with zipfile.ZipFile(BASE) as z: original=z.read('classes.dex')
        with tempfile.TemporaryDirectory() as directory:
            work=Path(directory)
            patched=patch_presentation(original,work/'patch',JAVA)
            self.assertEqual(patch_presentation(patched,work/'restore',JAVA,restore=True,baseline=original),original)
            with self.assertRaises(subprocess.CalledProcessError):patch_presentation(patched,work/'double',JAVA,baseline=original)
            damaged=bytearray(patched.replace(b'NRXRApp.java',b'NRXRApp.javx',1));self.assertNotEqual(damaged,patched)
            damaged[12:32]=hashlib.sha1(damaged[32:]).digest();struct.pack_into('<I',damaged,8,zlib.adler32(damaged[12:])&0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):patch_presentation(damaged,work/'unrelated',JAVA,restore=True,baseline=original)

if __name__=='__main__':unittest.main()
