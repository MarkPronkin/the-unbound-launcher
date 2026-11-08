"""Exercise the production bootstrap across Wi-Fi loss, daemon retention and reboot recovery."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_display_startup import ROOT, JAVA


STUBS = {
    'android/content/Context.java': '''package android.content; public class Context {
      public ContentResolver resolver=new ContentResolver();
      public android.net.ConnectivityManager connectivity=new android.net.ConnectivityManager();
      private java.util.Map<String,SharedPreferences> preferences=new java.util.HashMap<>();
      public Context getApplicationContext(){return this;}public ContentResolver getContentResolver(){return resolver;}
      public SharedPreferences getSharedPreferences(String n,int mode){return preferences.computeIfAbsent(n,k->new SharedPreferences());}
      public <T>T getSystemService(Class<T> c){return c.cast(connectivity);}
      public android.content.pm.ApplicationInfo getApplicationInfo(){return new android.content.pm.ApplicationInfo();}
      public String getPackageName(){return "dev.markpronkin.unbound";}}''',
    'android/content/SharedPreferences.java': '''package android.content; public class SharedPreferences {
      java.util.Map<String,Object> values=new java.util.HashMap<>();
      public boolean getBoolean(String k,boolean d){return values.containsKey(k)?(Boolean)values.get(k):d;}
      public int getInt(String k,int d){return values.containsKey(k)?(Integer)values.get(k):d;}
      public Editor edit(){return new Editor();}public class Editor {
      public Editor putBoolean(String k,boolean v){values.put(k,v);return this;}
      public Editor putInt(String k,int v){values.put(k,v);return this;}public void apply(){}}}''',
    'android/content/ContentResolver.java': '''package android.content;public class ContentResolver {
      public android.os.IBinder binder;public boolean denied;
      public java.util.Map<String,Integer> settings=new java.util.HashMap<>();
      public android.os.Bundle call(android.net.Uri uri,String m,String a,android.os.Bundle b){
      android.os.Bundle result=new android.os.Bundle();result.binder=binder;return result;}}''',
    'android/content/Intent.java': '''package android.content;public class Intent {
      public Intent(Context c,Class<?> t){}public Intent putExtra(String k,boolean v){return this;}}''',
    'android/content/pm/ApplicationInfo.java': '''package android.content.pm;public class ApplicationInfo {
      public String sourceDir="/data/app/test'quote/base.apk";}''',
    'android/app/Activity.java': '''package android.app;public class Activity extends android.content.Context {
      public void startActivity(android.content.Intent i){}}''',
    'android/net/Uri.java': '''package android.net;public class Uri {public static Uri parse(String s){return new Uri();}}''',
    'android/net/Network.java': '''package android.net;public class Network {
      public NetworkCapabilities capabilities;public String name;public Network(int... types){capabilities=new NetworkCapabilities(types);name="net"+java.util.Arrays.toString(types);}
      public String toString(){return name;}}''',
    'android/net/NetworkCapabilities.java': '''package android.net;public class NetworkCapabilities {
      public static final int TRANSPORT_WIFI=1,TRANSPORT_CELLULAR=0,TRANSPORT_ETHERNET=3,TRANSPORT_VPN=4;
      public static final int NET_CAPABILITY_NOT_VPN=15;
      private int[] types;public NetworkCapabilities(int... types){this.types=types;}
      public boolean hasTransport(int t){for(int type:types)if(type==t)return true;return false;}}''',
    'android/net/LinkProperties.java': '''package android.net;public class LinkProperties {
      public String iface="wlan0";public java.util.List<String> routes=new java.util.ArrayList<>();
      public String getInterfaceName(){return iface;}public java.util.List<String> getLinkAddresses(){return java.util.Collections.emptyList();}
      public java.util.List<String> getRoutes(){return routes;}}''',
    'android/net/ConnectivityManager.java': '''package android.net;public class ConnectivityManager {
      public boolean denied;public Network[] networks=new Network[]{new Network(1)};
      public Network[] getAllNetworks(){if(denied)throw new SecurityException();return networks;}
      public LinkProperties link=new LinkProperties();public Network getBoundNetworkForProcess(){return null;}
      public LinkProperties getLinkProperties(Network n){return link;}
      public NetworkCapabilities getNetworkCapabilities(Network n){return n.capabilities;}}''',
    'android/os/IBinder.java': '''package android.os;public interface IBinder {boolean isBinderAlive();boolean pingBinder();}''',
    'android/os/Bundle.java': '''package android.os;public class Bundle {
      public IBinder binder;public IBinder getBinder(String k){return binder;}}''',
    'android/os/Build.java': '''package android.os;public class Build {public static class VERSION {public static int SDK_INT=34;}}''',
    'android/os/Looper.java': '''package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}''',
    'android/os/Handler.java': '''package android.os;public class Handler {public Handler(Looper l){}
      public void post(Runnable r){r.run();}public void postDelayed(Runnable r,long ms){}}''',
    'android/os/SystemClock.java': '''package android.os;public class SystemClock {
      public static long now;public static Runnable tick;
      public static long elapsedRealtime(){return now;}
      public static void sleep(long ms){now+=ms;if(tick!=null)tick.run();}}''',
    'android/provider/Settings.java': '''package android.provider;public class Settings {public static class Global {
      public static final String ADB_ENABLED="adb_enabled",BOOT_COUNT="boot_count";
      public static int getInt(android.content.ContentResolver r,String k,int d){
      if(r.denied)throw new SecurityException();return r.settings.containsKey(k)?r.settings.get(k):d;}}}''',
    'android/util/Log.java': '''package android.util;public class Log {
      public static int e(String t,String m){return 0;}public static int w(String t,String m,Throwable e){return 0;}}''',
    'dev/beampro/settings/MenuTransitions.java': '''package dev.beampro.settings;public class MenuTransitions {
      public static void open(android.app.Activity a,android.content.Intent i){}}''',
    'dev/beampro/settings/SetupNavigation.java': '''package dev.beampro.settings;public class SetupNavigation {
      public static android.content.Intent forward(android.app.Activity a,Class<?> t){return new android.content.Intent(a,t);}}''',
    'dev/beampro/adb/AdbPairingActivity.java': '''package dev.beampro.adb;public class AdbPairingActivity {public static String SETUP="setup";}''',
    'dev/beampro/adb/HelperPayload.java': '''package dev.beampro.adb;public class HelperPayload {public static String SHA256="pinned-sha";}''',
    'dev/beampro/adb/AutoGlassesReceiver.java': '''package dev.beampro.adb;public class AutoGlassesReceiver {
      public static int notifications;public static void helperReady(android.content.Context c){notifications++;}}''',
    'dev/beampro/adb/AdbDiscovery.java': '''package dev.beampro.adb;public class AdbDiscovery implements AutoCloseable {
      public static int created,closed;public static java.util.List<Integer> found=java.util.Arrays.asList(37002);
      public AdbDiscovery(android.content.Context c){created++;}public java.util.List<Integer> ports(boolean pair){return found;}
      private boolean stopped;public boolean isClosed(){return stopped;}public void close(){closed++;stopped=true;}}''',
    'io/github/muntashirakon/adb/AdbStream.java': '''package io.github.muntashirakon.adb;
      public class AdbStream implements AutoCloseable {private String text;public AdbStream(String text){this.text=text;}
      public java.io.InputStream openInputStream(){return new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
      public void close(){}}''',
    'dev/beampro/adb/LocalAdb.java': '''package dev.beampro.adb;public class LocalAdb {
      public static int created;public int accepted=37001,disconnects,starts;public String uid="2000",usb="1";
      public java.util.List<Integer> attempts=new java.util.ArrayList<>();public java.util.List<String> commands=new java.util.ArrayList<>();
      android.content.Context context;public LocalAdb(android.content.Context c){context=c;created++;}
      public boolean pair(String host,int port,String code)throws Exception{return true;}
      public boolean connect(String host,int port)throws java.io.IOException,InterruptedException{
      if(!"127.0.0.1".equals(host))throw new AssertionError("Non-local ADB");attempts.add(port);
      if(port!=accepted)throw new java.io.IOException("listener unavailable");return true;}
      public void disconnect(){disconnects++;}
      public io.github.muntashirakon.adb.AdbStream openStream(String command)throws java.io.IOException{commands.add(command);String output;
      if(command.equals("shell:id -u"))output=uid;
      else if(command.equals("shell:settings get global adb_enabled"))output=usb;
      else if(command.startsWith("shell:mkdir")){starts++;context.resolver.binder=new android.os.IBinder(){
      public boolean isBinderAlive(){return true;}public boolean pingBinder(){return true;}};output="UNBOUND_HELPER_STARTED";}
      else throw new AssertionError("Unexpected ADB command: "+command);
      return new io.github.muntashirakon.adb.AdbStream(output);}}''',
}


class MobileAdbTests(unittest.TestCase):
    def test_real_bootstrap_and_network_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, code in STUBS.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(code)
                sources.append(path)
            sources += [ROOT / 'embedded-adb/src/dev/beampro/adb' / name for name in
                        ('AdbBootstrap.java', 'AdbNetworkState.java')]
            sources.append(ROOT / 'tools/MobileAdbTest.java')
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp, *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp, 'dev.beampro.adb.MobileAdbTest'],
                                    capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
