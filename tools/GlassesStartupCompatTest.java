package dev.beampro.port;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import com.xreal.glasses.api.Startup;
import com.xreal.glassesdisplayplugevent.GlassesInitSetting;

public final class GlassesStartupCompatTest {
    static int assertions;
    static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    public static final class Control {
        int state=-1,calls;boolean broken;
        public int nativeGetGlassesStartupState(){calls++;if(broken)throw new IllegalStateException();return state;}
    }
    public static final class Sdk {
        private boolean isEthernetSeries;
        private Context mContext=new Context();
    }
    static Display display(int id,boolean xreal,int w,int h) {
        return new Display(id,xreal,new Display.Mode(17,w,h,120));
    }
    public static void main(String[] args) {
        Sdk sdk=new Sdk();Control control=new Control();
        Context.displays=new DisplayManager();Startup.error=103;
        Display glasses=display(7,true,1920,1080);Context.displays.displays=new Display[]{glasses};
        check(GlassesStartupCompat.getStartupState(control,sdk)==1,"Reported type 11 unsupported query recovers on active mono output");
        check(control.calls==1&&Startup.reads==1,"One original native query and immediate error read");
        check(Startup.error==103,"Keep original native error available to SDK error reporting");
        glasses.current=new Display.Mode(18,3840,1080,60);
        check(GlassesStartupCompat.getStartupState(control,sdk)==1,"Active stereo output also establishes physical readiness");
        control.state=0;int reads=Startup.reads;
        check(GlassesStartupCompat.getStartupState(control,sdk)==0,"Supported not-ready result retains original DP event wait");
        check(Startup.reads==reads,"Do not read errors for a supported query");
        control.state=1;
        check(GlassesStartupCompat.getStartupState(control,sdk)==1,"Supported ready result preserved");
        control.state=2;
        check(GlassesStartupCompat.getStartupState(control,sdk)==2,"Other non-failure native states preserved");
        control.state=-1;
        for(int error:new int[]{0,1,100,101,102,104,105,-1}) {
            Startup.error=error;
            check(GlassesStartupCompat.getStartupState(control,sdk)==-1,"Real native error must fail: "+error);
        }
        Startup.error=103;sdk.isEthernetSeries=true;
        check(GlassesStartupCompat.getStartupState(control,sdk)==-1,"Ethernet models must retain original startup handshake");
        sdk.isEthernetSeries=false;
        for(int type:new int[]{-1,0,1,7,8,9,10,12}) {
            GlassesInitSetting.type=type;
            check(GlassesStartupCompat.getStartupState(control,sdk)==-1,"Fallback must not affect type "+type);
        }
        GlassesInitSetting.type=11;glasses.state=1;
        check(GlassesStartupCompat.getStartupState(control,sdk)==-1,"Powered-off glasses are not ready");
        glasses.state=2;glasses.valid=false;
        check(GlassesStartupCompat.getStartupState(control,sdk)==-1,"Removed/stale glasses are not ready");
        glasses.valid=true;glasses.xreal=false;
        check(GlassesStartupCompat.getStartupState(control,sdk)==-1,"Unrelated external display cannot authorize fallback");
        glasses.xreal=true;glasses.id=0;
        check(GlassesStartupCompat.getStartupState(control,sdk)==-1,"Phone display cannot authorize fallback");
        glasses.id=7;glasses.current=new Display.Mode(1,1280,720,60);
        check(GlassesStartupCompat.getStartupState(control,sdk)==-1,"Unexpected physical mode cannot authorize fallback");
        glasses.current=null;
        check(GlassesStartupCompat.getStartupState(control,sdk)==-1,"Missing physical mode cannot authorize fallback");
        Context.displays.displays=new Display[0];
        check(GlassesStartupCompat.getStartupState(control,sdk)==-1,"Disconnect cannot authorize fallback");
        Context.displays.displays=new Display[]{display(7,true,1920,1080)};
        GlassesInitSetting.broken=true;
        check(GlassesStartupCompat.getStartupState(control,sdk)==-1,"Failed model check remains a failure");
        GlassesInitSetting.broken=false;Startup.broken=true;
        check(GlassesStartupCompat.getStartupState(control,sdk)==-1,"Unavailable native error remains a failure");
        Startup.broken=false;control.broken=true;
        check(GlassesStartupCompat.getStartupState(control,sdk)==-1,"Native query exception remains a failure");
        control.broken=false;Context.displays=null;
        check(GlassesStartupCompat.getStartupState(control,sdk)==-1,"Unavailable display service remains a failure");
        check(GlassesStartupCompat.getStartupState(control,new Object())==-1,"Incompatible SDK remains a failure");
        System.out.println("PASS glasses startup assertions: "+assertions);
    }
}
