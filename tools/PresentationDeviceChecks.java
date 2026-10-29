package dev.beampro.presentationtest;

import android.app.*;
import android.content.*;
import android.hardware.display.*;
import android.media.ImageReader;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import dev.beampro.port.PresentationOnlyCompat;
import ai.nreal.activitylife.NRXRApp;
import ai.nreal.nebula.space.LaunchSpaceAcrivity;

/** Real Presentation/Activity behavior with controlled USB identity and native Unity boundary. */
public final class PresentationDeviceChecks extends Instrumentation {
    public static volatile Intent launched;
    public static volatile int launchDisplay=-1;
    public static volatile LaunchSpaceAcrivity owner;
    public static volatile boolean launchPhone;
    public static Display external;
    public static Presentation presentation;
    public static boolean ownerStopped;
    int passed;
    Entry entry;
    VirtualDisplay virtual;
    ImageReader reader;

    public static final class App extends Application {
        @Override public void onCreate(){
            super.onCreate();PresentationOnlyCompat.install(this);
            registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
                public void onActivityCreated(Activity a,Bundle b){}
                public void onActivityStarted(Activity a){if(a==owner)ownerStopped=false;}
                public void onActivityResumed(Activity a){}
                public void onActivityPaused(Activity a){}
                public void onActivityStopped(Activity a){if(a==owner)ownerStopped=true;}
                public void onActivitySaveInstanceState(Activity a,Bundle b){}
                public void onActivityDestroyed(Activity a){}
            });
        }
    }
    public static final class Entry extends Activity {
        @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView v=new TextView(this);v.setText("Direct display fixture");setContentView(v);}
        @Override public void startActivity(Intent intent,Bundle options) {
            launched=new Intent(intent);launchDisplay=options.getInt("android.activity.launchDisplayId",-1);
            if(launchPhone)super.startActivity(intent,options);
        }
    }
    public static volatile Activity overlay;
    public static final class Overlay extends Activity {
        @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView v=new TextView(this);v.setText("Phone dialog");setContentView(v);overlay=this;}
        @Override public void onDestroy(){if(overlay==this)overlay=null;super.onDestroy();}
    }
    public static void showOutput(Activity activity) {
        if(external==null||activity.isFinishing()||activity.isDestroyed())return;
        presentation=new Presentation(activity,external);
        TextView content=new TextView(presentation.getContext());content.setText("Direct Presentation — no desktop activity");
        content.setTextColor(0xffffffff);content.setTextSize(32);content.setBackgroundColor(0xff166534);
        presentation.setContentView(content);
        presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        presentation.show();
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            entry=(Entry)startActivitySync(new Intent(getTargetContext(),Entry.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            NRXRApp app=NRXRApp.getInstance();app.mContext=entry;
            final int[] windowsBefore=new int[1];
            runOnMainSync(()->{
                reader=ImageReader.newInstance(3840,1080,PixelFormat.RGBA_8888,2);
                // As on the Pixel: the glasses first appear at the VGA fallback, then the helper selects native SBS.
                virtual=getTargetContext().getSystemService(DisplayManager.class).createVirtualDisplay(
                        "XREAL Presentation Fixture",640,480,160,reader.getSurface(),
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                external=virtual.getDisplay();
                check(external.getDisplayId()!=0&&external.getMode().getPhysicalWidth()==640,"Real external display starts at the VGA fallback");
                windowsBefore[0]=android.view.inspector.WindowInspector.getGlobalWindowViews().size();
            });
            launchPhone=true;
            runOnMainSync(()->app.startUnityActivityInternal(new Intent()));
            Thread.sleep(1500);waitForIdleSync();
            runOnMainSync(()->{
                check(owner==null,"AR waits while the glasses show only the VGA fallback");
                check(android.view.inspector.WindowInspector.getGlobalWindowViews().size()==windowsBefore[0],
                        "Waiting for the glasses shows no dialog or status window");
                virtual.resize(3840,1080,160);
            });
            await(()->owner!=null&&presentation!=null,15000,"Direct route launched the phone owner and its Presentation");
            await(this::serviceForeground,5000,"The live session has a foreground service");
            check(external.getMode().getPhysicalWidth()==3840,"AR starts only after native stereo output");
            waitForIdleSync();
            runOnMainSync(()->{
                check(launchDisplay==0&&!launched.getBooleanExtra("multiResumeMode",true),"Actual SDK launches phone-owned lifecycle");
                check(!app.mMultiResumeMode&&app.externalLaunches==0,"No external activity is launched");
                check(owner.getDisplay().getDisplayId()==0,"Controller remains on the phone");
                check(presentation.getDisplay().getDisplayId()==external.getDisplayId(),"Presentation uses the external display");
                check(presentation.isShowing(),"Android permits the real Presentation without a desktop task");
                check(owner.mUnityPlayer.resumes>0,"Original phone lifecycle resumed Unity");
                owner.onWindowFocusChanged(false);
                check(owner.mUnityPlayer.focused,"The visible direct owner keeps engine focus through its own overlay");
                com.unity3d.player.UnityPlayer other=new com.unity3d.player.UnityPlayer(owner);other.focused=true;
                PresentationOnlyCompat.windowFocus(other,false);
                check(!other.focused,"Focus loss for any other player is forwarded unchanged");
                PresentationOnlyCompat.pause(other);
                check(other.pauses==1,"Pause for any other player is forwarded unchanged");
                check(PresentationOnlyCompat.isActive(owner),"Session belongs to the live phone owner");
                check(NRXRApp.getFakeActivity()==owner,"Native activity lookup resolves to the phone owner");
                app.moveToVirtualDisplay();
                check(app.virtualMoves==0&&app.readyForDynamicSwitchDPImpl(),"Mode transition needs no placeholder activity/display");
                check(!app.readyForRestartSessionImpl(),"Restart waits until the glasses re-enumerate");
            });
            // A mode switch re-enumerates the glasses: remove, then add under a new logical ID.
            final int before=external.getDisplayId();
            runOnMainSync(()->{
                virtual.release();
                virtual=getTargetContext().getSystemService(DisplayManager.class).createVirtualDisplay(
                        "XREAL Presentation Fixture",3840,1080,160,reader.getSurface(),
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                external=virtual.getDisplay();
                showOutput(owner);
            });
            await(()->onMain(()->app.mDisplayId==external.getDisplayId()&&app.readyForRestartSessionImpl()),5000,
                    "Restart proceeds on the re-enumerated native output");
            check(external.getDisplayId()!=before&&PresentationOnlyCompat.isActive(owner),"The session survives the mode switch");
            check(presentation.getDisplay().getDisplayId()==external.getDisplayId(),"The SDK boundary recreates its Presentation on the new display");
            // A permission dialog over the phone owner (pause without stop), as on the Pixel at start-up.
            final int quietPauses=owner.mUnityPlayer.pauses;
            runOnMainSync(()->owner.startActivity(new Intent(owner,Overlay.class)));
            await(()->overlay!=null&&onMain(()->overlay.hasWindowFocus()&&!owner.hasWindowFocus()),5000,"A phone dialog covers the owner");
            check(owner.mUnityPlayer.pauses==quietPauses&&owner.mUnityPlayer.focused,"A phone dialog neither pauses nor unfocuses the renderer");
            runOnMainSync(()->overlay.finish());
            // The SDK's resumed owner hands window focus to its own 0x0 IME mask, so wait for idle instead.
            await(()->overlay==null,5000,"The dialog closes");waitForIdleSync();
            check(owner.mUnityPlayer.pauses==quietPauses&&owner.mUnityPlayer.focused,"The renderer kept running through the dialog");
            final int oldPauses=owner.mUnityPlayer.pauses;
            final int oldResumes=owner.mUnityPlayer.resumes;
            runOnMainSync(()->startActivitySyncOnMain());
            await(()->onMain(()->ownerStopped),5000,"A different full-screen phone activity stops the controller");
            check(owner.mUnityPlayer.pauses==oldPauses,"A hidden phone controller does not pause the glasses renderer");
            check(owner.mUnityPlayer.focused,"A hidden phone controller retains renderer focus");
            check(onMain(()->presentation.isShowing()&&PresentationOnlyCompat.isActive(owner)),"The glasses Presentation and session survive leaving the trackpad");
            check(serviceForeground(),"The hidden renderer retains its foreground service");
            runOnMainSync(()->getTargetContext().startService(new Intent(getTargetContext(),dev.beampro.port.ArSessionService.class)
                    .putExtra("unbound.arSession",-1L)));
            waitForIdleSync();
            check(serviceForeground(),"A stale session command cannot stop the current foreground service");
            Notification backgroundNotice=sessionNotice();
            check(backgroundNotice!=null&&backgroundNotice.contentIntent!=null,"The hidden session offers a return action");
            backgroundNotice.contentIntent.send();
            await(()->owner.mUnityPlayer.resumes>oldResumes,5000,"Original phone resume path executes on return");
            check(owner.mUnityPlayer.pauses==oldPauses,"Returning to the trackpad does not restart a paused renderer");
            runOnMainSync(()->entry.finish());
            runOnMainSync(()->{presentation.dismiss();virtual.release();virtual=null;});
            await(()->owner.isFinishing()||owner.isDestroyed(),15000,"Display loss closes the direct session");
            check(!PresentationOnlyCompat.isActive(owner),"Display loss releases lifecycle ownership");
            runOnMainSync(()->{
                int beforePause=owner.mUnityPlayer.pauses;
                PresentationOnlyCompat.pause(owner.mUnityPlayer);
                check(owner.mUnityPlayer.pauses==beforePause+1,"A closed session restores ordinary Unity pause behavior");
                PresentationOnlyCompat.windowFocus(owner.mUnityPlayer,false);
                check(!owner.mUnityPlayer.focused,"A closed session restores ordinary Unity focus behavior");
            });
            check(app.mMultiResumeMode,"Closing the experiment restores the SDK mode");
            await(()->!serviceForeground(),5000,"Display loss stops the foreground service");
            owner=null;presentation=null;launched=null;
            entry=(Entry)startActivitySync(new Intent(getTargetContext(),Entry.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            app.mContext=entry;ai.nreal.nebula.manager.XREALManager.target=1;
            runOnMainSync(()->{
                reader.close();reader=ImageReader.newInstance(1920,1080,PixelFormat.RGBA_8888,2);
                virtual=getTargetContext().getSystemService(DisplayManager.class).createVirtualDisplay(
                        "XREAL Presentation Fixture",1920,1080,160,reader.getSurface(),
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                external=virtual.getDisplay();app.startUnityActivityInternal(new Intent());
            });
            await(()->owner!=null&&presentation!=null,15000,"A fresh connection starts a new direct session");
            await(()->sessionNotice()!=null,5000,"The foreground notification is posted for the new session");
            runOnMainSync(()->{
                check(owner.getDisplay().getDisplayId()==0&&app.externalLaunches==0,"Reconnect still uses no external activity");
                check(presentation.getDisplay().getMode().getPhysicalWidth()==1920,"Mono 1080p output uses the same Presentation path");
                check(app.readyForRestartSessionImpl(),"Mono readiness uses its matching physical mode");
                Notification notice=sessionNotice();
                check(notice!=null&&notice.contentIntent!=null,"The session notification returns to the controller");
                try {notice.actions[0].actionIntent.send();}catch(PendingIntent.CanceledException error){throw new AssertionError(error);}
            });
            await(()->!PresentationOnlyCompat.isActive(owner),5000,"Stop AR in the notification releases the second session");
            await(()->!serviceForeground(),5000,"Stop AR removes the foreground service");
            result.putString("stream","PASS direct Presentation Android/SDK checks: "+passed+"\n");
            finish(-1,result);
        } catch(Throwable failure) {
            android.util.Log.e("PresentationChecks","Fixture failed",failure);
            result.putString("stream","FAIL direct Presentation after "+passed+": "+failure+"\n");finish(0,result);
        } finally {
            runOnMainSync(()->{
                if(presentation!=null){presentation.dismiss();presentation=null;}
                if(virtual!=null)virtual.release();if(reader!=null)reader.close();
                if(overlay!=null)overlay.finish();if(owner!=null)owner.finish();if(entry!=null)entry.finish();
            });
        }
    }
    void startActivitySyncOnMain() {
        Intent intent=new Intent(getTargetContext(),Entry.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getTargetContext().startActivity(intent);
    }
    interface Condition{boolean get();}
    Notification sessionNotice() {
        for(android.service.notification.StatusBarNotification notice:getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications())
            if(notice.getId()==7320)return notice.getNotification();
        return null;
    }
    boolean serviceForeground() {
        for(ActivityManager.RunningServiceInfo service:getTargetContext().getSystemService(ActivityManager.class).getRunningServices(50))
            if(service.service.getClassName().equals(dev.beampro.port.ArSessionService.class.getName()))return service.foreground;
        return false;
    }
    boolean onMain(Condition condition){boolean[] value=new boolean[1];runOnMainSync(()->value[0]=condition.get());return value[0];}
    void await(Condition condition,long timeout,String reason)throws Exception {
        long until=SystemClock.uptimeMillis()+timeout;
        while(!condition.get()&&SystemClock.uptimeMillis()<until)Thread.sleep(50);
        check(condition.get(),reason);
    }
    void check(boolean value,String reason){if(!value)throw new AssertionError(reason);passed++;}
}
