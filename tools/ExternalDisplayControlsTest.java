package dev.beampro.bridge;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.WindowManager;
import dev.beampro.port.ArKeepAwake;
import java.lang.reflect.*;

/** Execute the production lifecycle hooks and helper's display guard. No panel power commands. */
public final class ExternalDisplayControlsTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static boolean kept(Activity activity){return activity.getWindow().getDecorView().getKeepScreenOn()
            ||(activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0;}
    static boolean accepted(Method guard,PhoneScreenPower helper,int id)throws Exception {
        try{guard.invoke(helper,id);return true;}
        catch(InvocationTargetException expected){return false;}
    }
    public static void main(String[] args)throws Exception {
        Context.app=new Application();Context.displays=new DisplayManager();
        Display.Mode mono=new Display.Mode(1,1920,1080,60);
        Display phone=new Display(0,false,mono,mono),monitor=new Display(17,false,mono,mono);
        phone.type=1;monitor.product.manufacturer="DEL";
        Display glasses=new Display(17,true,mono,mono);
        Activity controller=new ai.nreal.nebula.space.LaunchSpaceAcrivity();controller.display=phone;
        ArKeepAwake.install(Context.app);
        Application.ActivityLifecycleCallbacks callbacks=Context.app.callbacks.get(0);
        Context.displays.displays=new Display[]{monitor};callbacks.onActivityStarted(controller);
        check(!kept(controller),"An ordinary monitor must not keep the phone awake");
        Context.displays.displays=new Display[]{glasses};Context.displays.changed(17);
        check(kept(controller),"Connected glasses retain AR screen-timeout protection");
        Context.displays.displays=new Display[]{monitor};Context.displays.changed(17);
        check(!kept(controller),"Replacing glasses with a monitor releases AR keep-awake immediately");
        Context.displays.displays=new Display[]{glasses};Context.displays.changed(17);
        glasses.state=1;Context.displays.changed(17);
        check(!kept(controller),"Inactive glasses cannot keep the phone awake");
        glasses.state=Display.STATE_ON;Context.displays.changed(17);callbacks.onActivityStopped(controller);
        check(!kept(controller),"Controller shutdown releases its own keep-awake flag");
        controller.getWindow().getDecorView().setKeepScreenOn(true);
        controller.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        callbacks.onActivityStarted(controller);Context.displays.displays=new Display[0];Context.displays.changed(17);
        callbacks.onActivityStopped(controller);
        check(kept(controller),"Preserve a pre-existing screen-timeout request");

        PhoneScreenPower helper=new PhoneScreenPower(new Context());
        Method guard=PhoneScreenPower.class.getDeclaredMethod("requireGlasses",int.class);guard.setAccessible(true);
        Context.displays.displays=new Display[]{phone,monitor};
        check(!accepted(guard,helper,17),"An ordinary monitor cannot authorize panel blanking");
        check(!accepted(guard,helper,0)&&!accepted(guard,helper,99),"Reject primary and missing displays");
        Context.displays.displays=new Display[]{glasses};
        check(accepted(guard,helper,17),"Accept an active physical XREAL display");
        glasses.type=5;check(!accepted(guard,helper,17),"Reject virtual displays");glasses.type=2;
        glasses.state=1;check(!accepted(guard,helper,17),"Reject inactive glasses");glasses.state=2;
        glasses.product=null;check(!accepted(guard,helper,17),"Unknown display identity fails closed");
        glasses.product=new Display.Product();glasses.product.manufacturer="AUS";glasses.product.id="43593";
        check(accepted(guard,helper,17),"Retain the supported ROG R1 identity");
        glasses.product.id="123";check(!accepted(guard,helper,17),"An ordinary ASUS monitor cannot authorize panel blanking");
        System.out.println("PASS glasses-only screen control assertions: "+checks);
    }
}
