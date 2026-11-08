"""Exercise production ADB discovery, retries and TCP sockets through VPN transitions."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_mobile_adb import STUBS, ROOT, JAVA


DISCOVERY_STUBS = {
    'android/content/Context.java': STUBS['android/content/Context.java'].replace(
        'public ContentResolver resolver=',
        'public static final String NSD_SERVICE="nsd";public android.net.nsd.NsdManager nsd=new android.net.nsd.NsdManager();'
        'public Object getSystemService(String n){return nsd;}public ContentResolver resolver='),
    'android/net/ConnectivityManager.java': STUBS['android/net/ConnectivityManager.java'].replace(
        'public boolean denied;', '''public int registrations,unregistrations;public NetworkCallback callback;
        public void registerNetworkCallback(NetworkRequest r,NetworkCallback c){if(denied)throw new SecurityException();callback=c;registrations++;}
        public void unregisterNetworkCallback(NetworkCallback c){unregistrations++;callback=null;}
        public static class NetworkCallback{public void onAvailable(Network n){}public void onLost(Network n){}
        public void onCapabilitiesChanged(Network n,NetworkCapabilities c){}public void onLinkPropertiesChanged(Network n,LinkProperties p){}}
        public boolean denied;'''),
    'android/net/NetworkRequest.java': '''package android.net;public class NetworkRequest {
      public boolean wifi,notVpn,cleared;
      public static class Builder {NetworkRequest r=new NetworkRequest();
      public Builder clearCapabilities(){r.cleared=true;return this;}
      public Builder addTransportType(int t){r.wifi=t==NetworkCapabilities.TRANSPORT_WIFI;return this;}
      public Builder addCapability(int c){r.notVpn=c==NetworkCapabilities.NET_CAPABILITY_NOT_VPN;return this;}
      public NetworkRequest build(){return r;}}}''',
    'android/os/Handler.java': '''package android.os;public class Handler {
      static class Task{Runnable r;long at;Task(Runnable r,long at){this.r=r;this.at=at;}}
      static java.util.List<Task> tasks=new java.util.ArrayList<>();public Handler(Looper l){}
      public boolean post(Runnable r){return postDelayed(r,0);}
      public boolean postDelayed(Runnable r,long ms){tasks.add(new Task(r,SystemClock.now+ms));return true;}
      public void removeCallbacks(Runnable r){tasks.removeIf(t->t.r==r);}
      public static void advance(long ms){long until=SystemClock.now+ms;
      while(true){Task next=null;for(Task t:tasks)if(t.at<=until&&(next==null||t.at<next.at))next=t;
      if(next==null)break;tasks.remove(next);SystemClock.now=next.at;next.r.run();}SystemClock.now=until;}
      public static int pending(){return tasks.size();}}''',
    'android/net/nsd/NsdServiceInfo.java': '''package android.net.nsd;public class NsdServiceInfo {
      public String name,type;public int port;public java.net.InetAddress host;public android.net.Network network;
      public NsdServiceInfo(String name,String type,int port,String host)throws Exception{
      this.name=name;this.type=type;this.port=port;this.host=java.net.InetAddress.getByName(host);}
      public String getServiceName(){return name;}public String getServiceType(){return type;}
      public int getPort(){return port;}public java.net.InetAddress getHost(){return host;}
      public android.net.Network getNetwork(){return network;}}''',
    'android/net/nsd/NsdManager.java': '''package android.net.nsd;public class NsdManager {
      public static final int PROTOCOL_DNS_SD=1;public boolean rejectScoped,rejectAll;
      public int stops,scoped,legacy,cancelled;public android.net.NetworkRequest lastRequest;
      public java.util.List<DiscoveryListener> listeners=new java.util.ArrayList<>();
      public java.util.List<ResolveListener> resolutions=new java.util.ArrayList<>();
      public void discoverServices(String t,int p,DiscoveryListener l){if(rejectAll)throw new SecurityException();legacy++;listeners.add(l);}
      public void discoverServices(String t,int p,android.net.NetworkRequest r,java.util.concurrent.Executor e,DiscoveryListener l){
      if(rejectScoped||rejectAll)throw new SecurityException();lastRequest=r;scoped++;listeners.add(l);}
      public void stopServiceDiscovery(DiscoveryListener l){stops++;}
      public void resolveService(NsdServiceInfo s,ResolveListener l){resolutions.add(l);}
      public void stopServiceResolution(ResolveListener l){cancelled++;}
      public interface DiscoveryListener{void onDiscoveryStarted(String t);void onDiscoveryStopped(String t);
      void onStartDiscoveryFailed(String t,int e);void onStopDiscoveryFailed(String t,int e);
      void onServiceFound(NsdServiceInfo i);void onServiceLost(NsdServiceInfo i);}
      public interface ResolveListener{void onResolveFailed(NsdServiceInfo i,int e);void onServiceResolved(NsdServiceInfo i);}}''',
}


def run_java(stubs, sources, main):
    with tempfile.TemporaryDirectory() as tmp:
        paths = []
        for name, value in stubs.items():
            path = Path(tmp) / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(value)
            paths.append(path)
        subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp,
                        *map(str, paths + sources)], check=True)
        result = subprocess.run([str(JAVA / 'bin/java'), '-cp', tmp, main],
                                capture_output=True, text=True, timeout=20)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)
        print(result.stdout.strip())


class VpnAdbTests(unittest.TestCase):
    def test_real_bootstrap_during_vpn_changes(self):
        run_java(STUBS, [ROOT / 'embedded-adb/src/dev/beampro/adb' / n for n in
                        ('AdbBootstrap.java', 'AdbNetworkState.java')] + [ROOT / 'tools/VpnAdbTest.java'],
                 'dev.beampro.adb.VpnAdbTest')

    def test_real_discovery_lifecycle(self):
        stubs = dict(STUBS)
        del stubs['dev/beampro/adb/AdbDiscovery.java']
        stubs.update(DISCOVERY_STUBS)
        # The other stub classes do not require the real bootstrap in this test.
        run_java(stubs, [ROOT / 'embedded-adb/src/dev/beampro/adb' / n for n in
                        ('AdbNetworkState.java', 'AdbDiscovery.java')] + [ROOT / 'tools/AdbDiscoveryTest.java'],
                 'dev.beampro.adb.AdbDiscoveryTest')

    def test_real_loopback_socket(self):
        run_java({}, [ROOT / 'embedded-adb/vendor/libadb/java/io/github/muntashirakon/adb/AdbSocket.java',
                      ROOT / 'tools/AdbSocketTest.java'], 'io.github.muntashirakon.adb.AdbSocketTest')


if __name__ == '__main__':
    unittest.main()
