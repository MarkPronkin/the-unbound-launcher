"""Verify automatic entry's caller, hardware, lock, cancellation and deduplication boundaries."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_display_startup import ROOT, JAVA
from test_usb_permission import STUBS

RECEIVER_STUBS = dict(STUBS)
RECEIVER_STUBS.update({
    'android/content/SharedPreferences.java': '''package android.content;
      public class SharedPreferences extends java.util.HashMap<String,Object> {
        public boolean getBoolean(String k,boolean fallback){return containsKey(k)?(Boolean)get(k):fallback;}
        public String getString(String k,String fallback){return containsKey(k)?(String)get(k):fallback;}
        public Editor edit(){return new Editor();}public class Editor {
          public Editor putString(String k,String v){put(k,v);return this;}
          public Editor putInt(String k,int v){put(k,v);return this;}
          public Editor clear(){SharedPreferences.this.clear();return this;}public void apply(){}}}''',
    'android/content/Context.java': '''package android.content;public class Context {
      public String name="dev.markpronkin.unbound";public ContentResolver resolver=new ContentResolver();
      public android.hardware.usb.UsbManager usb=new android.hardware.usb.UsbManager();
      java.util.Map<String,SharedPreferences> state=new java.util.HashMap<>();
      public Context getApplicationContext(){return this;}public String getPackageName(){return name;}
      public ContentResolver getContentResolver(){return resolver;}
      public SharedPreferences getSharedPreferences(String n,int mode){return state.computeIfAbsent(n,k->new SharedPreferences());}
      public <T>T getSystemService(Class<T> c){return c.cast(usb);}}''',
    'android/content/Intent.java': '''package android.content;public class Intent {
      String action;public java.util.Map<String,Object> extras=new java.util.HashMap<>();
      public Intent(String a){action=a;}public Intent putExtra(String k,Object v){extras.put(k,v);return this;}
      public Intent putExtra(String k,boolean v){extras.put(k,v);return this;}
      public String getAction(){return action;}public <T>T getParcelableExtra(String k){return (T)extras.get(k);}}''',
    'android/content/BroadcastReceiver.java': '''package android.content;public abstract class BroadcastReceiver {
      public abstract void onReceive(Context c,Intent i);public PendingResult goAsync(){return new PendingResult();}
      public static class PendingResult {public void finish(){}}}''',
    'android/hardware/usb/UsbManager.java': '''package android.hardware.usb;public class UsbManager {
      public static final String ACTION_USB_DEVICE_ATTACHED="attached",ACTION_USB_DEVICE_DETACHED="detached",EXTRA_DEVICE="device";
      public java.util.HashMap<String,UsbDevice> devices=new java.util.HashMap<>();
      public java.util.HashMap<String,UsbDevice> getDeviceList(){return devices;}}''',
    'android/provider/Settings.java': '''package android.provider;public class Settings {
      public static boolean overlay=true;public static boolean canDrawOverlays(android.content.Context c){return overlay;}}''',
    'android/util/Log.java': '''package android.util;public class Log {
      public static int i(String t,String s){return 0;}public static int w(String t,String s,Throwable e){return 0;}
      public static int e(String t,String s,Throwable e){return 0;}}''',
    'dev/beampro/settings/TermsConsent.java': '''package dev.beampro.settings;public class TermsConsent {
      public static boolean accepted=true;public static boolean hasAccepted(android.content.Context c){return accepted;}}''',
    'dev/beampro/port/GlassesConnectionCompat.java': '''package dev.beampro.port;public class GlassesConnectionCompat {
      public static boolean active,mirroring,failRead;public static boolean sessionActive(){return active;}
      public static boolean startInScreenMirroring(android.content.Context c){
        if(failRead)throw new IllegalStateException("Preference unavailable");return mirroring;}}''',
    'com/xreal/glassesdisplayplugevent/helper/GlassesTypeUtil.java': '''package com.xreal.glassesdisplayplugevent.helper;
      public class GlassesTypeUtil {public static int checkGlassesType(android.hardware.usb.UsbDevice d){return 7;}}''',
    'dev/beampro/adb/AdbBootstrap.java': '''package dev.beampro.adb;public class AdbBootstrap {
      public static final String AUTHORITY="dev.beampro.portprobe.bridge";public static boolean ready=true;public static int connects;
      public static boolean ready(android.content.Context c){return ready;}public static boolean paired(android.content.Context c){return true;}
      public static void connect(android.content.Context c,int port,Object callback){connects++;}}''',
})


class AutoArEntryTests(unittest.TestCase):
    def test_receiver_respects_mirroring_on_attach_resume_and_queued_dispatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, code in RECEIVER_STUBS.items():
                p = work / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(code)
                sources.append(p)
            sources += [ROOT / 'embedded-adb/src/dev/beampro/adb/AutoGlassesReceiver.java',
                        ROOT / 'compat/src/dev/beampro/compat/GlassesIdentity.java',
                        ROOT / 'tools/AutoGlassesReceiverTest.java']
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', str(work), *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', str(work), 'dev.beampro.adb.AutoGlassesReceiverTest'],
                                    text=True, capture_output=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())

    def test_production_policy(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, code in STUBS.items():
                p = work / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(code)
                sources.append(p)
            sources += [ROOT / 'bridge/src/dev/beampro/bridge' / name for name in
                        ('AutoArEntry.java', 'XrealUsbPermissions.java', 'ShellCommand.java')]
            sources.append(ROOT / 'tools/AutoArEntryTest.java')
            sources.append(ROOT/"compat/src/dev/beampro/compat/GlassesIdentity.java")
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', str(work), *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', str(work), 'dev.beampro.bridge.AutoArEntryTest'],
                                    text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS automatic AR entry assertions:', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
