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
  for(int i=0;i<6;i++)archive.link("link-snapshot-"+i+"\n"+display);
  archive=new DebugArchive(root);
  file=archive.export("display snapshot export",display,surface);
  try(ZipFile zip=new ZipFile(file)){
   check(zip.size()==6,"both dumps and retained link history alongside logs, metadata and network history");
   check(new String(zip.getInputStream(zip.getEntry("display.txt")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).equals(display),"display dump preserved in full at its budget");
   check(new String(zip.getInputStream(zip.getEntry("surfaceflinger.txt")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).equals(surface),"SurfaceFlinger dump preserved in full at its budget");
   String link=new String(zip.getInputStream(zip.getEntry("display-link.txt")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
   check(!link.contains("link-snapshot-2")&&link.contains("link-snapshot-3")&&link.contains("link-snapshot-5"),"latest three link snapshots survive restarts and later disconnects");
   check(link.indexOf("link-snapshot-3")<link.indexOf("link-snapshot-5"),"link snapshots retain chronological order");
   check(link.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<601_000&&link.contains("truncated"),"link history has an independent byte limit and explicit truncation");
  }
  check(file.length()<=15_000_000,"maximum dumps plus network and link histories and app ring fit ZIP cap");
  file=archive.export(new String(new char[64_000]).replace('\0','m'),display,surface);
  check(file.length()<=15_000_000,"full metadata budget also fits alongside maximum diagnostics");
  byte[] fullLogs;
  try(ZipFile zip=new ZipFile(file)){fullLogs=zip.getInputStream(zip.getEntry("app.log")).readAllBytes();}
  archive.startupBegin(true);
  archive.startupEvent("one","begin","XREAL 1S");
  String sdk="10-28 12:11:27.500  3557 25772 I XREAL   : [NRService] NRSDK Version: 3.1.2.20261009101910\n";
  byte[] sdkBytes=sdk.getBytes(java.nio.charset.StandardCharsets.UTF_8);archive.startupLog(sdkBytes,sdkBytes.length);
  for(int i=0;i<30;i++){
   if(i==10)archive.startupEvent("two","begin","retry after the first failure");
   byte[] body=new byte[7000];random.nextBytes(body);for(int j=0;j<body.length;j++)body[j]=(byte)(32+(body[j]&255)%95);
   String line="10-28 12:11:28.500  3557 25772 I UnboundConnect: "+(i==6?"333 get rsp timeout ":"Observed startup stage ")
       +new String(body,java.nio.charset.StandardCharsets.UTF_8)+"\n";
   byte[] encoded=line.getBytes(java.nio.charset.StandardCharsets.UTF_8);archive.startupLog(encoded,encoded.length);
  }
  for(int i=0;i<6;i++)archive.startupSnapshot("control-snapshot-"+i+"\n"+display.substring(0,32_000));
  for(int i=0;i<6;i++)archive.startupTrace("control-trace-"+i+"\n"+display.substring(0,30_000));
  archive=new DebugArchive(root);archive.startupBegin(false);
  file=archive.export(display.substring(0,64_000),display,surface);
  try(ZipFile zip=new ZipFile(file)){
   check(zip.size()==9,"startup timeline, control snapshots and traces coexist with all existing diagnostics");
   byte[] tail=zip.getInputStream(zip.getEntry("app.log")).readAllBytes();
   check(tail.length==11_620_000&&Arrays.equals(tail,Arrays.copyOfRange(fullLogs,380_000,fullLogs.length)),"startup data reserves 380 KB by exporting the newest app bytes in order");
   byte[] startup=zip.getInputStream(zip.getEntry("glasses-startup.txt")).readAllBytes();
   byte[] controls=zip.getInputStream(zip.getEntry("glasses-control.txt")).readAllBytes();
   byte[] traces=zip.getInputStream(zip.getEntry("glasses-control-trace.txt")).readAllBytes();
   check(startup.length<=165_000&&startup.length+controls.length+traces.length<=340_000,"all startup evidence fits inside its export reservation with room for ZIP headers");
   String trace=new String(traces,java.nio.charset.StandardCharsets.UTF_8);
   check(trace.contains("control-trace-0")&&!trace.contains("control-trace-1\n")&&!trace.contains("control-trace-3\n")
       &&trace.contains("control-trace-4")&&trace.contains("control-trace-5"),"the first trace since collection began stays beside the latest two");
   check(trace.indexOf("control-trace-0")<trace.indexOf("control-trace-4")&&trace.indexOf("control-trace-4")<trace.indexOf("control-trace-5"),"traces keep capture order");
   check(trace.contains("[truncated]")&&traces.length<=3*(24_000+40),"each trace has its own explicit byte limit");
   String timeline=new String(startup,java.nio.charset.StandardCharsets.UTF_8);
   check(timeline.contains("333 get rsp timeout")&&timeline.contains("333=1")&&timeline.contains("3.1.2.20261009101910"),"first failure and observed summary survive later retries and archive restart");
   String control=new String(controls,java.nio.charset.StandardCharsets.UTF_8);
   check(!control.contains("control-snapshot-2")&&control.contains("control-snapshot-3")&&control.contains("control-snapshot-5"),"retain only the latest three bounded control snapshots across restart");
   check(control.indexOf("control-snapshot-3")<control.indexOf("control-snapshot-5"),"control snapshots retain capture order");
  }
  check(archive.bytes()==12_000_000,"startup export reservation never shrinks the collection ring");
  archive.startupBegin(true);
  try(ZipFile zip=new ZipFile(archive.export("after reset"))){
   check(zip.getEntry("glasses-control-trace.txt")==null&&zip.getEntry("glasses-control.txt")==null,"starting a new collection removes old control snapshots and traces");
  }
  for(int i=0;i<6;i++)archive.startupSnapshot("control-snapshot-"+i+"\n"+display.substring(0,32_000));
  for(int i=0;i<6;i++)archive.startupTrace("control-trace-"+i+"\n"+display.substring(0,30_000));
  for(int i=0;i<30;i++){
   byte[] body=new byte[7000];random.nextBytes(body);for(int j=0;j<body.length;j++)body[j]=(byte)(32+(body[j]&255)%95);
   String line="10-28 12:11:28.500  3557 25772 I UnboundConnect: "+(i==6?"333 get rsp timeout ":"Observed startup stage ")+new String(body,java.nio.charset.StandardCharsets.UTF_8)+"\n";
   byte[] encoded=line.getBytes(java.nio.charset.StandardCharsets.UTF_8);archive.startupLog(encoded,encoded.length);
  }
  check(file.length()<=15_000_000,"maximum startup evidence, full existing diagnostics and metadata fit the ZIP hard cap");
  String geometry=display.substring(0,RendererTrace.MAX_BYTES);
  file=archive.export(new String(new char[64_000]).replace('\0','m'),display,surface,geometry);
  check(file.length()<=15_000_000,"renderer geometry plus maximum existing diagnostics fit the hard ZIP cap");
  try(ZipFile zip=new ZipFile(file)){
   check(Arrays.equals(zip.getInputStream(zip.getEntry("renderer-geometry.txt")).readAllBytes(),geometry.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"renderer evidence is exported independently of app.log");
  }
  file=archive.export("window evidence",display,surface,geometry,"first connected window\nlatest inset state\n");
  check(file.length()<=15_000_000,"retained immersive-window evidence fits the hard ZIP cap");
  try(ZipFile zip=new ZipFile(file)){
   String windows=new String(zip.getInputStream(zip.getEntry("immersive-windows.txt")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
   check(windows.contains("first connected window")&&windows.contains("latest inset state"),"connected-window state is exported independently of logcat");
  }
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
                  ('DebugArchive.java', 'StartupTrace.java', 'DisplayDiagnostics.java', 'NetworkDiagnostics.java', 'ControlTrace.java', 'RendererTrace.java')], str(test)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp,
                'dev.beampro.settings.DebugArchiveChecks', str(work / 'data')], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS debug archive checks', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
