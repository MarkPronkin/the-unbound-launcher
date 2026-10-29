package dev.beampro.port;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Phone-owned AR lifecycle for the SDK's native glasses Presentation (no DeX/Desktop activity). */
public final class PresentationOnlyCompat implements Application.ActivityLifecycleCallbacks {
    private static final String TAG="UnboundPresentation";
    private static final String OWNER="ai.nreal.nebula.space.LaunchSpaceAcrivity";
    private static final String EXTRA="dev.beampro.port.PRESENTATION_SESSION";
    private static final String FAILED="AR could not start on the glasses. Reconnect them to try again.";
    private static final String DEX="Turn off Wired DeX or select Mirrored under Connected display, then reconnect your glasses.";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ThreadLocal<Object> BYPASS=new ThreadLocal<>();
    private static Waiter pending;
    private static Session active;
    private static boolean installed;
    private static long sequence;

    public static synchronized void install(Context context) {
        if(installed)return;
        installed=true;
        ((Application)context.getApplicationContext()).registerActivityLifecycleCallbacks(new PresentationOnlyCompat());
    }
    private static Field field(Object target,String name)throws Exception {
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try {
            Field value=type.getDeclaredField(name);value.setAccessible(true);return value;
        } catch(NoSuchFieldException absent){}
        throw new NoSuchFieldException(name);
    }
    private static Object get(Object target,String name)throws Exception {return field(target,name).get(target);}
    private static Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception {
        Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,args);
    }
    public static boolean isActive(Activity owner) {return active!=null && active.owner==owner && !active.closed;}
    static boolean directDisplay(Display display) {
        return active!=null && !active.closed && display!=null && active.displayId==display.getDisplayId();
    }
    static boolean dex(Display display) {return display.toString().contains("FLAG_EXTERNAL_DEX_HOSTING");}
    private static boolean ready(Display display) {
        if(display==null||!DisplayStartupCompat.xreal(display)||display.getState()!=Display.STATE_ON
                ||(display.getFlags()&Display.FLAG_PRESENTATION)==0||dex(display))return false;
        Display.Mode mode=display.getMode();
        return PresentationReadiness.nativeOutput(mode.getPhysicalWidth(),mode.getPhysicalHeight(),mode.getRefreshRate());
    }
    private static String describe(Display display) {
        Display.Mode mode=display.getMode();
        return mode.getPhysicalWidth()+"x"+mode.getPhysicalHeight()+"@"+mode.getRefreshRate();
    }
    /** Own every AR launch: wait for native glasses output, then start Unity on the phone.
     *  Returns false only for the re-entrant launch issued by this class. */
    public static boolean startUnity(Object app,Intent intent) {
        if(BYPASS.get()==app)return false;
        Activity context=null;
        try {
            context=(Activity)get(app,"mContext");
            if(active!=null && active.app==app && active.owner!=null && !active.closed)return false;
            if(pending!=null) {
                if(pending.app==app)return true;
                pending.close();
            }
            pending=new Waiter(app,context,new Intent(intent));
            pending.start();
        } catch(Exception failure) {
            Log.e(TAG,"Cannot prepare direct glasses output",failure);
            if(pending!=null)pending.close();
            if(context!=null) {Toast.makeText(context,FAILED,Toast.LENGTH_LONG).show();context.finish();}
        }
        return true;
    }
    /** Defensive guard for a stale SDK callback; no external activity belongs to this path. */
    public static boolean owns(Object app) {return (pending!=null&&pending.app==app)||(active!=null&&active.app==app&&!active.closed);}
    public static Activity renderingActivity() {return active!=null&&!active.closed?active.owner:null;}
    static long sessionToken() {return active==null||active.closed?0:active.token;}
    static void foreground(long token,boolean ready) {
        if(active!=null&&!active.closed&&active.token==token)active.foreground=ready;
    }
    static void stopSession(long token) {
        if(active!=null&&!active.closed&&active.token==token)active.finish("AR session ended.");
    }
    private static Session sessionFor(Object unity) {
        Session session=active;
        if(session!=null&&!session.closed&&session.owner!=null)try {
            if(get(session.owner,"mUnityPlayer")==unity)return session;
        } catch(Exception failure){Log.w(TAG,"Cannot match Unity to the direct session",failure);}
        return null;
    }
    /** The phone controller may stop while its separate glasses Presentation keeps rendering.
     *  Match by player because the SDK reuses the activity's register for mUnityPlayer. */
    public static void pause(Object unity) {
        Session session=sessionFor(unity);
        if(session!=null&&session.keepRendering()) {
            Log.i(TAG,"Phone controller hidden; keeping glasses renderer active");return;
        }
        try {unity.getClass().getMethod("pause").invoke(unity);}
        catch(Exception failure){throw new IllegalStateException("Cannot forward Unity pause",failure);}
    }
    public static void windowFocus(Object unity,boolean focused) {
        Session session=sessionFor(unity);boolean renderFocus=focused;
        if(!focused&&session!=null&&session.keepRendering())renderFocus=true;
        try {unity.getClass().getMethod("windowFocusChanged",boolean.class).invoke(unity,renderFocus);}
        catch(Exception failure){Log.e(TAG,"Cannot forward Unity window focus",failure);}
    }
    public static boolean prepareSwitch(Object app) {
        if(active==null||active.app!=app||active.closed)return false;
        Session session=active;
        session.switchUntil=SystemClock.uptimeMillis()+10000;session.switchFrom=session.displayId;
        Display display=session.displays==null?null:session.displays.getDisplay(session.displayId);
        session.switchMode=display==null?null:describe(display);
        Log.i(TAG,"Preparing direct-display mode transition from "+session.switchMode);
        return true;
    }
    public static int dynamicReady(Object app) {
        return active==null||active.app!=app||active.closed?-1:active.owner!=null&&!active.owner.isFinishing()?1:0;
    }
    /** Like the SDK's placeholder check: not ready until the glasses re-enumerate or change mode. */
    public static int restartReady(Object app) {
        if(active==null||active.app!=app||active.closed)return -1;
        Session session=active;
        Display display=session.displays==null?null:session.displays.getDisplay(session.displayId);
        if(!ready(display))return 0;
        boolean pending=SystemClock.uptimeMillis()<session.switchUntil;
        if(pending&&session.displayId==session.switchFrom&&describe(display).equals(session.switchMode))return 0;
        if(session.switchUntil!=0)Log.i(TAG,"Direct output ready after mode transition: "+describe(display));
        session.switchUntil=0;return 1;
    }

    /** Silent wait for native output; the transparent entry activity keeps the connection screen visible. */
    private static final class Waiter implements Runnable,DisplayManager.DisplayListener {
        final Object app;final Activity context;final Intent intent;final DisplayManager displays;
        final long deadline=SystemClock.uptimeMillis()+45000;
        boolean closed;long stableSince,dexSince;int lastDisplay=-1;String lastStatus;
        Waiter(Object app,Activity context,Intent intent) {
            this.app=app;this.context=context;this.intent=intent;displays=context.getSystemService(DisplayManager.class);
        }
        void start(){displays.registerDisplayListener(this,MAIN);run();}
        void status(String value){if(!value.equals(lastStatus)){lastStatus=value;Log.i(TAG,value);}}
        void close() {
            if(closed)return;closed=true;
            displays.unregisterDisplayListener(this);MAIN.removeCallbacks(this);if(pending==this)pending=null;
        }
        void fail(String message) {
            Log.w(TAG,message);close();
            if(context.isFinishing()||context.isDestroyed())return;
            AlertDialog dialog=new AlertDialog.Builder(context,android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("AR could not start").setMessage(message)
                    .setPositiveButton("Close",(d,w)->context.finish()).create();
            dialog.setOnCancelListener(d->context.finish());dialog.show();
        }
        public void run() {
            if(closed)return;MAIN.removeCallbacks(this);
            if(context.isDestroyed()||context.isFinishing()){close();return;}
            long now=SystemClock.uptimeMillis();
            try {
                Display selected=null;boolean hasDex=false;String seen=null;
                for(Display display:displays.getDisplays()) {
                    if(!DisplayStartupCompat.xreal(display))continue;
                    if(dex(display))hasDex=true;
                    if(ready(display)) {selected=display;break;}
                    seen=describe(display)+(display.getState()==Display.STATE_ON?"":" off");
                }
                dexSince=hasDex?(dexSince==0?now:dexSince):0;
                if(selected!=null) {
                    if(lastDisplay!=selected.getDisplayId()){lastDisplay=selected.getDisplayId();stableSince=now;}
                    if(now-stableSince>=1000) {
                        Session next=new Session(app,selected.getDisplayId(),(Boolean)get(app,"mMultiResumeMode"));
                        active=next;
                        intent.putExtra(EXTRA,next.token);
                        field(app,"mMultiResumeMode").setBoolean(app,false);
                        field(app,"mDisplayId").setInt(app,selected.getDisplayId());
                        close();
                        BYPASS.set(app);
                        try {call(app,"startUnityActivityInternal",new Class<?>[]{Intent.class},intent);}
                        finally {BYPASS.remove();}
                        Log.i(TAG,"Starting phone-owned Unity lifecycle; physical display="+selected.getDisplayId()+" "+describe(selected)+"; no external activity");
                        return;
                    }
                    status("Waiting for glasses output to settle");
                } else {
                    lastDisplay=-1;stableSince=0;
                    status("Waiting for native glasses output"+(seen==null?"":" (now "+seen+")")+(hasDex?" with DeX active":""));
                }
                if(dexSince!=0&&now-dexSince>=5000){fail(DEX);return;}
                if(now>=deadline) {
                    fail("The glasses didn't reach full resolution. Make sure On-device ADB is running and DeX/Desktop is off, then reconnect your glasses.");return;
                }
            } catch(Exception failure) {
                if(active!=null&&active.app==app&&active.owner==null)active.close();
                Log.e(TAG,"Direct display startup failed",failure);
                if(closed){Toast.makeText(context,FAILED,Toast.LENGTH_LONG).show();context.finish();}
                else fail(FAILED);
                return;
            }
            MAIN.postDelayed(this,250);
        }
        public void onDisplayAdded(int id){run();}
        public void onDisplayChanged(int id){if(id==lastDisplay)stableSince=SystemClock.uptimeMillis();run();}
        public void onDisplayRemoved(int id){if(lastDisplay==id){lastDisplay=-1;stableSince=0;}run();}
    }

    private static final class Session implements Runnable,DisplayManager.DisplayListener {
        final Object app;final long token=++sequence;final boolean originalMultiResume;
        Activity owner;DisplayManager displays;int displayId,switchFrom=-1;boolean closed,seenPresentation,foreground;
        long deadline=SystemClock.uptimeMillis()+45000,missingSince,switchUntil;String switchMode;
        Session(Object app,int displayId,boolean originalMultiResume){this.app=app;this.displayId=displayId;this.originalMultiResume=originalMultiResume;}
        void attach(Activity activity) {
            if(owner==activity)return;owner=activity;displays=activity.getSystemService(DisplayManager.class);
            displays.registerDisplayListener(this,MAIN);StereoPresentationCompat.track(owner);run();
        }
        boolean locked() {
            KeyguardManager guard=owner.getSystemService(KeyguardManager.class);
            return guard!=null&&guard.isKeyguardLocked();
        }
        boolean keepRendering() {
            if(closed||owner==null||owner.isFinishing()||owner.isDestroyed()||locked())return false;
            Display display=displays==null?null:displays.getDisplay(displayId);
            return display!=null&&display.getState()==Display.STATE_ON&&DisplayStartupCompat.xreal(display)
                    &&(foreground||owner.getWindow().getDecorView().isShown());
        }
        void close() {
            if(closed)return;closed=true;MAIN.removeCallbacks(this);
            if(displays!=null)displays.unregisterDisplayListener(this);
            if(owner!=null)owner.stopService(new Intent(owner,ArSessionService.class));
            try {field(app,"mMultiResumeMode").setBoolean(app,originalMultiResume);}
            catch(Exception failure){Log.w(TAG,"Cannot restore SDK lifecycle selection",failure);}
            if(active==this)active=null;
        }
        void finish(String reason) {
            Log.w(TAG,reason);Activity activity=owner;close();
            if(activity!=null&&!activity.isDestroyed()){Toast.makeText(activity,reason,Toast.LENGTH_LONG).show();activity.finish();}
        }
        void notifyDisplay(String name,int id,int width,int height) {
            try {
                Object listener=get(app,"mXRDisplayListener");if(listener==null)return;
                if(name.equals("onXRDisplayRemoved"))call(listener,name,new Class<?>[]{int.class},id);
                else call(listener,name,new Class<?>[]{int.class,int.class,int.class},id,width,height);
            } catch(Exception failure) {Log.w(TAG,"Cannot notify SDK of a display transition",failure);}
        }
        public void run() {
            if(closed)return;MAIN.removeCallbacks(this);
            if(owner==null||owner.isDestroyed()||owner.isFinishing()){close();return;}
            if(locked()){finish("Phone locked. Unlock it and reopen AR Space to continue.");return;}
            Display display=displays.getDisplay(displayId);
            if(display==null||!DisplayStartupCompat.xreal(display)) {
                if(missingSince==0)missingSince=SystemClock.uptimeMillis();
                if(SystemClock.uptimeMillis()>Math.max(missingSince+3000,switchUntil)) {
                    finish("Glasses disconnected. Reconnect them to start a new AR session.");return;
                }
            } else {
                missingSince=0;
                if(dex(display)){finish(DEX);return;}
                if(!seenPresentation)for(View root:WindowInspector.getGlobalWindowViews()) {
                    if(!root.isAttachedToWindow()||root.getDisplay()==null||root.getDisplay().getDisplayId()!=displayId
                            ||!(root.getLayoutParams() instanceof WindowManager.LayoutParams))continue;
                    int type=((WindowManager.LayoutParams)root.getLayoutParams()).type;
                    if(type==2037||type==WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION) {
                        seenPresentation=true;Log.i(TAG,"Native Presentation attached on display="+displayId+"; phone owns the session");break;
                    }
                }
                if(!seenPresentation&&SystemClock.uptimeMillis()>deadline){finish(FAILED);return;}
            }
            MAIN.postDelayed(this,500);
        }
        public void onDisplayAdded(int id) {
            Display display=displays.getDisplay(id);
            if(!DisplayStartupCompat.xreal(display)||dex(display))return;
            if(displayId!=id) {
                displayId=id;seenPresentation=false;deadline=SystemClock.uptimeMillis()+45000;missingSince=0;
                try {field(app,"mDisplayId").setInt(app,id);}catch(Exception failure){Log.w(TAG,"Cannot update direct display ID",failure);}
                notifyDisplay("onXRDisplayAdded",id,display.getMode().getPhysicalWidth(),display.getMode().getPhysicalHeight());
            }
            run();
        }
        public void onDisplayChanged(int id){run();}
        public void onDisplayRemoved(int id){if(id==displayId)notifyDisplay("onXRDisplayRemoved",id,0,0);run();}
    }

    public void onActivityCreated(Activity activity,Bundle saved) {
        if(!activity.getClass().getName().equals(OWNER))return;
        long token=activity.getIntent().getLongExtra(EXTRA,0);
        if(token==0)return;
        if(active==null||active.closed||active.token!=token){activity.finish();return;}
        active.attach(activity);
    }
    /** Keep the SDK's dialog handling; scoped pause hooks also cover a fully hidden controller. */
    public void onActivityPostCreated(Activity activity,Bundle saved) {
        if(!isActive(activity))return;
        try {field(activity,"fakePause").setBoolean(activity,true);}
        catch(Exception failure){Log.w(TAG,"Cannot keep rendering through phone dialogs",failure);}
    }
    public void onActivityStarted(Activity activity) {
        if(!isActive(activity))return;
        try {activity.startForegroundService(new Intent(activity,ArSessionService.class)
                .putExtra(ArSessionService.TOKEN,active.token));}
        catch(RuntimeException failure){Log.w(TAG,"Cannot keep the renderer process active in the background",failure);}
        StereoPresentationCompat.track(activity);
    }
    public void onActivityResumed(Activity activity){}
    public void onActivityPaused(Activity activity){}
    public void onActivityStopped(Activity activity) {
        if(!isActive(activity))return;
        try {Object unity=get(activity,"mUnityPlayer");if(unity!=null)windowFocus(unity,false);}
        catch(Exception failure){Log.w(TAG,"Cannot reconcile Unity focus",failure);}
    }
    public void onActivitySaveInstanceState(Activity activity,Bundle state){}
    public void onActivityDestroyed(Activity activity) {
        if(pending!=null&&pending.context==activity)pending.close();
        if(isActive(activity))active.close();
    }
}
