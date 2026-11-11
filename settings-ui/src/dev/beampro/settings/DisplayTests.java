package dev.beampro.settings;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Phone-owned guided tests. Native control, file capture and UI polling have separate workers. */
public final class DisplayTests {
    private static DisplayTests instance;
    private static long sequence;
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService control=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"unbound-display-test-control");t.setDaemon(true);return t;});
    private final ExecutorService records=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"unbound-display-test-records");t.setDaemon(true);return t;});
    private final ThreadPoolExecutor captures=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(3),
            r->{Thread t=new Thread(r,"unbound-display-test-captures");t.setDaemon(true);return t;});
    private final DisplayTestJournal journal;
    private final ArrayList<Runnable> observers=new ArrayList<>();
    private WeakReference<Activity> owner=new WeakReference<>(null);
    private volatile boolean visible,held,nativePending;
    private volatile int capturesPending;
    private volatile Future<?> journalReady;
    private Object ticket;
    private DisplayTestRun run;
    private DisplayTestPattern pattern;
    private String id="",status="Ready. Connect the glasses, then choose a test.",feedback="",lastInventory="",viewState="";
    private boolean restoreOnReserve,feedbackAvailable,checking;
    private int restoreRequest;
    private DisplayTestScreens screen;

    private DisplayTests(Context context){this.context=context;journal=new DisplayTestJournal(new File(context.getFilesDir(),"debug"));screen=DisplayTestScreens.read(context);}
    static synchronized DisplayTests get(Context context){if(instance==null)instance=new DisplayTests(context.getApplicationContext());return instance;}
    /** Called by the connection adapter and automatic USB entry before launching AR. */
    public static synchronized boolean active(){return instance!=null&&(instance.held||instance.nativePending);}
    static void flush() {
        DisplayTests tests=instance;if(tests==null)return;
        try{tests.captures.submit(()->{}).get(32,TimeUnit.SECONDS);tests.records.submit(()->{}).get(2,TimeUnit.SECONDS);}
        catch(Exception pending){tests.note("Some display-test evidence was still finishing at export.");}
    }
    void enter(Activity activity,Runnable observer){
        owner=new WeakReference<>(activity);visible=held=true;observers.add(observer);main.removeCallbacks(poll);main.post(poll);
        control.execute(()->{try{api().getMethod("cancelAutomaticEntry",Context.class).invoke(null,context);}catch(Exception absent){android.util.Log.w("UnboundDisplayTest","Cannot cancel a queued automatic entry",absent);}});
    }
    void leave(Runnable observer){observers.remove(observer);visible=false;Activity activity=owner.get();if(activity==null||activity.isFinishing())held=false;dismissPattern();if(run!=null&&run.busy())run.stop(SystemClock.uptimeMillis());main.post(poll);}
    void release(Activity activity){if(owner.get()==activity){held=false;visible=false;owner.clear();}}
    boolean busy(){return checking||nativePending||(run!=null&&run.busy());}
    boolean saving(){return capturesPending!=0;}
    boolean canFeedback(){return feedbackAvailable;}
    boolean nativeWork(){return nativePending||(run!=null&&run.busy());}
    boolean recovering(){return run!=null&&(run.phase==DisplayTestRun.Phase.RESTORING||(run.phase==DisplayTestRun.Phase.DONE&&nativePending));}
    String status(){return status+(feedback.isEmpty()?"":"\n"+feedback);}
    String output(){return screen==null?"Reading display…":screen.text;}
    String countdown(){return run!=null&&run.busy()?((run.phase==DisplayTestRun.Phase.PICTURE?"Returning to 2D in ":"This step has ")+run.remaining(SystemClock.uptimeMillis())+" seconds remaining"):"";}

    void check() {
        if(busy()||saving())return;
        begin("Connection check");checking=true;status="Capturing the connected display and desktop state…";changed();
        capture("before",()->{checking=false;status="Connection check saved. "+output();changed();});
    }
    void start(DisplayTestRun.Kind kind) {
        if(busy()||saving())return;
        begin(kind==DisplayTestRun.Kind.SBS?"3D SBS test":kind==DisplayTestRun.Kind.TWO_D?"2D test":"Return to 2D");
        status="Checking glasses access and firmware…";nativePending=true;ticket=null;restoreOnReserve=false;
        run=new DisplayTestRun(kind,new DisplayTestRun.Driver(){
            public void mode(boolean stereo,int request){
                note("REQUEST "+(stereo?"SBS 3840x1080@60":"2D 1920x1080@60"));
                invoke("mode",new Class<?>[]{Object.class,boolean.class,Consumer.class},ticket,stereo,callback(request,false));
            }
            public void restore(int request){
                dismissPattern();restoreRequest=request;restoreOnReserve=true;note("RECOVERY requested: 2D mode 1");
                if(ticket!=null)finishTicket();
            }
            public void capture(String stage){DisplayTests.this.capture(stage,()->{
                if(stage.equals("before")&&run!=null)run.capturedBefore(SystemClock.uptimeMillis());changed();
            });}
            public void pattern(boolean stereo){showPattern(stereo);}
            public void status(String text){status=text;note(text);changed();}
            public void complete(String text,boolean restored){status=text;dismissPattern();note("COMPLETE restored="+restored+"; "+text);changed();}
        },SystemClock.uptimeMillis());
        String expected=id;
        control.execute(()->{
            try {
                Object reserved=api().getMethod("reserve",Context.class).invoke(null,context);
                main.post(()->{
                    if(!expected.equals(id))return;
                    ticket=reserved;
                    if(restoreOnReserve){finishTicket();return;}
                    invoke("start",new Class<?>[]{Object.class,Consumer.class},ticket,(Consumer<Bundle>)answer->main.post(()->{
                        if(!expected.equals(id))return;
                        note("CONTROL "+answer.getString("message"));
                        run.ready(answer.getBoolean("ok"),answer.getString("message"),SystemClock.uptimeMillis());changed();
                    }));
                });
            } catch(Exception|LinkageError failure){main.post(()->{
                if(!expected.equals(id))return;
                nativePending=false;String message=error(failure);note("CONTROL ERROR "+message);
                // Reservation failed before this test owned any control state. Keep an existing AR session intact.
                run=null;ticket=null;restoreOnReserve=false;status=message;note("COMPLETE blocked before control initialization; "+message);
                changed();
            });}
        });
        changed();main.removeCallbacks(poll);main.post(poll);
    }
    void stop(){if(run!=null&&run.busy())run.stop(SystemClock.uptimeMillis());else if(!nativePending)start(DisplayTestRun.Kind.RESTORE);}
    void feedback(boolean clear){if(!feedbackAvailable)return;note("WEARER_FEEDBACK "+(clear?"Pattern looked clear":"Pattern was broken or missing"));feedback="Picture feedback saved: "+(clear?"clear":"broken or missing");changed();}

    private void begin(String label){
        id=Long.toHexString(System.currentTimeMillis())+"-"+(++sequence);feedbackAvailable=false;feedback="";lastInventory="";dismissPattern();
        String expected=id;journalReady=records.submit(()->{try{journal.begin(expected,label);}catch(Exception failure){android.util.Log.w("UnboundDisplayTest","Cannot begin test history",failure);}});
        DebugData.get(context).pauseDisplayObservations();
        note("INITIAL "+DebugData.externalDisplays(context));
    }
    private final Runnable poll=new Runnable(){public void run(){
        main.removeCallbacks(this);screen=DisplayTestScreens.read(context);
        if(run!=null&&run.busy()){
            String inventory=DebugData.externalDisplays(context);
            if(!inventory.equals(lastInventory)){lastInventory=inventory;note("DISPLAY uptimeMs="+SystemClock.uptimeMillis()+"; "+inventory);}
            boolean attached=true;
            if(ticket!=null)try{attached=Boolean.TRUE.equals(api().getMethod("attached",Object.class).invoke(null,ticket));}catch(Exception failure){attached=false;}
            run.sample(SystemClock.uptimeMillis(),attached,screen.id,screen.width,screen.height,screen.hz,screen.on);
        }
        String view=status+"|"+output()+"|"+countdown()+"|"+capturesPending+"|"+nativePending;
        if(!view.equals(viewState)){viewState=view;changed();}
        if(visible||nativePending||(run!=null&&run.busy()))main.postDelayed(this,250);
    }};
    private Consumer<Bundle> callback(int request,boolean finishing){
        String expected=id;
        return answer->main.post(()->{
            if(!expected.equals(id))return;
            if(finishing){ticket=null;nativePending=false;restoreOnReserve=false;}
            note((finishing?"RECOVERY":"COMMAND")+" mode="+answer.getInt("mode",-1)+" ok="+answer.getBoolean("ok")+"; "+answer.getString("message"));
            if(run!=null)run.reply(request,answer.getBoolean("ok"),answer.getString("message"),SystemClock.uptimeMillis());
            changed();
        });
    }
    private void finishTicket(){
        Object token=ticket;restoreOnReserve=false;
        invoke("finish",new Class<?>[]{Object.class,Consumer.class},token,callback(restoreRequest,true));
    }
    private void invoke(String method,Class<?>[] types,Object... args){
        try{api().getMethod(method,types).invoke(null,args);}
        catch(Exception|LinkageError failure){
            @SuppressWarnings("unchecked") Consumer<Bundle> callback=(Consumer<Bundle>)args[args.length-1];
            Bundle answer=new Bundle();answer.putBoolean("ok",false);answer.putString("message",error(failure));callback.accept(answer);
        }
    }
    private static Class<?> api() throws ClassNotFoundException{return Class.forName("dev.beampro.port.DisplayTestCompat");}
    private static String error(Throwable failure){Throwable cause=failure.getCause()==null?failure:failure.getCause();return cause.getMessage()==null?"Display tests are unavailable in this build":cause.getMessage();}
    private void showPattern(boolean stereo){
        Activity activity=owner.get();if(!visible||activity==null||activity.isFinishing()){run.stop(SystemClock.uptimeMillis());return;}
        try{dismissPattern();pattern=new DisplayTestPattern(activity,screen.display,stereo);pattern.show();feedbackAvailable=true;note("PATTERN shown; wearer feedback has not yet been supplied");changed();}
        catch(RuntimeException failure){note("PATTERN ERROR "+error(failure));run.fail("The test pattern could not open on the glasses",SystemClock.uptimeMillis());}
    }
    private void dismissPattern(){if(pattern!=null){try{pattern.dismiss();}catch(RuntimeException ignored){}pattern=null;}}
    private void note(String text){String expected=id;records.execute(()->{try{journal.note(expected,text);}catch(Exception failure){android.util.Log.w("UnboundDisplayTest","Cannot retain test result",failure);}});}
    private void capture(String stage,Runnable finished){
        String expected=id,requested="Requested wallMs="+System.currentTimeMillis()+"; uptimeMs="+SystemClock.uptimeMillis()+"\n"+DebugData.externalDisplays(context);
        capturesPending++;changed();
        try{captures.execute(()->{
            try{
                Future<?> ready=journalReady;if(ready!=null)ready.get(5,TimeUnit.SECONDS);
                String report=requested+"\n"+DebugDisplay.captureStartup(context);
                journal.capture(expected,stage,report);
            }catch(Exception failure){try{journal.note(expected,"CAPTURE "+stage+" unavailable: "+error(failure));}catch(Exception ignored){}}
            finally{main.post(()->{capturesPending--;if(expected.equals(id)&&finished!=null)finished.run();changed();});}
        });}catch(java.util.concurrent.RejectedExecutionException full){capturesPending--;note("CAPTURE "+stage+" skipped: previous captures still finishing");if(finished!=null)finished.run();changed();}
    }
    private void changed(){for(Runnable observer:new ArrayList<>(observers))observer.run();}
}
