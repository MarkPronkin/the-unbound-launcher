package dev.beampro.port;

import android.app.Instrumentation;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import java.lang.reflect.*;

/** Check Android's real socket marking and the shipped adapter's restoration under ART. */
public final class GlassesNetworkDeviceChecks extends Instrumentation {
    static { System.loadLibrary("NetworkChecks"); }
    static native int socketMark(boolean create);
    static native void closeSocket();
    int checks;
    void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static class Callback {
        int calls;boolean ready,broken;
        public void onPingResult(boolean result){calls++;ready=result;if(broken)throw new IllegalStateException("native fixture failure");}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart() {
        Bundle result=new Bundle();ConnectivityManager cm=null;Network previous=null;
        try {
            Context context=getTargetContext();cm=context.getSystemService(ConnectivityManager.class);
            previous=cm.getBoundNetworkForProcess();Network network=cm.getActiveNetwork();
            check(network!=null,"Emulator has an active Android network");
            check(GlassesNetworkCompat.controlNetwork(cm)==null,"Ordinary emulator networks are not misidentified as glasses");
            Method restore=GlassesNetworkCompat.class.getDeclaredMethod("restore",ConnectivityManager.class,Network.class,Network.class);
            restore.setAccessible(true);
            check(cm.bindProcessToNetwork(network),"Supported API binds an unprivileged app process");
            int mark=socketMark(true);int id=(int)(network.getNetworkHandle()>>>32);
            check((mark&0xffff)==id,"Native libc socket inherits selected Android network");
            restore.invoke(null,cm,network,previous);
            check(java.util.Objects.equals(previous,cm.getBoundNetworkForProcess()),"Shipped finally cleanup restores prior routing");
            check(socketMark(false)==mark,"Existing native socket retains its network after process restoration");
            if(previous==null)check((socketMark(true)&0xffff)!=id,"New sockets use normal routing after cleanup");
            check(cm.bindProcessToNetwork(network),"Bind again before stale-prior-network recovery");
            Network gone=Network.fromNetworkHandle((0x7ffeL<<32)|0xcafed00dL);
            restore.invoke(null,cm,network,gone);
            check(cm.getBoundNetworkForProcess()==null,"Unavailable prior network clears the temporary binding");
            com.xreal.glassesdisplayplugevent.GlassesInitSetting.context=context;
            com.xreal.glassesdisplayplugevent.GlassesInitSetting.ethernet=true;
            Callback callback=new Callback();Method deliver=Callback.class.getMethod("onPingResult",boolean.class);
            GlassesNetworkCompat.deliver(deliver,callback,true);
            check(callback.calls==1&&!callback.ready,"No authorized glasses stops native startup under real Android services");
            check(cm.getBoundNetworkForProcess()==null,"Rejected setup leaves process routing clear");
            com.xreal.glassesdisplayplugevent.GlassesInitSetting.ethernet=false;
            callback=new Callback();GlassesNetworkCompat.deliver(deliver,callback,true);
            check(callback.calls==1&&callback.ready,"Non-Ethernet startup remains unchanged in shipped adapter");
            callback=new Callback();GlassesNetworkCompat.deliver(deliver,callback,false);
            check(callback.calls==1&&!callback.ready,"Failed readiness remains failure");
            callback=new Callback();callback.broken=true;
            try{GlassesNetworkCompat.deliver(deliver,callback,true);throw new AssertionError("Expected callback exception");}
            catch(InvocationTargetException expected){}
            check(callback.calls==1,"Native callback exception does not trigger a second callback");
            result.putString("stream","PASS Android glasses control-network assertions: "+checks
                    +"; netId="+id+"; native socket mark="+mark+"\n");finish(-1,result);
        } catch(Throwable error) {result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);}
        finally {closeSocket();if(cm!=null)cm.bindProcessToNetwork(previous);}
    }
}
