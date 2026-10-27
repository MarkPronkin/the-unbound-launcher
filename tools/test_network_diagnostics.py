"""Exercise bounded network probes and the real SDK cancellation patch."""
import hashlib
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path

from test_display_startup import ROOT, JAVA
from build_embedded_adb import BASE
from ping_cancellation import patch_ping_cancellation


class NetworkDiagnosticsTests(unittest.TestCase):
    def test_optional_helper_failures_do_not_prevent_export_and_timeout_has_no_request_backlog(self):
        stubs = {
            'android/net/Uri.java': 'package android.net; public class Uri {public String value;public static Uri parse(String s){Uri u=new Uri();u.value=s;return u;}}',
            'android/os/IBinder.java': 'package android.os;public interface IBinder {boolean isBinderAlive();boolean transact(int code,Parcel data,Parcel reply,int flags);}',
            'android/os/Bundle.java': 'package android.os;public class Bundle {public IBinder binder;public IBinder getBinder(String s){return binder;}}',
            'android/os/Parcel.java': '''package android.os;public class Parcel {
                public String text;public static Parcel obtain(){return new Parcel();}public void writeInterfaceToken(String s){text=s;}
                public void readException(){}public String readString(){return text;}public void recycle(){}}''',
            'android/content/Context.java': '''package android.content;public class Context {
                public android.os.IBinder binder;public String authority;
                public String getPackageName(){return "dev.markpronkin.unbound";}
                public Context getContentResolver(){return this;}public android.os.Bundle call(android.net.Uri u,String m,String a,android.os.Bundle b){
                 authority=u.value;android.os.Bundle result=new android.os.Bundle();result.binder=binder;return result;}}''',
        }
        test = '''package dev.beampro.settings;
        public class DebugNetworkChecks {
          static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
          public static void main(String[] args)throws Exception {
            android.content.Context context=new android.content.Context();
            String report=DebugNetwork.capture(context,1000);
            check(report.contains("app UID")&&report.contains("not running"),"Missing helper preserves app diagnostics");
            context.binder=new android.os.IBinder(){public boolean isBinderAlive(){return true;}
              public boolean transact(int c,android.os.Parcel d,android.os.Parcel r,int f){return false;}};
            report=DebugNetwork.capture(context,1000);
            check(report.contains("does not support")&&report.contains("restart"),"Older helper yields a useful export");
            context.binder=new android.os.IBinder(){public boolean isBinderAlive(){return true;}
              public boolean transact(int c,android.os.Parcel d,android.os.Parcel r,int f){
                check(c==NetworkDiagnostics.TRANSACTION&&NetworkDiagnostics.TOKEN.equals(d.text),"Fixed diagnostic transaction");
                r.text="Identity: ADB shell helper\\neth0 169.254.2.10/24";return true;}};
            report=DebugNetwork.capture(context,1000);
            check(report.contains("app UID")&&report.contains("169.254.2.10"),"Retain both identities");
            check(context.authority.equals("content://dev.markpronkin.unbound.unbound.bridge"),"Resolve the installed package provider");
            java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
            context.binder=new android.os.IBinder(){public boolean isBinderAlive(){return true;}
              public boolean transact(int c,android.os.Parcel d,android.os.Parcel r,int f){
                while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}r.text="late";return true;}};
            long start=System.nanoTime();report=DebugNetwork.capture(context,100);
            check(report.contains("TimeoutException")&&(System.nanoTime()-start)/1000000<2000,"Hung Binder cannot hold export indefinitely");
            report=DebugNetwork.capture(context,100);
            check(report.contains("RejectedExecutionException")&&report.contains("app UID"),"Never queue more blocked helper requests");
            release.countDown();
            System.out.println("PASS optional network helper checks");
          }
        }'''
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, code in {**stubs, 'DebugNetworkChecks.java': test}.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(code)
                sources.append(path)
            sources += [ROOT / 'settings-ui/src/dev/beampro/settings' / name for name in ('NetworkDiagnostics.java', 'DebugNetwork.java')]
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp, *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp, 'dev.beampro.settings.DebugNetworkChecks'],
                                    capture_output=True, text=True, check=True, timeout=10)
            self.assertIn('PASS optional network helper checks', result.stdout)

    def test_fixed_probes_report_errors_and_bound_time_and_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            sources = [ROOT / 'settings-ui/src/dev/beampro/settings/NetworkDiagnostics.java', ROOT / 'tools/NetworkDiagnosticsTest.java']
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp, *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp, 'dev.beampro.settings.NetworkDiagnosticsTest'],
                                    capture_output=True, text=True, check=True, timeout=20)
            self.assertIn('PASS network diagnostics assertions', result.stdout)
            print(result.stdout.strip())

    def test_sdk_patch_restores_exactly_and_rejects_unrelated_changes(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes2.dex')
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            patched = patch_ping_cancellation(original, work / 'patch', JAVA)
            self.assertEqual(patch_ping_cancellation(patched, work / 'restore', JAVA, restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_ping_cancellation(patched, work / 'double', JAVA, baseline=original)
            damaged = bytearray(patched.replace(b'PingGSeriesHelper.java', b'PingGSeriesHelpef.java', 1))
            self.assertNotEqual(damaged, patched)
            damaged[12:32] = hashlib.sha1(damaged[32:]).digest()
            struct.pack_into('<I', damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_ping_cancellation(damaged, work / 'damaged', JAVA, restore=True, baseline=original)


if __name__ == '__main__':
    unittest.main()
