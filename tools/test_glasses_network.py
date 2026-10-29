"""Exercise the real network selector and guarded SDK callback with Android service fixtures."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_display_startup import ROOT, JAVA
from test_usb_permission import STUBS as USB_STUBS

STUBS = {
    'android/net/Network.java': '''package android.net; public class Network {
      public final int id;public Network(int id){this.id=id;}
      public boolean equals(Object o){return o instanceof Network&&((Network)o).id==id;}
      public int hashCode(){return id;}public String toString(){return "network-"+id;}}''',
    'android/net/NetworkCapabilities.java': '''package android.net; public class NetworkCapabilities {
      public static final int TRANSPORT_ETHERNET=3,NET_CAPABILITY_NOT_RESTRICTED=13;
      public boolean ethernet=true,unrestricted=true;
      public boolean hasTransport(int t){return ethernet&&t==TRANSPORT_ETHERNET;}
      public boolean hasCapability(int c){return unrestricted&&c==NET_CAPABILITY_NOT_RESTRICTED;}}''',
    'android/net/IpPrefix.java': '''package android.net; public class IpPrefix {
      public final String prefix;public IpPrefix(String p){prefix=p;}
      public int getPrefixLength(){return Integer.parseInt(prefix.split("/")[1]);}
      public boolean contains(java.net.InetAddress ip){return ip.getHostAddress().startsWith(prefix.substring(0,prefix.lastIndexOf('.')+1));}}''',
    'android/net/LinkAddress.java': '''package android.net; public class LinkAddress {
      final java.net.InetAddress ip;final int prefix;
      public LinkAddress(String s)throws Exception {ip=java.net.InetAddress.getByName(s.split("/")[0]);prefix=Integer.parseInt(s.split("/")[1]);}
      public int getPrefixLength(){return prefix;}public java.net.InetAddress getAddress(){return ip;}}''',
    'android/net/RouteInfo.java': '''package android.net; public class RouteInfo {
      public String iface;public IpPrefix prefix;public boolean gateway,defaultRoute;
      public RouteInfo(String p,String i){prefix=new IpPrefix(p);iface=i;}
      public IpPrefix getDestination(){return prefix;}public String getInterface(){return iface;}
      public boolean hasGateway(){return gateway;}public boolean isDefaultRoute(){return defaultRoute;}}''',
    'android/net/LinkProperties.java': '''package android.net; public class LinkProperties {
      public String iface;public java.util.List<LinkAddress> addresses=new java.util.ArrayList<>();
      public java.util.List<RouteInfo> routes=new java.util.ArrayList<>();
      public String getInterfaceName(){return iface;}public java.util.List<LinkAddress> getLinkAddresses(){return addresses;}
      public java.util.List<RouteInfo> getRoutes(){return routes;}}''',
    'android/net/ConnectivityManager.java': '''package android.net; public class ConnectivityManager {
      public Network bound;public int bindCalls;public Network reject,throwOn;public Runnable afterBind;
      public java.util.Map<Network,LinkProperties> links=new java.util.LinkedHashMap<>();
      public java.util.Map<Network,NetworkCapabilities> caps=new java.util.LinkedHashMap<>();
      public Network[] getAllNetworks(){return links.keySet().toArray(new Network[0]);}
      public LinkProperties getLinkProperties(Network n){return links.get(n);}
      public NetworkCapabilities getNetworkCapabilities(Network n){return caps.get(n);}
      public Network getBoundNetworkForProcess(){return bound;}
      public boolean bindProcessToNetwork(Network n){bindCalls++;
        if(n!=null&&n.equals(throwOn))throw new SecurityException("denied");
        if(n!=null&&n.equals(reject))return false;bound=n;
        if(afterBind!=null){Runnable r=afterBind;afterBind=null;r.run();}return true;}}''',
    'android/content/Context.java': '''package android.content; public class Context {
      public android.hardware.usb.UsbManager usb=new android.hardware.usb.UsbManager();
      public android.net.ConnectivityManager connectivity=new android.net.ConnectivityManager();
      public <T>T getSystemService(Class<T> c){return c.cast(c==android.hardware.usb.UsbManager.class?usb:connectivity);}}''',
    'android/os/SystemClock.java': '''package android.os; public class SystemClock {
      public static long now;public static Runnable sleepHook;public static long uptimeMillis(){return now;}
      public static void sleep(long ms){now+=ms;if(sleepHook!=null){Runnable r=sleepHook;sleepHook=null;r.run();}}}''',
    'android/os/Handler.java': '''package android.os; public class Handler {
      public boolean post(Runnable r){r.run();return true;}public void removeCallbacks(Runnable r){}}''',
    'android/util/Log.java': '''package android.util; public class Log {
      public static int i(String t,String m){return 0;}public static int w(String t,String m){return 0;}
      public static int w(String t,String m,Throwable e){return 0;}public static int e(String t,String m,Throwable e){return 0;}}''',
    'dev/beampro/port/GlassesConnectionCompat.java': '''package dev.beampro.port;public class GlassesConnectionCompat {
      static Object get(Object o,String name)throws Exception {java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}}''',
    'com/xreal/glassesdisplayplugevent/GlassesInitSetting.java': '''package com.xreal.glassesdisplayplugevent;
      public class GlassesInitSetting {public static android.content.Context context;public static boolean ethernet=true;
      public static GlassesInitSetting getInstance(){return new GlassesInitSetting();}
      public android.content.Context getContext(){return context;}public boolean isEthernetSeriesGlasses(){return ethernet;}}''',
    'com/xreal/glasses/servicesdk/helper/PingGSeriesHelper.java': '''package com.xreal.glasses.servicesdk.helper;
      public class PingGSeriesHelper {public interface PingCallback {void onPingResult(boolean ok);}}''',
}


class GlassesNetworkTests(unittest.TestCase):
    def test_selector_callback_scope_failures_reconnect_and_restoration(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, code in {**USB_STUBS, **STUBS}.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(code)
                sources.append(path)
            sources += [ROOT / 'port-client/src/dev/beampro/port' / n for n in
                        ('GlassesNetworkCompat.java', 'PingCallbackCompat.java')]
            sources += [ROOT / 'compat/src/dev/beampro/compat/GlassesIdentity.java', ROOT / 'tools/GlassesNetworkTest.java']
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp, *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp, 'dev.beampro.port.GlassesNetworkTest'],
                                    capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS glasses control-network assertions:', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
