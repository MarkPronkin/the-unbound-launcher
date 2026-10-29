"""R1 identity boundaries and a full audit of the six bundled SDK edits."""
import hashlib
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib

from build_embedded_adb import BASE
from rog_r1 import IDENTITY_SOURCE, patch_rog_r1
from test_display_startup import JAVA, ROOT
from test_usb_permission import STUBS


class RogR1Tests(unittest.TestCase):
    def test_exact_usb_and_edid_identities_keep_foreign_devices_and_existing_models(self):
        stubs = {name: code for name, code in STUBS.items()
                 if name in ('android/hardware/usb/UsbDevice.java', 'android/hardware/usb/UsbManager.java')}
        stubs.update({
            'android/hardware/usb/UsbManager.java': '''package android.hardware.usb;public class UsbManager {
                public java.util.Map<String,UsbDevice> devices=new java.util.HashMap<>();
                public java.util.Map<String,UsbDevice> getDeviceList(){return devices;}}''',
            'android/content/res/AssetManager.java': '''package android.content.res;public class AssetManager {
                public java.io.InputStream open(String n)throws java.io.IOException{return null;}}''',
            'android/graphics/drawable/Drawable.java': '''package android.graphics.drawable;public class Drawable {
                public static Drawable createFromStream(java.io.InputStream i,String n){return null;}}''',
            'android/view/InputDevice.java': '''package android.view;public class InputDevice {
                int v,p;public InputDevice(int v,int p){this.v=v;this.p=p;}
                public int getVendorId(){return v;}public int getProductId(){return p;}}''',
            'android/content/Context.java': '''package android.content;public class Context {
                public static android.hardware.usb.UsbManager usb=new android.hardware.usb.UsbManager();
                public <T>T getSystemService(Class<T> c){return c.cast(usb);}
                public android.content.res.AssetManager getAssets(){return null;}}''',
            'com/xreal/glassesdisplayplugevent/GlassesInitSetting.java': '''package com.xreal.glassesdisplayplugevent;
                public class GlassesInitSetting {public static GlassesInitSetting getInstance(){return new GlassesInitSetting();}
                public android.content.Context getContext(){return new android.content.Context();}}''',
            'RogR1Checks.java': '''import dev.beampro.compat.GlassesIdentity;
                import dev.beampro.port.RogR1Compat;import android.hardware.usb.UsbDevice;
                public class RogR1Checks {
                    static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
                    public static void main(String[] args)throws Exception{
                        check(GlassesIdentity.rogR1Display("43593","AUS"));
                        check(!GlassesIdentity.rogR1Display("43594","AUS"));
                        check(!GlassesIdentity.rogR1Display("43593","MRG"));
                        check(!GlassesIdentity.rogR1Display(null,"AUS"));
                        check(!GlassesIdentity.rogR1Display("43593",null));
                        check(GlassesIdentity.sdkDisplayType(-1,"43593","AUS")==6);
                        for(int original:new int[]{-1,0,1,2,3,4,5,6,7,8,9,10,11,20}){
                            check(GlassesIdentity.sdkDisplayType(original,"16640","MRG")==original);
                            check(RogR1Compat.sdkUsbType(original,new UsbDevice("onepro",13080,1078,3))==original);
                        }
                        check(RogR1Compat.uiType(6)==6);
                        for(int product:new int[]{0x1d9c,0x1d9d}) {
                            UsbDevice device=new UsbDevice("r1",0x0b05,product,4);
                            check(GlassesIdentity.usb(0x0b05,product));
                            check(RogR1Compat.sdkUsbType(-1,device)==6);
                            check(RogR1Compat.sdkUsbVendor(device)==13080);
                            check(device.getVendorId()==0x0b05&&device.getProductId()==product);
                            check(RogR1Compat.sdkInputVendor(new android.view.InputDevice(0x0b05,product))==13080);
                            android.content.Context.usb.devices.put("r1",device);
                            check(RogR1Compat.uiType(6)==1003&&RogR1Compat.uiType(7)==7);
                            Class<?> art=Class.forName("dev.beampro.settings.GlassesArtwork");
                            java.lang.reflect.Method forType=art.getDeclaredMethod("forType",int.class);forType.setAccessible(true);
                            Object artwork=forType.invoke(null,6);
                            java.lang.reflect.Field name=art.getDeclaredField("name");name.setAccessible(true);
                            check(name.get(artwork).equals("XREAL Rog R1"));
                            android.content.Context.usb.devices.clear();
                        }
                        for(int[] id:new int[][]{{0x0b05,0x1d9e},{1,0x1d9c},{13080,0x1d9c},{0x0b05,1078}}){
                            UsbDevice other=new UsbDevice("other",id[0],id[1],5);
                            check(RogR1Compat.sdkUsbType(-1,other)==-1);
                            check(RogR1Compat.sdkUsbVendor(other)==id[0]);
                            if(id[0]!=13080)check(!GlassesIdentity.usb(id[0],id[1]));
                        }
                        check(RogR1Compat.sdkUsbType(-1,null)==-1);
                        System.out.println("PASS R1 identities: "+checks);
                    }
                }''',
        })
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            files = []
            for name, code in stubs.items():
                file = work / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(code)
                files.append(file)
            files += [IDENTITY_SOURCE, ROOT / 'port-client/src/dev/beampro/port/RogR1Compat.java',
                      ROOT / 'settings-ui/src/dev/beampro/settings/GlassesArtwork.java']
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp, *map(str, files)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp, 'RogR1Checks'],
                                    capture_output=True, text=True, check=True)
            print(result.stdout.strip())

    def test_six_sdk_hooks_restore_exactly_and_reject_double_patch_and_unrelated_edits(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes2.dex')
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            patched = patch_rog_r1(original, work / 'patch', JAVA)
            self.assertNotEqual(patched, original)
            self.assertEqual(patch_rog_r1(patched, work / 'restore', JAVA, restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_rog_r1(patched, work / 'double', JAVA, baseline=original)
            damaged = bytearray(patched.replace(b'DisplayModel.java', b'DisplayModef.java', 1))
            self.assertNotEqual(damaged, patched)
            damaged[12:32] = hashlib.sha1(damaged[32:]).digest()
            struct.pack_into('<I', damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_rog_r1(damaged, work / 'unrelated', JAVA, restore=True, baseline=original)


if __name__ == '__main__':
    unittest.main()
