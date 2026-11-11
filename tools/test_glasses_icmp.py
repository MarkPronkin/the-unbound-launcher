"""Exercise Samsung's readiness fallback against native socket and Android network boundaries."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_display_startup import JAVA, ROOT

STUBS = {
    'android/content/Context.java': '''package android.content;public class Context {
        public android.net.ConnectivityManager connectivity=new android.net.ConnectivityManager();
        public <T>T getSystemService(Class<T> c){return c.cast(connectivity);}}''',
    'android/net/ConnectivityManager.java': '''package android.net;public class ConnectivityManager {
        public Network network=new Network();public int queries;public boolean changed;
    }''',
    'android/net/Network.java': '''package android.net;public class Network {
        public int binds;public boolean denied;
        public void bindSocket(java.io.FileDescriptor fd)throws java.io.IOException {
            binds++;if(denied)throw new java.io.IOException("network binding denied");}}''',
    'android/os/SystemClock.java': '''package android.os;public class SystemClock {
        public static long now;public static long uptimeMillis(){return now;}}''',
    'android/system/OsConstants.java': '''package android.system;public class OsConstants {
        public static final int AF_INET=2,SOCK_DGRAM=2,IPPROTO_ICMP=1,SOL_SOCKET=1,
            SO_RCVTIMEO=20,EAGAIN=11,EINTR=4; }''',
    'android/system/ErrnoException.java': '''package android.system;public class ErrnoException extends Exception {
        public final int errno;public ErrnoException(String name,int code){super(name);errno=code;}}''',
    'android/system/StructTimeval.java': '''package android.system;public class StructTimeval {
        public long ms;public static StructTimeval fromMillis(long ms){StructTimeval t=new StructTimeval();t.ms=ms;return t;}}''',
    'android/system/Os.java': '''package android.system;
        import java.io.*;import java.net.*;
        public class Os {
            public static int opened,closed,sends,receives,mode;public static byte[] request;public static Runnable beforeReply;
            public static long timeout;public static boolean shortWrite;
            public static FileDescriptor socket(int family,int type,int protocol){
                if(family!=2||type!=2||protocol!=1)throw new AssertionError("unprivileged IPv4 ICMP datagram only");
                opened++;return new FileDescriptor();}
            public static void bind(FileDescriptor fd,InetAddress address,int port){
                if(!address.getHostAddress().equals("169.254.2.10")||port!=0)throw new AssertionError("fixed source only");}
            public static int sendto(FileDescriptor fd,byte[] bytes,int offset,int length,int flags,InetAddress peer,int port){
                if(!peer.getHostAddress().equals("169.254.2.1")||port!=0)throw new AssertionError("fixed destination only");
                sends++;request=bytes.clone();return shortWrite?length-1:length;}
            public static void setsockoptTimeval(FileDescriptor fd,int level,int option,StructTimeval value){
                if(level!=1||option!=20||value.ms<=0||value.ms>100)throw new AssertionError("bounded receive slices");timeout=value.ms;}
            public static int recvfrom(FileDescriptor fd,byte[] bytes,int offset,int length,int flags,InetSocketAddress source)throws Exception {
                receives++;android.os.SystemClock.now+=timeout;
                if(mode==1)throw new ErrnoException("recvfrom",OsConstants.EAGAIN);
                if(mode==2)throw new ErrnoException("recvfrom",13);
                if(mode==3)throw new ErrnoException("recvfrom",OsConstants.EINTR);
                if(beforeReply!=null)beforeReply.run();
                System.arraycopy(request,0,bytes,0,request.length);bytes[0]=0;
                // Native recvfrom fills this immutable-looking Android out parameter.
                java.lang.reflect.Field holder=InetSocketAddress.class.getDeclaredField("holder");holder.setAccessible(true);
                Object h=holder.get(source);java.lang.reflect.Field addr=h.getClass().getDeclaredField("addr");addr.setAccessible(true);
                addr.set(h,InetAddress.getByName(mode==4?"169.254.1.1":"169.254.2.1"));
                if(mode==5)bytes[8]^=1;if(mode==6)bytes[0]=3;if(mode==7)bytes[7]=2;
                return mode==8?7:request.length;
            }
            public static void close(FileDescriptor fd){closed++;}
        }''',
    'dev/beampro/port/EthernetReadinessCompat.java': '''package dev.beampro.port;public class EthernetReadinessCompat {
        static class Reply {final int exit;final boolean timedOut;final String output;
            Reply(int exit,boolean timedOut,String output){this.exit=exit;this.timedOut=timedOut;this.output=output;}
            boolean ready(){return exit==0&&!timedOut;}}}''',
    'dev/beampro/port/GlassesNetworkCompat.java': '''package dev.beampro.port;public class GlassesNetworkCompat {
        static android.net.Network controlNetwork(android.net.ConnectivityManager cm){
            cm.queries++;return cm.changed&&cm.queries>1?null:cm.network;}}''',
}


class GlassesIcmpTests(unittest.TestCase):
    def test_network_binding_real_reply_validation_deadlines_and_cleanup(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, code in STUBS.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(code)
                sources.append(path)
            sources += [ROOT / 'port-client/src/dev/beampro/port/GlassesIcmpProbe.java',
                        ROOT / 'tools/GlassesIcmpProbeTest.java']
            subprocess.run([str(JAVA / 'bin/javac'), '--release', '8', '-d', tmp, *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / 'bin/java'), '--add-opens', 'java.base/java.net=ALL-UNNAMED',
                                     '-cp', tmp, 'dev.beampro.port.GlassesIcmpProbeTest'],
                                    capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS glasses ICMP assertions:', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
