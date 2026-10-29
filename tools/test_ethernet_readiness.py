"""Reproduce app-side eth0 denial with a working helper, retaining real failure and cancellation."""
import hashlib
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib

from test_display_startup import ROOT, JAVA
from test_usb_permission import STUBS as USB_STUBS
from build_embedded_adb import BASE
from ethernet_ping import patch_ethernet_ping


class EthernetReadinessTests(unittest.TestCase):
    def test_client_helper_identity_usb_revalidation_and_cancellation(self):
        stubs = dict(USB_STUBS)
        stubs.update({
            # Network selection itself is exercised with the real ping guard in test_glasses_network.
            'dev/beampro/port/GlassesNetworkCompat.java': '''package dev.beampro.port;public class GlassesNetworkCompat {
                static void deliver(java.lang.reflect.Method result,Object callback,boolean success)throws Exception {result.invoke(callback,success);}}''',
            'android/os/Handler.java': '''package android.os;public class Handler {
                final java.util.Queue<Runnable> jobs=new java.util.concurrent.ConcurrentLinkedQueue<>();
                public boolean post(Runnable r){jobs.add(r);return true;}public void removeCallbacks(Runnable r){jobs.removeIf(j->j==r);}
                public void drain(){Runnable r;while((r=jobs.poll())!=null)r.run();}}''',
            'android/os/SystemClock.java': 'package android.os;public class SystemClock {public static long uptimeMillis(){return System.nanoTime()/1000000;}}',
            'android/content/Context.java': '''package android.content;public class Context {
                public android.hardware.usb.UsbManager usb=new android.hardware.usb.UsbManager();
                public ContentResolver resolver=new ContentResolver();public ContentResolver getContentResolver(){return resolver;}
                public <T>T getSystemService(Class<T> c){return c.cast(usb);}}''',
            'android/util/Log.java': '''package android.util;public class Log {
                public static int i(String t,String m){return 0;}public static int w(String t,String m,Throwable e){return 0;}
                public static int e(String t,String m,Throwable e){e.printStackTrace();return 0;}}''',
            'dev/beampro/port/GlassesConnectionCompat.java': '''package dev.beampro.port;public class GlassesConnectionCompat {
                static Object get(Object o,String name)throws Exception {java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}}''',
            'com/xreal/glassesdisplayplugevent/GlassesInitSetting.java': '''package com.xreal.glassesdisplayplugevent;
                public class GlassesInitSetting {public static android.content.Context context;
                 public static GlassesInitSetting getInstance(){return new GlassesInitSetting();}public android.content.Context getContext(){return context;}}''',
            'com/xreal/glasses/servicesdk/helper/PingGSeriesHelper.java': '''package com.xreal.glasses.servicesdk.helper;
                public class PingGSeriesHelper {public interface PingCallback {void onPingResult(boolean ok);}}''',
        })
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            files = []
            for name, source in stubs.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                files.append(path)
            files += [ROOT / 'port-client/src/dev/beampro/port' / n for n in ('PingCallbackCompat.java', 'EthernetReadinessCompat.java')]
            files.append(ROOT / 'tools/EthernetReadinessTest.java')
            files.append(ROOT/"compat/src/dev/beampro/compat/GlassesIdentity.java")
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp, *map(str, files)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp, 'dev.beampro.port.EthernetReadinessTest'],
                                    capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS Ethernet readiness assertions:', result.stdout)
            print(result.stdout.strip())

    def test_helper_allows_only_launcher_fixed_command_and_has_no_backlog(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            files = []
            for name, source in USB_STUBS.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                files.append(path)
            files += [ROOT / 'bridge/src/dev/beampro/bridge' / n for n in
                      ('ShellCommand.java', 'GlassesNetworkProbe.java', 'GlassesEthernet.java', 'XrealUsbPermissions.java')]
            files += [ROOT / 'tools/GlassesNetworkProbeTest.java', ROOT / 'tools/GlassesEthernetTest.java',
                      ROOT / 'compat/src/dev/beampro/compat/GlassesIdentity.java']
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp, *map(str, files)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp, 'dev.beampro.bridge.GlassesNetworkProbeTest'],
                                    capture_output=True, text=True, check=True, timeout=10)
            print(result.stdout.strip())
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp, 'dev.beampro.bridge.GlassesEthernetTest'],
                                    capture_output=True, text=True, check=True, timeout=10)
            print(result.stdout.strip())

    def test_sdk_ping_block_exact_scope_and_restoration(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes2.dex')
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            patched = patch_ethernet_ping(original, work / 'patch', JAVA)
            self.assertEqual(patch_ethernet_ping(patched, work / 'restore', JAVA, restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_ethernet_ping(patched, work / 'double', JAVA, baseline=original)
            damaged = bytearray(patched.replace(b'PingGSeriesHelper.java', b'PingGSeriesHelpef.java', 1))
            self.assertNotEqual(damaged, patched)
            damaged[12:32] = hashlib.sha1(damaged[32:]).digest()
            struct.pack_into('<I', damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_ethernet_ping(damaged, work / 'damaged', JAVA, restore=True, baseline=original)


if __name__ == '__main__':
    unittest.main()
