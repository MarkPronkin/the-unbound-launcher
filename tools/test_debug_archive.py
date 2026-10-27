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
  for(int i=0;i<6;i++)archive.network("snapshot-"+i+"\\n"+new String(new char[70_000]).replace('\0','z'));
  archive=new DebugArchive(root);file=archive.export("network snapshot export");
  try(ZipFile zip=new ZipFile(file)){
   String network=new String(zip.getInputStream(zip.getEntry("network-diagnostics.txt")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
   check(zip.size()==3,"network diagnostics added alongside existing logs and metadata");
   check(!network.contains("snapshot-2")&&network.contains("snapshot-3")&&network.contains("snapshot-5"),"retain the latest three snapshots across restarts");
   check(network.indexOf("snapshot-3")<network.indexOf("snapshot-5"),"failure snapshots precede later export snapshots");
   check(network.length()<193_000,"network history bounded independently of app log ring");
  }
  check(file.length()<=15_000_000,"network data and incompressible app logs fit the hard ZIP limit");
  for(int i=0;i<12;i++){random.nextBytes(chunk);archive.append(chunk,chunk.length);}
  check(archive.bytes()==12_000_000,"fill the complete app log budget for dump export stress test");
  byte[] dumpBytes=new byte[DisplayDiagnostics.MAX_BYTES];random.nextBytes(dumpBytes);
  for(int i=0;i<dumpBytes.length;i++)dumpBytes[i]=(byte)(32+(dumpBytes[i]&255)%95);
  String display=new String(dumpBytes,java.nio.charset.StandardCharsets.UTF_8);
  random.nextBytes(dumpBytes);for(int i=0;i<dumpBytes.length;i++)dumpBytes[i]=(byte)(32+(dumpBytes[i]&255)%95);
  String surface=new String(dumpBytes,java.nio.charset.StandardCharsets.UTF_8);
  file=archive.export("display snapshot export",display,surface);
  try(ZipFile zip=new ZipFile(file)){
   check(zip.size()==5,"both dumps alongside logs, metadata and network history");
   check(new String(zip.getInputStream(zip.getEntry("display.txt")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).equals(display),"display dump preserved in full at its budget");
   check(new String(zip.getInputStream(zip.getEntry("surfaceflinger.txt")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).equals(surface),"SurfaceFlinger dump preserved in full at its budget");
  }
  check(file.length()<=15_000_000,"maximum dumps plus network history and app ring fit ZIP cap");
  file=archive.export("oversized dump",display+display,"Helper unavailable: restart updated helper");
  try(ZipFile zip=new ZipFile(file)){
   byte[] dump=zip.getInputStream(zip.getEntry("display.txt")).readAllBytes();
   check(dump.length<=DisplayDiagnostics.MAX_BYTES&&new String(dump,java.nio.charset.StandardCharsets.UTF_8).contains("truncated"),"oversized dumps have an explicit bounded truncation notice");
   check(new String(zip.getInputStream(zip.getEntry("surfaceflinger.txt")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("Helper unavailable"),"diagnostic failure remains in usable ZIP");
  }
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
                *[str(ROOT / 'settings-ui/src/dev/beampro/settings' / name) for name in
                  ('DebugArchive.java', 'DisplayDiagnostics.java', 'NetworkDiagnostics.java')], str(test)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp,
                'dev.beampro.settings.DebugArchiveChecks', str(work / 'data')], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS debug archive checks', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
