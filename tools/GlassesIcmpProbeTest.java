package dev.beampro.port;

import android.content.Context;
import android.system.Os;
import java.io.IOException;

public final class GlassesIcmpProbeTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static Context setup(){
        Os.opened=Os.closed=Os.sends=Os.receives=Os.mode=0;Os.shortWrite=false;Os.beforeReply=null;
        android.os.SystemClock.now=0;return new Context();
    }
    public static void main(String[] args)throws Exception {
        Context context=setup();
        check(GlassesIcmpProbe.ping(context).ready(),"A matching real echo exchange satisfies readiness");
        check(context.connectivity.network.binds==1,"Bind only the echo socket to the glasses Network");
        check(Os.opened==1&&Os.closed==1&&Os.sends==1,"One datagram request and deterministic cleanup");
        check(Os.request[0]==8&&Os.request[1]==0&&Os.request[7]==1,"Send an IPv4 echo request");
        byte[] first=Os.request.clone();GlassesIcmpProbe.ping(context);
        check(!java.util.Arrays.equals(first,Os.request),"Fresh payload prevents accepting another poll's reply");
        for(int mode:new int[]{1,3,4,5,6,7,8}) {
            context=setup();Os.mode=mode;
            EthernetReadinessCompat.Reply reply=GlassesIcmpProbe.ping(context);
            check(!reply.ready()&&reply.timedOut,"Silence, interruptions, wrong peer/payload/type/sequence/length fail: "+mode);
            check(android.os.SystemClock.now==1000&&Os.sends==1,"One-second total deadline despite unrelated packets");
            check(Os.closed==1,"Close a timed-out socket");
        }
        context=setup();context.connectivity.network=null;
        try{GlassesIcmpProbe.ping(context);throw new AssertionError("Missing network accepted");}catch(IOException expected){checks++;}
        check(Os.opened==0,"Missing glasses network cannot send through Wi-Fi");
        context=setup();context.connectivity.network.denied=true;
        try{GlassesIcmpProbe.ping(context);throw new AssertionError("Denied binding accepted");}catch(IOException expected){checks++;}
        check(Os.sends==0&&Os.closed==1,"Denied binding closes before sending");
        context=setup();context.connectivity.changed=true;
        try{GlassesIcmpProbe.ping(context);throw new AssertionError("Changed network accepted");}catch(IOException expected){checks++;}
        check(Os.sends==0&&Os.closed==1,"Revalidate network before transmitting");
        context=setup();final Context changed=context;Os.beforeReply=()->changed.connectivity.changed=true;
        try{GlassesIcmpProbe.ping(context);throw new AssertionError("Stale reply accepted");}catch(IOException expected){checks++;}
        check(Os.closed==1,"Revalidate network after a matching reply");
        context=setup();Os.shortWrite=true;
        try{GlassesIcmpProbe.ping(context);throw new AssertionError("Short send accepted");}catch(IOException expected){checks++;}
        check(Os.receives==0&&Os.closed==1,"Incomplete send cannot satisfy readiness");
        context=setup();Os.mode=2;
        try{GlassesIcmpProbe.ping(context);throw new AssertionError("Socket error accepted");}catch(android.system.ErrnoException expected){checks++;}
        check(Os.closed==1,"Socket error closes descriptor");
        context=setup();Thread.currentThread().interrupt();
        try{GlassesIcmpProbe.ping(context);throw new AssertionError("Interrupted probe accepted");}catch(java.io.InterruptedIOException expected){checks++;}
        finally{Thread.interrupted();}
        check(Os.opened==0&&Os.sends==0,"Cancellation cannot open or send another probe");
        context=setup();Os.beforeReply=()->Thread.currentThread().interrupt();
        try{GlassesIcmpProbe.ping(context);throw new AssertionError("Cancelled reply accepted");}
        catch(java.io.InterruptedIOException expected){checks++;}finally{Thread.interrupted();}
        check(Os.closed==1,"Cancellation during receive closes descriptor");
        System.out.println("PASS glasses ICMP assertions: "+checks);
    }
}
