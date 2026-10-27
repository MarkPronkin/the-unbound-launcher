package dev.beampro.startupchecks;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.view.Surface;
import com.xreal.glasses.api.Control;
import com.xreal.glasses.api.Startup;
import com.xreal.glasses.servicesdk.NRServiceControl;
import com.xreal.glassesdisplayplugevent.GlassesInitSetting;

/** Hardware-independent execution of the actual patched SDK callback under ART. */
public final class GlassesStartupDeviceChecks extends Instrumentation {
    int assertions;
    void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    NRServiceControl run(Context context,boolean ethernet,int type,int state,int error,boolean init,boolean ping) {
        Startup.init=init;Startup.initCalls=0;Startup.error=error;Control.state=state;Control.calls=0;
        GlassesInitSetting.type=type;
        NRServiceControl sdk=new NRServiceControl(context,ethernet);
        NRServiceControl.OnStartSDKCallback callback=ok->{sdk.calls++;sdk.result=ok;};
        sdk.prepare(callback);
        sdk.runStartup(callback,0,false,ping);
        return sdk;
    }
    @Override public void onStart() {
        Bundle result=new Bundle();VirtualDisplay display=null;Surface surface=null;SurfaceTexture texture=null;
        try {
            Context context=getTargetContext();
            texture=new SurfaceTexture(false);texture.setDefaultBufferSize(1920,1080);surface=new Surface(texture);
            DisplayManager dm=context.getSystemService(DisplayManager.class);
            display=dm.createVirtualDisplay("XREAL startup fixture",1920,1080,240,surface,0);
            check(display!=null,"Create isolated fixture display");
            // Only the SDK's EDID predicate is faked; DisplayStartupCompat and
            // GlassesStartupCompat operate on Android's real display objects.
            ai.nreal.activitylife.NRXRApp.displayId=display.getDisplay().getDisplayId();
            NRServiceControl sdk=run(context,false,11,-1,103,true,true);
            check(sdk.calls==1&&sdk.result,"Actual SDK callback recovers reported unsupported query");
            check(Startup.initCalls==1&&Control.calls==1,"Original native init and query execute once");
            check(sdk.reportedError==103,"Original SDK error reporting still runs after compatibility hook");
            sdk=run(context,false,11,-1,103,false,true);
            check(sdk.calls==1&&!sdk.result&&sdk.errorCode==3,"Failed native init remains fatal");
            check(Startup.initCalls==2&&Control.calls==0,"Original init retry retained; status never queried on failure");
            sdk=run(context,false,11,-1,103,true,false);
            check(sdk.calls==1&&!sdk.result&&sdk.errorCode==1&&Startup.initCalls==0,"Failed ping cannot enter native init");
            sdk=run(context,false,11,-1,102,true,true);
            check(sdk.calls==1&&!sdk.result&&sdk.errorCode==2,"Other query errors remain fatal");
            sdk=run(context,true,11,-1,103,true,true);
            check(sdk.calls==1&&!sdk.result&&sdk.errorCode==2,"Ethernet query failure remains fatal");
            sdk=run(context,false,10,-1,103,true,true);
            check(sdk.calls==1&&!sdk.result,"Other H-series model remains unchanged");
            sdk=run(context,false,11,0,0,true,true);
            check(sdk.calls==0&&sdk.waiting,"Supported not-ready status retains original DP wait");
            sdk=run(context,true,7,0,0,true,true);
            check(sdk.calls==0&&sdk.waiting,"Ethernet DP wait retained");
            sdk=run(context,false,11,1,0,true,true);
            check(sdk.calls==1&&sdk.result,"Supported ready status succeeds through original callback");
            display.release();display=null;ai.nreal.activitylife.NRXRApp.displayId=-1;
            sdk=run(context,false,11,-1,103,true,true);
            check(sdk.calls==1&&!sdk.result,"Absent XREAL output cannot authorize fallback");
            result.putString("stream","PASS actual SDK startup DEX assertions: "+assertions+"\n");finish(-1,result);
        } catch(Throwable error) {
            result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);
        } finally {
            if(display!=null)display.release();if(surface!=null)surface.release();if(texture!=null)texture.release();
        }
    }
}
