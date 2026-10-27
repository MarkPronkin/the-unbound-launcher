import android.app.*;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import ai.nreal.activitylife.*;
import dev.beampro.port.DisplayStartupCompat;

/** Deterministic host tests of the actual adapter, without contacting any device. */
public class DisplayStartupCompatTest {
    static int assertions;
    static Application.ActivityLifecycleCallbacks callbacks;
    static DisplayManager displays;
    static Display.Mode vga = new Display.Mode(33,640,480,59.94f);
    static Display.Mode stereo = new Display.Mode(32,3840,1080,60);
    static void check(boolean b,String message){assertions++;if(!b)throw new AssertionError(message);}
    static NRFakeActivity window(Display d){NRFakeActivity a=new NRFakeActivity();a.display=d;callbacks.onActivityCreated(a,null);return a;}
    static int mode(Activity a){return a.getWindow().getAttributes().preferredDisplayModeId;}
    static Intent intent(){return new Intent().setComponent(new ComponentName("dev.markpronkin.unbound","ai.nreal.activitylife.NRFakeActivity"));}
    public static void main(String[] args){
        Context.app=new Application();displays=new DisplayManager();Context.displays=displays;Context.manager=new ActivityManager();
        DisplayStartupCompat.install(Context.app);callbacks=Context.app.callbacks.get(0);
        Display xr=new Display(11,true,vga,stereo,vga);
        displays.displays=new Display[]{xr};NRFakeActivity a=window(xr);
        check(mode(a)==32,"VGA fallback must request native stereo");
        xr.current=stereo;displays.changed(11);
        check(mode(a)==32,"Keep request after switch; clearing would revert to VGA");
        callbacks.onActivityPaused(a);check(mode(a)==32,"Visible paused renderer retains request");
        callbacks.onActivityStopped(a);check(mode(a)==0,"Stop restores prior preference");
        xr.current=vga;callbacks.onActivityStarted(a);check(mode(a)==32,"Restart recovers fallback again");
        xr.valid=false;displays.changed(11);check(mode(a)==0,"Retired display releases numeric mode ID");
        xr.valid=true;xr.current=vga;displays.changed(11);check(mode(a)==32,"Reappearance can recover");
        xr.modes=new Display.Mode[]{new Display.Mode(47,3840,1080,60),vga};displays.changed(11);
        check(mode(a)==47,"Re-enumeration re-reads mode IDs");
        a.display=new Display(0,false,vga,stereo,vga);displays.changed(0);
        check(mode(a)==0,"Moving to phone removes external mode request");callbacks.onActivityDestroyed(a);
        Display good=new Display(12,true,stereo,stereo,vga);a=window(good);
        check(mode(a)==0,"Working native mode needs no override");callbacks.onActivityDestroyed(a);
        a=window(new Display(0,true,vga,stereo,vga));check(mode(a)==0,"Never change primary display");callbacks.onActivityDestroyed(a);
        a=window(new Display(13,false,vga,stereo,vga));check(mode(a)==0,"Never change a non-XREAL monitor");callbacks.onActivityDestroyed(a);
        a=window(new Display(14,true,vga,vga));check(mode(a)==0,"Never invent unsupported stereo mode");callbacks.onActivityDestroyed(a);
        a=window(new Display(15,true,vga,new Display.Mode(90,3840,1080,120),vga));
        check(mode(a)==0,"Never request 120 Hz as fallback");callbacks.onActivityDestroyed(a);
        for (Display.Mode fallback : new Display.Mode[]{new Display.Mode(51,1280,720,60),
                new Display.Mode(52,1920,1080,60),new Display.Mode(53,2560,1440,60)}) {
            a=window(new Display(16,true,fallback,stereo,vga));
            check(mode(a)==32,"Recover non-VGA OEM fallback when the glasses advertise only stereo");
            callbacks.onActivityStopped(a);
            check(mode(a)==0,"Restore OEM fallback request when the window stops");callbacks.onActivityDestroyed(a);
        }
        a=window(new Display(16,true,vga,stereo,new Display.Mode(54,1920,1080,60),vga));
        check(mode(a)==0,"Mixed mono/stereo EDID does not prove a stereo switch");callbacks.onActivityDestroyed(a);
        a=new NRFakeActivity();a.display=xr;a.getWindow().getAttributes().preferredDisplayModeId=77;callbacks.onActivityCreated(a,null);
        check(mode(a)==77,"Respect existing SDK preference");callbacks.onActivityDestroyed(a);
        xr.modes=new Display.Mode[]{stereo,vga};a=window(xr);a.getWindow().getAttributes().preferredDisplayModeId=78;displays.changed(11);
        check(mode(a)==78,"Respect subsequent SDK preference");callbacks.onActivityStopped(a);
        check(mode(a)==78,"Do not restore over another owner's preference");callbacks.onActivityDestroyed(a);

        displays.displays=new Display[0];NRXRApp app=new NRXRApp();DisplayStartupCompat.awaitDisplay(app,intent());
        check(displays.listeners.size()==2,"Wait listener registered");Handler.advance(8000);
        check(app.finishes==0,"Five-second chooser delay no longer kills startup");
        xr.state=1;displays.displays=new Display[]{xr};displays.changed(11);check(app.launches==0,"Disabled display cannot launch");
        xr.state=Display.STATE_ON;Context.manager.allowed=false;displays.changed(11);check(app.launches==0,"Respect Android's display access policy");
        Context.manager.allowed=true;app.launchResult=false;displays.changed(11);
        check(app.launches==0,"Do not create native renderer with VGA metrics on Android 17");
        xr.current=stereo;displays.changed(11);
        check(app.launches==1,"Ready stereo output allows the original launch attempt");
        check(!app.mIsActivityStartedOnDPAdded,"Rejected launch cannot set success flag");
        app.launchResult=true;Handler.advance(250);check(app.mIsActivityStartedOnDPAdded,"Retry can succeed after policy change");
        check(displays.listeners.size()==1,"Success unregisters wait listener");
        int count=app.launches;displays.changed(11);Handler.advance(50000);check(app.launches==count,"No duplicate late launch");
        check(app.finishes==0,"Successful startup has no stale timeout");
        check(app.mContext.lastOptions.getInt("android.activity.windowingMode",0)==1,"Renderer requests fullscreen");
        check(app.mContext.lastOptions.getInt("android.activity.launchDisplayId",0)==11,"Preserve target display");

        displays.displays=new Display[0];app=new NRXRApp();DisplayStartupCompat.awaitDisplay(app,intent());Handler.advance(44999);
        check(app.finishes==0,"45-second grace period retained");Handler.advance(1);check(app.finishes==1,"Bounded timeout uses original cleanup");
        check(displays.listeners.size()==1,"Timeout unregisters listener");
        app=new NRXRApp();DisplayStartupCompat.awaitDisplay(app,intent());app.mUnityActivity.destroyed=true;callbacks.onActivityDestroyed(app.mUnityActivity);
        displays.displays=new Display[]{xr};displays.changed(11);Handler.advance(50000);
        check(app.launches==0,"Destroy cancels pending launch");check(displays.listeners.size()==1,"Destroy releases listener");
        app=new NRXRApp();app.mContext.finishing=true;DisplayStartupCompat.awaitDisplay(app,intent());
        check(app.launches==1,"SDK's intentionally finishing entry Activity is allowed");
        check(displays.listeners.size()==1,"Existing display re-query starts without a new added event");
        Bundle b=new Bundle();b.putInt("android.activity.launchDisplayId",11);b.putInt("sentinel",9);
        Activity owner=new Activity();DisplayStartupCompat.startActivity(owner,intent(),b);
        check(b.getInt("android.activity.windowingMode",0)==0,"Do not mutate caller options");
        check(owner.lastOptions.getInt("sentinel",0)==9,"Preserve unrelated launch options");
        Intent other=new Intent().setComponent(new ComponentName("other","ai.nreal.activitylife.NRFakeActivity"));
        DisplayStartupCompat.startActivity(owner,other,b);check(owner.lastOptions==b,"Do not change another package's task");
        b.putInt("android.activity.launchDisplayId",0);DisplayStartupCompat.startActivity(owner,intent(),b);
        check(owner.lastOptions==b,"Do not change phone launch windowing mode");
        DisplayStartupCompat.startActivity(owner,intent(),null);check(owner.lastOptions==null,"Null options remain valid");
        app=new NRXRApp();app.mIsActivityStartedOnDPAdded=true;Context.manager.allowed=false;
        DisplayStartupCompat.startRenderer(app);check(app.launches==0,"Existing display with pending consent must also wait");
        Context.manager.allowed=true;displays.changed(11);check(app.launches==1,"Changed event retries an existing display");
        check(app.mContext.lastIntent.getFlags()==276824064,"Preserve the original renderer task flags");
        System.out.println("PASS "+assertions+" display startup assertions");
    }
}
