import android.app.*;
import android.content.*;
import android.hardware.display.*;
import android.os.*;
import android.view.*;
import dev.beampro.port.StereoPresentationCompat;
import dev.beampro.port.ArBackdropActivity;

public class StereoPresentationCompatTest {
    static int count;
    static void check(boolean b,String s){count++;if(!b)throw new AssertionError(s);}
    static class DisplayContext extends Context {
        final WindowManager windows=new WindowManager();final DisplayManager displays;
        DisplayContext(DisplayManager displays){this.displays=displays;}
        @Override public <T>T getSystemService(Class<T> cls){return cls.cast(cls==WindowManager.class?windows:displays);}
    }
    static View root(DisplayContext c,Display d,int type){
        View root=new View(c);root.attached=true;root.display=d;
        WindowManager.LayoutParams attrs=new WindowManager.LayoutParams();attrs.type=type;attrs.x=20;attrs.y=10;attrs.flags=2048;
        root.params=attrs;return root;
    }
    static WindowManager.LayoutParams attrs(View root){return (WindowManager.LayoutParams)root.params;}
    static ArBackdropActivity backdrop(Intent intent,Display display)throws Exception{
        ArBackdropActivity activity=new ArBackdropActivity();activity.intent=intent;activity.display=display;
        java.lang.reflect.Method create=ArBackdropActivity.class.getDeclaredMethod("onCreate",Bundle.class);create.setAccessible(true);create.invoke(activity,new Object[]{null});return activity;
    }
    public static void main(String[] args)throws Exception{
        Build.VERSION.SDK_INT=36;
        DisplayManager displays=new DisplayManager();Context.displays=displays;Context.manager=new ActivityManager();
        Display.Mode vga=new Display.Mode(45,640,480,59.94f),stereo=new Display.Mode(44,3840,1080,60);
        Display xr=new Display(15,true,vga,stereo,vga);displays.displays=new Display[]{xr};
        DisplayContext external=new DisplayContext(displays),phone=new DisplayContext(displays);
        View decor=root(external,xr,2037);View frame=new View(phone),surface=new View(phone);
        StereoPresentationCompat.watchView(frame);StereoPresentationCompat.watchView(frame);StereoPresentationCompat.watchView(surface);
        check(frame.listeners.size()==1,"Getter/constructor retries must not double-watch a view");
        frame.attach(decor);surface.attach(decor);Handler.advance(0);
        check(attrs(decor).preferredDisplayModeId==44,"Actual Presentation owns native stereo request");
        check(external.windows.lastView==decor&&phone.windows.updates==0,"Use Presentation context, not phone context retained by children");
        check(attrs(decor).width==-1&&attrs(decor).height==-1,"Window fills display");
        check(attrs(decor).x==0&&attrs(decor).y==0&&attrs(decor).gravity==(Gravity.TOP|Gravity.LEFT),"Stereo origin remains at display origin");
        check(attrs(decor).getFitInsetsTypes()==0,"No status/navigation/caption inset in stereo output");
        check((attrs(decor).flags&1024)!=0&&(attrs(decor).flags&2048)==0,"Fullscreen flags applied");
        check(decor.insets.hidden==WindowInsets.Type.systemBars(),"Hide bars on this Presentation");
        check(frame.params.width==-1&&frame.params.height==-1&&surface.params.width==-1&&surface.params.height==-1,"SDK children fill their window");
        check(displays.listeners.size()==1,"Two SDK views share one display listener");
        int updates=external.windows.updates;decor.observer.layout();Handler.advance(0);
        check(external.windows.updates==updates,"Layout callback is idempotent");
        // The placeholder Activity stops, but the SDK Presentation and its views remain attached.
        Handler.advance(45000);check(attrs(decor).preferredDisplayModeId==44,"Request lives with visible SDK surface");
        xr.current=stereo;displays.changed(15);Handler.advance(0);
        check(attrs(decor).preferredDisplayModeId==44,"Do not revert to VGA after the mode changes");
        xr.modes=new Display.Mode[]{new Display.Mode(51,3840,1080,60),vga};displays.changed(15);Handler.advance(0);
        check(attrs(decor).preferredDisplayModeId==51,"Refresh mode ID on re-enumeration");
        frame.detach();check(attrs(decor).preferredDisplayModeId==51,"One child detaching must not release another's window");
        surface.detach();check(attrs(decor).preferredDisplayModeId==0,"Last detach releases owned mode request");
        check(displays.listeners.isEmpty()&&decor.observer.listeners.isEmpty(),"Detach removes listeners");
        Handler.advance(10000);check(attrs(decor).preferredDisplayModeId==0,"No stale callback restores the request");
        surface.attach(decor);Handler.advance(0);check(attrs(decor).preferredDisplayModeId==51,"Reattaching recovers native Presentation");
        attrs(decor).preferredDisplayModeId=88;displays.changed(15);Handler.advance(0);
        check(attrs(decor).preferredDisplayModeId==88,"Respect a subsequent explicit SDK mode");surface.detach();
        check(attrs(decor).preferredDisplayModeId==88,"Do not restore over a later owner");
        View primary=root(phone,new Display(0,false,vga,stereo,vga),2037),view=new View(phone);
        StereoPresentationCompat.watchView(view);view.attach(primary);Handler.advance(0);
        check(attrs(primary).preferredDisplayModeId==0&&phone.windows.updates==0,"Phone view/window remains untouched");view.detach();
        View other=root(external,new Display(7,false,vga,stereo,vga),2037);view=new View(phone);
        StereoPresentationCompat.watchView(view);view.attach(other);Handler.advance(0);
        check(attrs(other).preferredDisplayModeId==0,"Never modify another monitor");view.detach();
        View mono=root(external,new Display(16,true,vga,vga),2037);view=new View(phone);
        StereoPresentationCompat.watchView(view);view.attach(mono);Handler.advance(0);
        check(attrs(mono).preferredDisplayModeId==0&&attrs(mono).width==850,"Never invent a stereo mode");view.detach();
        View overlay=root(external,xr,2038);view=new View(phone);
        StereoPresentationCompat.watchView(view);view.attach(overlay);Handler.advance(0);
        check(attrs(overlay).preferredDisplayModeId==0&&attrs(overlay).width==850,"Only Presentation windows receive the override");view.detach();
        Activity owner=new Activity();
        View nativeRoot=root(external,xr,2037);
        attrs(nativeRoot).flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        android.view.inspector.WindowInspector.windows.add(primary);
        android.view.inspector.WindowInspector.windows.add(overlay);
        StereoPresentationCompat.track(owner);Handler.advance(500);
        check(attrs(primary).width==850&&attrs(overlay).width==850,"Global discovery skips phone and non-Presentation roots");
        android.view.inspector.WindowInspector.windows.add(nativeRoot);Handler.advance(500);
        check(attrs(nativeRoot).preferredDisplayModeId==51,"Discover native-created Presentation with no SDK child attachment");
        check((attrs(nativeRoot).flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0
                &&owner.lastOptions.getInt("android.activity.launchDisplayId",0)==15,"Immersive backdrop owns bars without stealing native/app focus");
        check((attrs(nativeRoot).flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0,"Preserve phone trackpad touch routing");
        check(nativeRoot.insets.hidden==WindowInsets.Type.systemBars(),"Native Presentation requests immersive bars");
        ArBackdropActivity background=backdrop(owner.lastIntent,xr);
        check(!background.isFinishing()&&background.window.format==-1,"Valid AR lease creates an opaque backdrop");
        check((background.window.getAttributes().flags&24)==24,"Backdrop is neither focusable nor touchable");
        check(background.window.getDecorView().insets.hidden==WindowInsets.Type.systemBars(),"Opaque application requests immersive bars while app focus can remain elsewhere");
        check(owner.lastOptions.getInt("android.activity.windowingMode",0)==1,"Backdrop is a fullscreen task on the glasses");
        ArBackdropActivity stale=backdrop(new Intent(),xr);check(stale.isFinishing(),"Reject stale or externally reconstructed backdrop intents");
        int listeners=displays.listeners.size();StereoPresentationCompat.track(owner);Handler.advance(1000);
        check(displays.listeners.size()==listeners,"Tracking retries do not duplicate root sessions");
        nativeRoot.detach();android.view.inspector.WindowInspector.windows.remove(nativeRoot);
        check(attrs(nativeRoot).preferredDisplayModeId==0,"Native root detach releases its mode request");
        owner.destroyed=true;Handler.advance(500);int scans=android.view.inspector.WindowInspector.scans;Handler.advance(5000);
        check(android.view.inspector.WindowInspector.scans==scans&&background.isFinishing(),"Stop scanning and backdrop when AR owner is destroyed");
        check(backdrop(owner.lastIntent,xr).isFinishing(),"Restored activity cannot leave a black display after its AR owner ends");
        Build.VERSION.SDK_INT=37;
        Activity modernOwner=new Activity();View modernRoot=root(external,xr,2037);
        attrs(modernRoot).flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        android.view.inspector.WindowInspector.windows.add(modernRoot);
        StereoPresentationCompat.track(modernOwner);Handler.advance(500);
        check((attrs(modernRoot).flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)==0,"Android 17 Presentation owns its display's bar focus");
        check((attrs(modernRoot).flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0,"Renderer cannot intercept phone-controller touches");
        check((attrs(modernRoot).flags&WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)!=0,"Renderer is excluded from app IME targeting");
        check(modernOwner.lastIntent==null,"Do not enqueue an Activity that PresentationController prevents from running");
        int modernUpdates=external.windows.updates;Handler.advance(2000);
        check(external.windows.updates==modernUpdates,"Healthy modern Presentation is not repeatedly relaid out");
        attrs(modernRoot).flags|=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        attrs(modernRoot).flags&=~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;Handler.advance(500);
        check((attrs(modernRoot).flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)==0
                &&(attrs(modernRoot).flags&WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)!=0,"Recover a native flag rewrite without needing a display/layout event");
        check(attrs(primary).width==850&&attrs(overlay).width==850,"Modern focus policy still skips phone and unrelated overlay windows");
        modernRoot.detach();android.view.inspector.WindowInspector.windows.remove(modernRoot);modernOwner.destroyed=true;Handler.advance(500);
        System.out.println("PASS "+count+" presentation assertions");
    }
}
