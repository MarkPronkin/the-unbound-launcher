package dev.beampro.port;

import android.app.Instrumentation;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.SystemClock;

/** Execute the shipped ICMP fallback under an ordinary Android app UID. */
public final class GlassesIcmpDeviceChecks extends Instrumentation {
    int checks;
    boolean timeout;
    void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    @Override public void onCreate(Bundle args){super.onCreate(args);timeout="true".equals(args.getString("timeout"));start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            Context context=getTargetContext();
            ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);
            Network previous=cm.getBoundNetworkForProcess();
            GlassesNetworkCompat.chosen=cm.getActiveNetwork();
            check(android.os.Process.myUid()>=10000,"Run real socket operations as an unprivileged app");
            check(GlassesNetworkCompat.chosen!=null,"Emulator has an Android Network");
            long started=SystemClock.uptimeMillis();
            EthernetReadinessCompat.Reply reply=GlassesIcmpProbe.ping(context);
            long duration=SystemClock.uptimeMillis()-started;
            if(timeout){
                check(!reply.ready()&&reply.timedOut,"Dropped echo replies remain a readiness failure");
                check(duration>=900&&duration<2500,"Real receive timeout stays bounded");
            }else{
                check(reply.ready(),"Actual kernel echo exchange on the selected Network succeeds");
                check(duration<1500,"Successful echo completes within the deadline");
                int descriptors=new java.io.File("/proc/self/fd").list().length;
                for(int i=0;i<8;i++)check(GlassesIcmpProbe.ping(context).ready(),"Repeated real echo succeeds");
                check(new java.io.File("/proc/self/fd").list().length<=descriptors,"Echo sockets do not leak descriptors");
            }
            check(java.util.Objects.equals(previous,cm.getBoundNetworkForProcess()),"Echo never changes process routing");
            GlassesNetworkCompat.missing=true;
            try{GlassesIcmpProbe.ping(context);throw new AssertionError("Missing network accepted");}
            catch(java.io.IOException expected){checks++;}finally{GlassesNetworkCompat.missing=false;}
            Thread.currentThread().interrupt();
            try{GlassesIcmpProbe.ping(context);throw new AssertionError("Cancelled probe accepted");}
            catch(java.io.InterruptedIOException expected){checks++;}finally{Thread.interrupted();}
            result.putString("stream","PASS Android glasses ICMP assertions: "+checks+"; timeout="+timeout
                    +"; uid="+android.os.Process.myUid()+"; durationMs="+duration+"\n");finish(-1,result);
        }catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);}
    }
}
