package dev.beampro.port;

import android.app.Activity;
import android.content.Context;
import android.net.Network;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import java.lang.reflect.*;

/** Actual Session methods with deterministic Android service, clock and window boundaries. */
public final class PresentationNetworkTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static class App {
        public boolean mMultiResumeMode=true;public int mDisplayId;public Listener mXRDisplayListener;
    }
    public static class Listener {
        final android.net.ConnectivityManager cm;int added;Network selected;
        Listener(android.net.ConnectivityManager cm){this.cm=cm;}
        public void onXRDisplayAdded(int id,int w,int h){added++;selected=cm.bound;}
        public void onXRDisplayRemoved(int id){}
    }
    static final Class<?> SESSION;
    static {try{SESSION=Class.forName("dev.beampro.port.PresentationOnlyCompat$Session");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static Object get(Object o,String name)throws Exception {Field f=SESSION.getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception {Field f=SESSION.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static void call(Object o,String name,Object... args)throws Exception {
        for(Method m:SESSION.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){
            m.setAccessible(true);m.invoke(o,args);return;}
        throw new NoSuchMethodException(name);
    }
    static class Fixture implements AutoCloseable {
        final GlassesNetworkTest.Setup setup=new GlassesNetworkTest.Setup();
        final App app=new App();final Activity owner=new Activity();final Object session;
        Display display;
        Fixture()throws Exception {
            Handler.reset();WindowInspector.roots.clear();app.mXRDisplayListener=new Listener(setup.cm);
            display(11,true);owner.displays=setup.context.displays;
            Constructor<?> ctor=SESSION.getDeclaredConstructor(Object.class,Context.class,int.class,boolean.class);ctor.setAccessible(true);
            session=ctor.newInstance(app,setup.context,display.getDisplayId(),true);
            call(session,"selectNetwork");call(session,"attach",owner);
        }
        void display(int id,boolean xreal) {
            display=new Display(id,xreal,new Display.Mode(1,3840,1080,60));
            setup.context.displays.displays=new Display[]{display};
        }
        void show()throws Exception {
            WindowInspector.roots.add(new View(display,new WindowManager.LayoutParams(2037)));call(session,"run");
        }
        void added(int id)throws Exception {WindowInspector.roots.clear();display(id,true);call(session,"onDisplayAdded",id);}
        public void close()throws Exception {call(session,"close");}
    }
    public static void main(String[] args)throws Exception {
        try(Fixture f=new Fixture()) {
            check(f.setup.glasses.equals(f.setup.cm.bound),"Renderer binds before Unity startup");
            f.show();check(f.setup.glasses.equals(f.setup.cm.bound),"Presentation attachment retains the startup lease");
            Handler.advance(999);check(f.setup.glasses.equals(f.setup.cm.bound),"The grace interval lasts a full second");
            Handler.advance(1);check(f.setup.cm.bound==null&&get(f.session,"network")==null,"Routing restores one second after Presentation attachment");
            check(Boolean.TRUE.equals(get(f.session,"seenPresentation")),"Releasing routing preserves the live AR session");
        }
        for(boolean replaceNetwork:new boolean[]{false,true})try(Fixture f=new Fixture()) {
            f.show();Runnable oldRelease=(Runnable)get(f.session,"networkRelease");Handler.advance(500);
            if(replaceNetwork) {
                f.setup.cm.links.remove(f.setup.glasses);f.setup.glasses=new Network(200);
                f.setup.add(f.setup.glasses,"eth0","169.254.2.10/24","169.254.2.0/24");
            }
            f.added(12);Object replacement=get(f.session,"network");
            check(f.setup.glasses.equals(f.setup.cm.bound),"Display switch selects the live control network");
            check(f.app.mXRDisplayListener.added==1&&f.setup.glasses.equals(f.app.mXRDisplayListener.selected),
                    "Network selection precedes the SDK display-added callback");
            oldRelease.run();check(get(f.session,"network")==replacement&&f.setup.glasses.equals(f.setup.cm.bound),
                    "A stale timer cannot release the replacement lease, even on the same Android network");
            Handler.advance(500);check(f.setup.glasses.equals(f.setup.cm.bound),"The superseded deadline is cancelled");
            f.show();Handler.advance(1000);check(f.setup.cm.bound==null,"The new Presentation gets its own grace interval");
        }
        try(Fixture f=new Fixture()) {
            f.show();Handler.advance(1000);f.setup.cm.links.remove(f.setup.glasses);
            long before=SystemClock.now;f.added(12);
            check(SystemClock.now==before&&f.app.mXRDisplayListener.added==0,"Unpublished network waits without blocking UI or notifying the SDK early");
            f.setup.glasses=new Network(201);f.setup.add(f.setup.glasses,"eth0","169.254.2.10/24","169.254.2.0/24");
            Handler.advance(250);
            check(f.app.mXRDisplayListener.added==1&&f.setup.glasses.equals(f.app.mXRDisplayListener.selected),
                    "A later network publication resumes the display switch on Ethernet");
            int binds=f.setup.cm.bindCalls;call(f.session,"onDisplayAdded",12);
            check(f.setup.cm.bindCalls==binds&&f.app.mXRDisplayListener.added==1,"Duplicate display events do not reacquire a lease or repeat the SDK callback");
        }
        try(Fixture f=new Fixture()) {
            Object lease=get(f.session,"network");int binds=f.setup.cm.bindCalls;
            Display monitor=new Display(99,false,new Display.Mode(2,1920,1080,60));
            f.setup.context.displays.displays=new Display[]{f.display,monitor};call(f.session,"onDisplayAdded",99);
            check(get(f.session,"network")==lease&&f.setup.cm.bindCalls==binds,"An unrelated monitor cannot change glasses routing");
            f.show();Runnable release=(Runnable)get(f.session,"networkRelease");f.close();release.run();
            check(f.setup.cm.bound==null&&ownerClosed(f),"Session close immediately releases routing and cancels delayed cleanup");
        }
        try(Fixture f=new Fixture()) {
            f.show();Handler.advance(1000);f.setup.cm.links.remove(f.setup.glasses);f.added(12);
            set(f.session,"deadline",SystemClock.now+500);Handler.advance(750);
            check(f.owner.finishing&&Boolean.TRUE.equals(get(f.session,"closed")),"Network publication timeout closes the renderer session");
            check(f.setup.cm.bound==null&&f.app.mXRDisplayListener.added==0&&f.owner.stops>0,"A failed switch releases routing and its service without an SDK callback");
        }
        System.out.println("PASS renderer network lifecycle assertions: "+checks);
    }
    static boolean ownerClosed(Fixture f)throws Exception {return Boolean.TRUE.equals(get(f.session,"closed"))&&f.owner.stops>0;}
}
