"""Test the actual debug ring with incompressible data, ZIP overhead and manifest grants."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from debug_manifest import PROVIDER, patch_debug_manifest
from test_display_startup import ROOT, JAVA
from build_embedded_adb import BASE
from verify_embedded_adb import elements, verify_attribute_order


class DebugArchiveTests(unittest.TestCase):
    def test_private_provider_registration_is_idempotent_and_preserves_manifest(self):
        with ZipFile(BASE) as archive:
            original = archive.read('AndroidManifest.xml')
        patched = patch_debug_manifest(original)
        before, after = elements(original), elements(patched)
        self.assertEqual([node for node in after if node[1].get('name') != PROVIDER], before)
        provider = next(attrs for tag, attrs in after if attrs.get('name') == PROVIDER)
        self.assertEqual(provider['exported'], 0)
        self.assertEqual(provider['grantUriPermissions'], 0xFFFFFFFF)
        self.assertEqual(provider['authorities'], 'com.xreal.evapro.nebula.unbound.debug')
        verify_attribute_order(patched)
        self.assertEqual(patch_debug_manifest(patched), patched)

    def test_ring_retains_recent_bytes_and_zip_always_fits_decimal_limit(self):
        source = r'''package dev.beampro.settings;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.zip.*;
public class DebugArchiveChecks {
 static int passed;static void check(boolean b,String m){if(!b)throw new AssertionError(m);passed++;}
 public static void main(String[] args)throws Exception{
  File root=new File(args[0]);DebugArchive archive=new DebugArchive(root);
  check(archive.bytes()==0,"empty by default");
  byte[] chunk=new byte[DebugArchive.CHUNK_BYTES];Random random=new Random(21);
  byte[] expected=new byte[DebugArchive.CHUNK_BYTES*DebugArchive.MAX_CHUNKS];
  for(int i=0;i<30;i++){
   random.nextBytes(chunk);archive.append(chunk,chunk.length);
   check(archive.bytes()<=12_000_000,"rolling bound during collection");
   if(i>=18)System.arraycopy(chunk,0,expected,(i-18)*chunk.length,chunk.length);
  }
  File file=archive.export("device metadata");
  check(file.length()<=15_000_000,"incompressible ZIP with headers fits 15 MB");
  check(DebugArchive.validName(file.getName()),"provider accepts exported filename");
  try(ZipFile zip=new ZipFile(file)){
   check(zip.size()==2,"report and app logs only");
   check(Arrays.equals(zip.getInputStream(zip.getEntry("app.log")).readAllBytes(),expected),"oldest data discarded, recent data intact and ordered");
  }
  archive=new DebugArchive(root);check(archive.bytes()==12_000_000,"restart preserves ring");
  archive.append(new byte[]{42},1);check(archive.bytes()==11_000_001,"restart rotates oldest chunk");
  for(int i=0;i<4;i++)archive.export("another snapshot");
  check(new File(root,"exports").listFiles().length==3,"export retention bounded");
  try{archive.export(new String(new char[64_001]).replace('\0','a'));throw new AssertionError("accepted oversize report");}catch(IOException expectedError){}
  check(new File(root,"exports").listFiles((d,n)->n.endsWith(".tmp")).length==0,"failed exports cleaned up");
  ByteArrayOutputStream output=new ByteArrayOutputStream();
  try(DebugArchive.LimitedOutput limited=new DebugArchive.LimitedOutput(output,10)){
   limited.write(new byte[9]);limited.write(1);
   try{limited.write(1);throw new AssertionError("cap not enforced");}catch(IOException expectedError){}
   check(output.size()==10,"hard cap never writes extra byte");
  }
  check(!DebugArchive.validName("../secrets.zip"),"no traversal filenames");
  System.out.println("PASS debug archive checks: "+passed);
 }
}'''
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            test = work / 'DebugArchiveChecks.java'
            test.write_text(source)
            subprocess.run([str(JAVA / 'bin/javac'), '-d', tmp,
                str(ROOT / 'settings-ui/src/dev/beampro/settings/DebugArchive.java'), str(test)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp,
                'dev.beampro.settings.DebugArchiveChecks', str(work / 'data')], capture_output=True, text=True, check=True)
            self.assertIn('PASS debug archive checks', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
