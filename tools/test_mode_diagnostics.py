"""Exercise the native mode observers and prove the shipped SDK hook's exact scope."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from build_embedded_adb import BASE
from mode_diagnostics import patch_mode_diagnostics
from test_display_startup import ROOT, JAVA


class ModeDiagnosticsTests(unittest.TestCase):
    def test_native_results_order_errors_and_opt_in_readbacks(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            debug = work / 'DebugData.java'
            debug.write_text('''package dev.beampro.settings;
              public class DebugData {
                public static boolean enabled,broken;public static java.util.List<String> events=new java.util.ArrayList<>();
                public static boolean modeDiagnosticsEnabled(){return enabled;}
                public static void modeEvent(String kind,String text){if(broken)throw new IllegalStateException();events.add(kind+" "+text);}
              }''')
            check = work / 'ModeChecks.java'
            check.write_text('''import dev.beampro.port.GlassesModeDiagnostics;import dev.beampro.settings.DebugData;
              public class ModeChecks {
                static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
                public static class Control {
                  int edid=9,input=0,reads;boolean inputResult=false,brokenRead,brokenWrite;
                  final RuntimeException nativeFailure=new IllegalStateException("native write failed");
                  java.util.List<String> calls=new java.util.ArrayList<>();
                  public int nativeGetDpCurrentEdid(){reads++;if(brokenRead)throw new IllegalStateException();return edid;}
                  public int nativeGetDpInputMode(){reads++;return input;}
                  public int nativeGet2D3DMode(){reads++;return 1;}
                  public boolean nativeSetDpCurrentEdid(int mode){calls.add("edid:"+mode);if(brokenWrite)throw nativeFailure;edid=mode;return true;}
                  public boolean nativeSetDpInputModeEx(int mode,int flags){calls.add("input:"+mode+":"+flags);return inputResult;}
                  public boolean nativeSet2D3DMode(int mode){calls.add("legacy:"+mode);return false;}
                }
                public static void main(String[] args)throws Throwable {
                  Control c=new Control();
                  check(GlassesModeDiagnostics.setEdid(c,5)&&c.reads==0&&DebugData.events.isEmpty(),"Collection off performs no diagnostic getters");
                  DebugData.enabled=true;c.edid=9;c.calls.clear();
                  check(GlassesModeDiagnostics.setEdid(c,5),"Keep native EDID success");
                  check(!GlassesModeDiagnostics.setInputMode(c,1,0),"Preserve the separate input-mode failure even when EDID succeeded");
                  check(c.calls.equals(java.util.Arrays.asList("edid:5","input:1:0")),"Preserve native command order and arguments");
                  check(c.reads==4,"Read state only before the original native setters");
                  check(DebugData.events.get(1).contains("before edid=9")&&DebugData.events.get(4).contains("before edid=5"),"Retain original EDID and firmware readback before input switch");
                  check(DebugData.events.get(5).contains("returned=false")&&DebugData.events.get(5).contains("durationMs="),"Retain the input-mode result and elapsed time independently");
                  check(!GlassesModeDiagnostics.setLegacy(c,3)&&c.calls.get(2).equals("legacy:3"),"Keep legacy mode semantics");
                  c.brokenRead=true;check(GlassesModeDiagnostics.setEdid(c,5),"A rejected diagnostic getter cannot suppress a mode write");
                  DebugData.broken=true;check(GlassesModeDiagnostics.setEdid(c,5),"Failed event storage cannot suppress a mode write");
                  c.brokenWrite=true;try{GlassesModeDiagnostics.setEdid(c,5);throw new AssertionError("lost native exception");}
                  catch(RuntimeException failure){check(failure==c.nativeFailure,"Propagate the original native exception without reflection wrapping");}
                  System.out.println("PASS mode observer assertions: "+checks);
                }
              }''')
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp, str(debug), str(check),
                            str(ROOT / 'port-client/src/dev/beampro/port/GlassesModeDiagnostics.java')], check=True)
            result = subprocess.check_output([str(JAVA / 'bin/java'), '-cp', tmp, 'ModeChecks'], text=True)
            self.assertIn('PASS mode observer assertions:', result)
            print(result.strip())

    def test_pinned_sdk_mode_hook_restores_exact_original_and_rejects_double_patch(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes2.dex')
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            patched = patch_mode_diagnostics(original, work / 'patch', JAVA)
            self.assertNotEqual(patched, original)
            self.assertEqual(patch_mode_diagnostics(patched, work / 'restore', JAVA,
                                                  restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_mode_diagnostics(patched, work / 'double', JAVA, baseline=original)


if __name__ == '__main__':
    unittest.main()
