"""Run the actual USB adapters through fresh-install, reconnect and Android API boundaries."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from test_display_startup import ROOT, JAVA
from build_embedded_adb import BASE
from usb_permission import patch_usb_permission, patch_controller_usb_permission

STUBS = {
    'android/os/Process.java': 'package android.os;public class Process {public static int myUid(){return 2000;}}',
    'android/hardware/usb/UsbDevice.java': '''package android.hardware.usb; public class UsbDevice {
      String name;int vendor,product,id;public UsbDevice(String n,int v,int p,int i){name=n;vendor=v;product=p;id=i;}
      public String getDeviceName(){return name;}public int getVendorId(){return vendor;}
      public int getProductId(){return product;}public int getDeviceId(){return id;}}''',
    'android/os/Bundle.java': '''package android.os; public class Bundle extends java.util.HashMap<String,Object> {
      public IBinder getBinder(String key){return (IBinder)get(key);}public void putBinder(String k,IBinder v){put(k,v);}}''',
    'android/os/IBinder.java': '''package android.os; public interface IBinder {
      boolean isBinderAlive();boolean transact(int code,Parcel data,Parcel reply,int flags)throws RemoteException;}''',
    'android/os/RemoteException.java': 'package android.os;public class RemoteException extends Exception {}',
    'android/os/Parcel.java': '''package android.os; public class Parcel {
      java.util.List<Object> values=new java.util.ArrayList<>();int at;
      public static Parcel obtain(){return new Parcel();}public void recycle(){}
      public void writeInterfaceToken(String s){writeString(s);}public void writeString(String s){values.add(s);}
      public void writeInt(int i){values.add(i);}public String readString(){return (String)values.get(at++);}
      public int readInt(){return (Integer)values.get(at++);}public void readException(){}}''',
    'android/net/Uri.java': 'package android.net;public class Uri {public String text;public static Uri parse(String s){Uri u=new Uri();u.text=s;return u;}}',
    'android/content/ContentResolver.java': '''package android.content;public class ContentResolver {
      public android.os.IBinder remote;public int calls;
      public android.os.Bundle call(android.net.Uri uri,String method,String arg,android.os.Bundle extras){
        if(!uri.text.equals("content://dev.beampro.portprobe.bridge")||!method.equals("get"))throw new AssertionError("provider");
        calls++;android.os.Bundle b=new android.os.Bundle();b.putBinder("binder",remote);return b;}}''',
    'android/content/Context.java': '''package android.content;public class Context {
      public String name="com.xreal.evapro.nebula";public ContentResolver resolver=new ContentResolver();
      public Context getApplicationContext(){return this;}public String getPackageName(){return name;}
      public ContentResolver getContentResolver(){return resolver;}}''',
    'android/content/Intent.java': '''package android.content;public class Intent {
      public java.util.Map<String,Object> extras=new java.util.HashMap<>();
      public Intent putExtra(String k,Object v){extras.put(k,v);return this;}
      public Intent putExtra(String k,boolean v){extras.put(k,v);return this;}}''',
    'android/app/PendingIntent.java': '''package android.app;public class PendingIntent {
      public String creator="com.xreal.evapro.nebula";public int responses;public boolean granted,canceled;
      public String getCreatorPackage(){return creator;}
      public void send(android.content.Context c,int code,android.content.Intent i)throws CanceledException {
        if(canceled)throw new CanceledException();responses++;granted=(Boolean)i.extras.get("permission");}
      public static class CanceledException extends Exception {}}''',
    'android/hardware/usb/UsbManager.java': '''package android.hardware.usb;public class UsbManager {
      public static final String EXTRA_DEVICE="device",EXTRA_PERMISSION_GRANTED="permission";
      public java.util.HashMap<String,UsbDevice> devices=new java.util.HashMap<>();
      public boolean granted,privacyBlocked;public int requests,dialogs;
      public java.util.HashMap<String,UsbDevice> getDeviceList(){return devices;}
      public boolean hasPermission(UsbDevice device){return granted&&!privacyBlocked;}
      public void requestPermission(UsbDevice d,android.app.PendingIntent p){requests++;if(!hasPermission(d)){dialogs++;return;}
        try{p.send(null,0,new android.content.Intent().putExtra(EXTRA_DEVICE,d).putExtra(EXTRA_PERMISSION_GRANTED,true));}
        catch(android.app.PendingIntent.CanceledException ignored){}}}''',
    'android/content/pm/ApplicationInfo.java': 'package android.content.pm;public class ApplicationInfo {public int uid;}',
    'android/content/pm/PackageManager.java': '''package android.content.pm;public class PackageManager {
      public int uid=10123;public ApplicationInfo getApplicationInfo(String name,int flags){
      if(!name.equals("com.xreal.evapro.nebula"))throw new AssertionError("target package");ApplicationInfo a=new ApplicationInfo();a.uid=uid;return a;}}''',
    'android/util/Log.java': '''package android.util;public class Log {
      public static int i(String t,String m){return 0;}public static int e(String t,String m,Throwable e){return 0;}}''',
}

SERVER_TEST = '''package dev.beampro.bridge;
import android.hardware.usb.UsbDevice;import android.os.Bundle;import android.content.pm.PackageManager;
public class XrealUsbPermissionsTest {
  static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
  public static class Legacy {Bundle devices=new Bundle();int calls,uid;
    public void getDeviceList(Bundle out){out.putAll(devices);}
    public void grantDevicePermission(UsbDevice d,int u){calls++;uid=u;}}
  public static class Modern extends Legacy {String name;boolean persistent=true,reject;
    public void grantDevicePermission(UsbDevice d,String p,int u,boolean store){
      if(reject)throw new SecurityException("denied");calls++;name=p;uid=u;persistent=store;}}
  interface Action{void run()throws Exception;}
  static void denied(Action r)throws Exception {try{r.run();throw new AssertionError("accepted");}catch(SecurityException expected){checks++;}}
  public static void main(String[] args)throws Exception {
    PackageManager pm=new PackageManager();UsbDevice one=new UsbDevice("one",13080,1080,12);
    Legacy legacy=new Legacy();legacy.devices.put("one",one);
    XrealUsbPermissions old=new XrealUsbPermissions(pm,10123,legacy,Legacy.class);
    check(old.grant(10123,"one",13080,1080,12));check(legacy.calls==1&&legacy.uid==10123);
    denied(()->old.grant(10124,"one",13080,1080,12));
    denied(()->old.grant(2000,"one",13080,1080,12));
    denied(()->old.grant(10123,"one",1234,1080,12));check(legacy.calls==1);
    pm.uid=10456;denied(()->old.grant(10123,"one",13080,1080,12));pm.uid=10123;
    check(!old.grant(10123,"missing",13080,1080,12));
    check(!old.grant(10123,"one",13080,1081,12));check(!old.grant(10123,"one",13080,1080,13));
    legacy.devices.put("one",new UsbDevice("one",2222,1080,12));check(!old.grant(10123,"one",13080,1080,12));check(legacy.calls==1);
    Modern modern=new Modern();modern.devices.put("one",one);
    XrealUsbPermissions pixel=new XrealUsbPermissions(pm,10123,modern,Modern.class);
    check(pixel.grant(10123,"one",13080,1080,12));check(modern.calls==1&&!modern.persistent);
    check(modern.name.equals("com.xreal.evapro.nebula")&&modern.uid==10123);
    modern.reject=true;try{pixel.grant(10123,"one",13080,1080,12);throw new AssertionError("accepted rejection");}
    catch(java.lang.reflect.InvocationTargetException expected){check(expected.getCause() instanceof SecurityException);}
    check(modern.calls==1); // A modern failure must never fall back to the legacy overload.
    System.out.println("PASS XREAL USB server assertions: "+checks);
  }
}'''

CLIENT_TEST = '''package dev.beampro.port;
import android.hardware.usb.*;import android.content.*;import android.app.PendingIntent;import android.os.*;
import java.util.concurrent.*;import java.util.*;
public class UsbPermissionCompatTest {
  static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
  static class Manual extends ScheduledThreadPoolExecutor {
    ArrayDeque<Runnable> jobs=new ArrayDeque<>();Manual(){super(1);}
    public void execute(Runnable r){jobs.add(r);}public ScheduledFuture<?> schedule(Runnable r,long n,TimeUnit unit){jobs.add(r);return null;}
    void step(){jobs.remove().run();}}
  static class Remote implements IBinder {
    UsbManager usb;int calls;boolean supported=true,failOnce,grant=true;String error;
    Remote(UsbManager u){usb=u;}public boolean isBinderAlive(){return true;}
    public boolean transact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
      calls++;if(failOnce){failOnce=false;throw new RemoteException();}if(!supported)return false;
      check(code==0x00FFFFF8&&data.readString().equals("dev.beampro.bridge.XREAL_USB"));
      String name=data.readString();UsbDevice live=usb.devices.get(name);
      check(live!=null&&data.readInt()==13080&&data.readInt()==live.getProductId()&&data.readInt()==live.getDeviceId());
      reply.writeString(error);reply.writeInt(grant?1:0);if(grant&&error==null)usb.granted=true;return true;}}
  static int sequence;static class Setup {
    Context context=new Context();UsbManager usb=new UsbManager();PendingIntent callback=new PendingIntent();
    UsbDevice device=new UsbDevice("usb"+(sequence++),13080,1080,12);Remote remote=new Remote(usb);
    Setup(boolean ready){usb.devices.put(device.getDeviceName(),device);if(ready)context.resolver.remote=remote;}
    void request(){UsbPermissionCompat.requestPermission(context,usb,device,callback);}
    void success(){check(callback.responses==1&&callback.granted&&usb.requests==0&&usb.dialogs==0);}}
  public static void main(String[] args)throws Exception {
    Manual manual=new Manual();UsbPermissionCompat.worker.shutdownNow();UsbPermissionCompat.worker=manual;
    Setup fresh=new Setup(false);fresh.request();manual.step();
    check(fresh.usb.requests==0&&fresh.callback.responses==0&&manual.jobs.size()==1);
    fresh.context.resolver.remote=fresh.remote;manual.step();fresh.success();check(fresh.remote.calls==1);
    Setup authorized=new Setup(false);authorized.usb.granted=true;authorized.request();manual.step();authorized.success();check(authorized.context.resolver.calls==0);
    Setup detached=new Setup(false);detached.request();manual.step();detached.usb.devices.clear();manual.step();
    check(detached.callback.responses==1&&!detached.callback.granted&&detached.usb.requests==0);
    Setup duplicate=new Setup(true);duplicate.request();duplicate.request();manual.step();manual.step();duplicate.success();check(duplicate.remote.calls==1);
    Setup stale=new Setup(true);stale.request();stale.usb.devices.put(stale.device.getDeviceName(),new UsbDevice(stale.device.getDeviceName(),13080,999,12));manual.step();
    check(stale.remote.calls==0&&stale.callback.responses==1&&!stale.callback.granted);
    Setup old=new Setup(true);old.remote.supported=false;old.request();manual.step();check(old.usb.dialogs==0&&old.callback.responses==1&&!old.callback.granted);
    Setup rejected=new Setup(true);rejected.remote.error="permission denied";rejected.request();manual.step();check(rejected.usb.requests==0&&!rejected.callback.granted);
    Setup restarting=new Setup(true);restarting.remote.failOnce=true;restarting.request();manual.step();check(restarting.callback.responses==0);manual.step();restarting.success();
    Setup privacy=new Setup(true);privacy.usb.privacyBlocked=true;privacy.request();manual.step();check(privacy.usb.requests==0&&privacy.callback.responses==1&&!privacy.callback.granted);
    Setup unrelated=new Setup(true);unrelated.device=new UsbDevice("other",1234,1080,12);unrelated.request();check(unrelated.remote.calls==0&&unrelated.usb.dialogs==1);
    Setup otherCreator=new Setup(true);otherCreator.callback.creator="another.app";otherCreator.request();check(otherCreator.remote.calls==0&&otherCreator.usb.dialogs==1);
    Setup separate=new Setup(true);PendingIntent second=new PendingIntent();separate.request();
    UsbPermissionCompat.requestPermission(separate.context,separate.usb,separate.device,second);manual.step();manual.step();
    separate.success();check(second.responses==1&&second.granted&&separate.remote.calls==1);
    Setup early=new Setup(true);UsbPermissionCompat.requestPermission(early.usb,early.device,early.callback);manual.step();
    check(early.usb.requests==0&&early.callback.responses==0);
    UsbPermissionCompat.install(early.context);manual.step();manual.step();early.success();
    check(manual.jobs.isEmpty());manual.shutdownNow();
    System.out.println("PASS XREAL USB client assertions: "+checks);
  }
}'''

WORKER_TEST = '''package dev.beampro.bridge;
import android.content.pm.PackageManager;
public class UsbWorkerModeTest {
 public static void main(String[] args)throws Exception {
  PackageManager pm=new PackageManager();XrealUsbPermissions adapter=new XrealUsbPermissions(pm,10123);
  ShellCommand.output="UNBOUND_USB_GRANTED\\n";
  if(!adapter.grant(10123,"one",13080,1080,12))throw new AssertionError("grant");
  String[] argv=ShellCommand.argv;
  if(argv.length!=8||!argv[0].equals("/system/bin/app_process")||!argv[2].endsWith("$Worker")
    ||!argv[3].equals("10123")||!argv[4].equals("one")||!argv[5].equals("13080")
    ||!argv[6].equals("1080")||!argv[7].equals("12"))throw new AssertionError("worker command");
  ShellCommand.output="UNBOUND_USB_ABSENT\\n";
  if(adapter.grant(10123,"one",13080,1080,12))throw new AssertionError("absent");
  ShellCommand.output="no result";
  try{adapter.grant(10123,"one",13080,1080,12);throw new AssertionError("missing result");}
  catch(java.io.IOException expected){}
  ShellCommand.succeeded=false;
  try{adapter.grant(10123,"one",13080,1080,12);throw new AssertionError("failure");}
  catch(java.io.IOException expected){}
  int calls=ShellCommand.calls;
  try{adapter.grant(10124,"one",13080,1080,12);throw new AssertionError("caller");}
  catch(SecurityException expected){}
  if(ShellCommand.calls!=calls)throw new AssertionError("unauthorized worker started");
  System.out.println("PASS XREAL USB isolated worker routing and result checks");
 }
}'''

WORKER_COMMAND_STUB = '''package dev.beampro.bridge;final class ShellCommand {
 static String[] argv;static String output;static boolean succeeded=true;static int calls;
 static class Result {String output;Result(String v){output=v;}boolean succeeded(){return ShellCommand.succeeded;}}
 static Result run(long timeout,String... command){if(timeout!=10000)throw new AssertionError("timeout");calls++;argv=command;return new Result(output);}
}'''


class UsbPermissionTests(unittest.TestCase):
    def test_production_mode_uses_isolated_process_and_preserves_scope(self):
        self.run_java('dev.beampro.bridge.UsbWorkerModeTest', WORKER_TEST,
                      ROOT / 'bridge/src/dev/beampro/bridge/XrealUsbPermissions.java')
    def test_real_client_fresh_install_deferral_and_callback(self):
        self.run_java('dev.beampro.port.UsbPermissionCompatTest', CLIENT_TEST,
                      ROOT / 'port-client/src/dev/beampro/port/UsbPermissionCompat.java')

    def test_real_shell_adapter_scope_and_both_framework_contracts(self):
        self.run_java('dev.beampro.bridge.XrealUsbPermissionsTest', SERVER_TEST,
                      ROOT / 'bridge/src/dev/beampro/bridge/XrealUsbPermissions.java')

    def run_java(self, name, test, source):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            paths = []
            stubs=dict(STUBS)
            if name.endswith('UsbWorkerModeTest'):
                stubs['dev/beampro/bridge/ShellCommand.java']=WORKER_COMMAND_STUB
            for file, text in {**stubs, name.replace('.', '/') + '.java': test}.items():
                path = work / file
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
                paths.append(str(path))
            sources=[str(source)]
            if source.name=='XrealUsbPermissions.java' and not name.endswith('UsbWorkerModeTest'):
                sources.append(str(ROOT/'bridge/src/dev/beampro/bridge/ShellCommand.java'))
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', str(work), *paths, *sources], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', str(work), name], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS XREAL USB', result.stdout)
            print(result.stdout.strip())

    def test_pixel_framework_contract_from_device(self):
        source = (ROOT / 'artifacts/usb-permission/framework-source/android/hardware/usb/IUsbManager.java').read_text()
        self.assertIn('void grantDevicePermission(UsbDevice usbDevice, String str, int i, boolean z)', source)
        self.assertIn('void getDeviceList(Bundle bundle)', source)

    def test_one_call_patch_round_trip_and_reject_double_patch(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('classes2.dex')
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            patched = patch_usb_permission(original, work / 'patch', JAVA)
            self.assertEqual(patch_usb_permission(patched, work / 'restore', JAVA, restore=True, baseline=original), original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_usb_permission(patched, work / 'double', JAVA)

    def test_controller_requests_and_retry_timer_round_trip(self):
        with zipfile.ZipFile(BASE) as archive:
            original=archive.read('classes.dex')
        with tempfile.TemporaryDirectory() as tmp:
            work=Path(tmp)
            patched=patch_controller_usb_permission(original,work/'patch',JAVA)
            self.assertEqual(patch_controller_usb_permission(patched,work/'restore',JAVA,restore=True,baseline=original),original)
            with self.assertRaises(subprocess.CalledProcessError):
                patch_controller_usb_permission(patched,work/'double',JAVA)


if __name__ == '__main__':
    unittest.main()
