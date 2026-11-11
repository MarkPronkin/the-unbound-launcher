package dev.beampro.port;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;

/** A real echo exchange when an OEM denies even the shell's SO_BINDTODEVICE ping. */
final class GlassesIcmpProbe {
    private static final int TIMEOUT_MS = 1000;

    static EthernetReadinessCompat.Reply ping(Context context) throws Exception {
        requireActive();
        ConnectivityManager connectivity=context.getSystemService(ConnectivityManager.class);
        if (connectivity==null) throw new IOException("Android connectivity service is unavailable");
        Network network=GlassesNetworkCompat.controlNetwork(connectivity);
        if (network==null) throw new IOException("Android has not published the glasses control network");
        InetAddress peer=InetAddress.getByName("169.254.2.1");
        FileDescriptor socket=Os.socket(OsConstants.AF_INET,OsConstants.SOCK_DGRAM,OsConstants.IPPROTO_ICMP);
        try {
            // Network.bindSocket uses Android's supported socket routing API, without
            // SO_BINDTODEVICE or a process-wide binding that could redirect app traffic.
            network.bindSocket(socket);
            Os.bind(socket,InetAddress.getByName("169.254.2.10"),0);
            requireNetwork(connectivity,network);
            requireActive();
            byte[] request=new byte[24];
            new SecureRandom().nextBytes(request);
            request[0]=8;request[1]=0;request[2]=0;request[3]=0;
            request[4]=0;request[5]=0;request[6]=0;request[7]=1;
            // Linux's datagram ICMP socket fills in its own identifier and checksum.
            if (Os.sendto(socket,request,0,request.length,0,peer,0)!=request.length)
                throw new IOException("Incomplete glasses echo request");
            long deadline=SystemClock.uptimeMillis()+TIMEOUT_MS;
            byte[] response=new byte[256];
            while (true) {
                requireActive();
                long remaining=deadline-SystemClock.uptimeMillis();
                if (remaining<=0) return new EthernetReadinessCompat.Reply(1,true,"Glasses network ICMP echo timed out");
                Os.setsockoptTimeval(socket,OsConstants.SOL_SOCKET,OsConstants.SO_RCVTIMEO,
                        StructTimeval.fromMillis(Math.min(100,remaining)));
                InetSocketAddress source=new InetSocketAddress(0);
                try {
                    int count=Os.recvfrom(socket,response,0,response.length,0,source);
                    if (peer.equals(source.getAddress()) && matches(request,response,count)) {
                        requireActive();
                        requireNetwork(connectivity,network);
                        return new EthernetReadinessCompat.Reply(0,false,"Glasses network ICMP echo reply: "+peer.getHostAddress());
                    }
                } catch (ErrnoException error) {
                    if (error.errno!=OsConstants.EAGAIN && error.errno!=OsConstants.EINTR) throw error;
                }
            }
        } finally { Os.close(socket); }
    }

    private static void requireNetwork(ConnectivityManager connectivity,Network expected) throws IOException {
        if (!expected.equals(GlassesNetworkCompat.controlNetwork(connectivity)))
            throw new IOException("Glasses control network changed during echo probe");
    }

    private static void requireActive() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Glasses echo cancelled");
    }

    static boolean matches(byte[] request,byte[] response,int count) {
        if (count!=request.length || response[0]!=0 || response[1]!=0
                || response[6]!=request[6] || response[7]!=request[7]) return false;
        // The kernel demultiplexes the rewritten identifier; match our random payload.
        for (int i=8;i<request.length;i++) if (request[i]!=response[i]) return false;
        return true;
    }

    private GlassesIcmpProbe() { }
}
