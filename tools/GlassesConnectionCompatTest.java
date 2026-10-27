package dev.beampro.port;

import android.app.*;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.usb.*;
import android.os.*;
import android.view.*;
import ai.nreal.nebula.manager.XREALManager;

public final class GlassesConnectionCompatTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static class Presenter {
        public Activity mActivity=new Activity();
        public Handler mHandler=new Handler(),mMainHandler=new Handler();
        public HandlerThread mHandlerThread=new HandlerThread("test");
        public boolean hasMTP;public int starts,mtpResumes,success,launchesAtSuccess=-1;
        public String mOtaStatus="connecting";public Object iDisplayEvent=new XREALManager.Event();
        private void checkUsb(boolean mtp){starts++;hasMTP=false;if(mtp){mtpResumes++;_goToSdk();}else GlassesConnectionCompat.prepare(this);}
        private void _goToSdk(){GlassesConnectionCompat.startSdk(this);}
        public void onNewIntent(){GlassesConnectionCompat.newIntent(this);}
        private void lambda$_goToSdk$4$test(int result){if(result==2)hasMTP=true;if(result==0){success++;launchesAtSuccess=XREALManager.instance.launches;}}
    }
    public static class UsbDisplayListener {public Presenter this$0;UsbDisplayListener(Presenter p){this$0=p;}}
    static Presenter fixture() throws Exception {
        java.lang.reflect.Field active=GlassesConnectionCompat.class.getDeclaredField("current");active.setAccessible(true);
        if(active.get(null)!=null)throw new AssertionError("previous session was not cleaned up");
        Handler.clear();SystemClock.now=0;ConnectionAnimation.defer=false;
        Context.app=new Application();Context.displays=new DisplayManager();Context.manager=new ActivityManager();Context.usb=new UsbManager();
        Context.usb.devices.put("glasses",new UsbDevice(13080));XREALManager.instance=new XREALManager();
        return new Presenter();
    }
    static void close(Presenter p){GlassesConnectionCompat.cancel(p.mActivity);Handler.advance(0);}
    public static void main(String[] args)throws Exception {
        Presenter p=fixture();XREALManager m=XREALManager.instance;m.nextResult=2;
        GlassesConnectionCompat.begin(p);Handler.advance(0);
        check(p.hasMTP&&m.starts==1,"USB configuration change enters the original MTP wait");
        GlassesConnectionCompat.usbDisplayRemoved(new UsbDisplayListener(p));
        GlassesConnectionCompat.status(p.mActivity,"sdkFail");Handler.advance(0);
        check(!ConnectionAnimation.last.failed,"USB re-enumeration keeps the animation waiting instead of failing");
        check(p.hasMTP,"A DisplayPort detach during USB reconfiguration must not turn the reconnect wait into an SDK failure");
        Context.usb.permission=false;Handler.advance(3000);
        check(m.starts==1,"Do not resume until the re-enumerated USB device is authorized");
        Context.usb.permission=true;Handler.advance(250);
        check(p.mtpResumes==1&&m.starts==2&&p.success==1,"Resume USB startup without an Activity onNewIntent");
        check(m.launches==1,"USB recovery launches exactly once");close(p);
        check(ConnectionAnimation.last.success&&ConnectionAnimation.last.disposed,"Successful startup settles and disposes the animation");

        p=fixture();m=XREALManager.instance;ConnectionAnimation.defer=true;
        GlassesConnectionCompat.begin(p);Handler.advance(0);ConnectionAnimation pending=ConnectionAnimation.last;
        check(pending.success&&m.launches==0&&p.success==0,"Success morph holds the controller and presenter completion");
        GlassesConnectionCompat.launch(m);pending.complete();Handler.advance(0);
        check(m.launches==1&&p.success==1&&p.launchesAtSuccess==1,"Handoff launches once before the original presenter callback");
        pending.complete();m.lateSuccess();Handler.advance(0);
        check(m.launches==1&&p.success==1,"Duplicate completion and stale native success cannot relaunch the controller");close(p);

        p=fixture();m=XREALManager.instance;ConnectionAnimation.defer=true;
        GlassesConnectionCompat.begin(p);Handler.advance(0);pending=ConnectionAnimation.last;close(p);
        pending.complete();m.lateSuccess();Handler.advance(0);
        check(m.launches==0&&p.success==0,"Closing during the morph prevents a late controller launch");

        p=fixture();m=XREALManager.instance;m.waitingDisplay=true;
        Display.Mode stereo=new Display.Mode(8,3840,1080,60f),vga=new Display.Mode(9,640,480,60f);
        Display glasses=new Display(4,true,vga,stereo,vga);Context.displays.displays=new Display[]{glasses};
        GlassesConnectionCompat.begin(p);Handler.advance(1000);
        check(m.launches==0,"Wait for the actual physical stereo mode");
        glasses.current=stereo;Handler.advance(1000);
        check(m.launches==1&&p.success==1,"A changed display with no removed event completes startup");
        check(m.mDisplayEvent==null,"Remove the old event waiter before polling completes startup");
        m.lateSuccess();Handler.advance(0);check(m.launches==1,"A later SDK callback cannot duplicate the AR launch");close(p);

        p=fixture();m=XREALManager.instance;m.waitingDisplay=true;m.mNRServiceControl.mode=9;
        Context.displays.displays=new Display[]{new Display(4,true,stereo,stereo)};
        GlassesConnectionCompat.begin(p);Handler.advance(1000);
        check(m.launches==0,"A stereo display alone is insufficient before the native SDK accepts the requested mode");
        m.mNRServiceControl.mode=5;Handler.advance(1000);
        check(m.launches==1,"Continue after the native mode and physical display agree");close(p);

        p=fixture();m=XREALManager.instance;m.waitingDisplay=true;m.target=10;m.mNRServiceControl.mode=10;
        Display.Mode mono=new Display.Mode(10,1920,1080,60f);
        Context.displays.displays=new Display[]{new Display(4,true,mono,mono)};
        GlassesConnectionCompat.begin(p);Handler.advance(1000);
        check(m.launches==1,"Preserve the original model's mono default instead of forcing stereo on every family");close(p);

        p=fixture();m=XREALManager.instance;m.waitingDisplay=true;m.target=3;
        m.mNRServiceControl.isEthernetSeries=false;m.mNRServiceControl.mode=3;
        glasses=new Display(4,true,mono,stereo,mono);Context.displays.displays=new Display[]{glasses};
        GlassesConnectionCompat.begin(p);Handler.advance(1000);
        check(m.launches==0,"Legacy SBS mode 3 must wait for stereo physical output");
        glasses.current=stereo;Handler.advance(1000);
        check(m.launches==1,"Legacy SBS startup continues after physical stereo arrives");close(p);

        p=fixture();m=XREALManager.instance;m.waitingDisplay=true;m.target=5;
        m.mNRServiceControl.isEthernetSeries=false;m.mNRServiceControl.mode=5;
        Context.displays.displays=new Display[]{new Display(4,true,mono,mono)};
        GlassesConnectionCompat.begin(p);Handler.advance(1000);
        check(m.launches==1,"Legacy 2D 72 Hz remains mono when experimental stereo is off");close(p);

        p=fixture();m=XREALManager.instance;m.waitingDisplay=true;
        Context.displays.displays=new Display[]{new Display(8,false,stereo,stereo)};
        GlassesConnectionCompat.begin(p);Handler.advance(45000);
        check(m.launches==0,"An unrelated monitor cannot satisfy display readiness");
        check(p.mActivity.mBtnConfirm.getVisibility()==View.VISIBLE,"Timeout exposes a recovery action");
        check(p.mActivity.mTvSubTitle.getText().toString().contains("timed out"),"Timeout explains which stage stalled");
        check(ConnectionAnimation.last.failed&&ConnectionAnimation.last.phase.contains("timed out"),"Full-screen recovery shows the real timeout reason");
        m.lateSuccess();Handler.advance(0);check(m.launches==0&&p.success==0,"Discard success after timeout");close(p);

        p=fixture();m=XREALManager.instance;m.nextResult=-1;
        GlassesConnectionCompat.begin(p);Handler.advance(0);
        ConnectionAnimation screen=ConnectionAnimation.last;
        GlassesConnectionCompat.status(p.mActivity,"sdkFail");Handler.advance(0);
        check(screen.failed,"A real USB failure reaches the full-screen recovery UI");
        screen.close.run();Handler.advance(0);
        check(screen.disposed&&p.mActivity.finishing,"The visible close control disposes the session");
        m.lateSuccess();Handler.advance(0);check(m.launches==0,"Closing the animation blocks a late SDK launch");

        p=fixture();m=XREALManager.instance;m.nextResult=-1;
        GlassesConnectionCompat.begin(p);Handler.advance(0);close(p);
        m.lateSuccess();Handler.advance(0);
        check(m.launches==0&&p.success==0,"Closing a connection prevents late AR startup");
        check(p.mHandlerThread.stopped,"Closing releases the original presenter worker");

        p=fixture();m=XREALManager.instance;m.nextResult=-1;
        GlassesConnectionCompat.begin(p);Handler.advance(0);close(p);
        Presenter replacement=new Presenter();GlassesConnectionCompat.begin(replacement);Handler.advance(0);
        check(m.starts==1,"Do not overlap a new native session with an unfinished cancelled request");
        m.lateSuccess();Handler.advance(0);
        check(m.launches==0,"The old native callback cannot launch into a replacement connection");close(replacement);

        p=fixture();m=XREALManager.instance;m.networkWait=true;
        GlassesConnectionCompat.begin(p);Handler.advance(0);
        com.xreal.glasses.servicesdk.helper.PingGSeriesHelper ping=m.mNRServiceControl.mPingHelper;
        ping.release();
        // Simulate an in-flight ping scheduling another poll after USB cleanup.
        ping.mSdkHandler.postDelayed(ping.mRunnablePing,200);
        Handler.advance(0);
        check(p.mActivity.mBtnConfirm.getText().equals("Try again"),"USB detach completes the ping instead of leaving startup busy forever");
        ping.callback.onPingResult(true);Handler.advance(250);
        check(ping.polls==0&&m.launches==0,"Cancelled in-flight ping cannot reschedule or report a late success");
        ping.release();Handler.advance(0);
        m.networkWait=false;GlassesConnectionCompat.confirm(p);Handler.advance(0);
        check(m.starts==2&&p.success==1&&m.launches==1,"Retry after cancelled network check succeeds without restarting the app");close(p);

        p=fixture();m=XREALManager.instance;m.networkWait=true;
        GlassesConnectionCompat.begin(p);Handler.advance(0);close(p);
        replacement=new Presenter();GlassesConnectionCompat.begin(replacement);Handler.advance(0);
        check(m.starts==1,"Replacement must wait for a pending network check to finish");
        m.mNRServiceControl.mPingHelper.release();Handler.advance(0);
        check(replacement.mActivity.mBtnConfirm.getText().equals("Try again"),"Completion refreshes the waiting replacement's visible recovery action");
        m.networkWait=false;GlassesConnectionCompat.confirm(replacement);Handler.advance(0);
        check(m.starts==2&&replacement.success==1,"Replacement no longer retains a copied busy flag after cancellation finishes");close(replacement);

        p=fixture();m=XREALManager.instance;m.networkWait=true;
        GlassesConnectionCompat.begin(p);Handler.advance(0);ping=m.mNRServiceControl.mPingHelper;
        ping.callback.onPingResult(true);ping.release();ping.callback.onPingResult(false);Handler.advance(0);
        check(p.success==1&&m.launches==1&&!ConnectionAnimation.last.failed,"Normal ping success and cleanup complete once without a false failure");close(p);

        p=fixture();m=XREALManager.instance;m.nextResult=1;
        GlassesConnectionCompat.begin(p);Handler.advance(0);
        check(p.mActivity.mBtnConfirm.getText().equals("Try again"),"SDK rejection offers retry without killing the app");
        GlassesConnectionCompat.confirm(p);Handler.advance(0);
        check(m.mNRServiceControl.stops==1,"Retry cleans up the previous SDK session");
        check(m.starts==2&&m.launches==1&&p.success==1,"Retry runs using the original live presenter worker");
        check(!p.mHandlerThread.stopped,"Retry does not stop the worker it will reuse");close(p);
        p=fixture();m=XREALManager.instance;m.nextResult=1;
        GlassesConnectionCompat.begin(p);Handler.advance(0);GlassesConnectionCompat.newIntent(p);Handler.advance(0);
        check(m.starts==2&&m.launches==1&&p.success==1,"A new automatic attachment resumes an existing failed connection sheet");close(p);

        p=fixture();m=XREALManager.instance;m.mGlassesType=6;
        GlassesConnectionCompat.begin(p);Handler.advance(0);
        check(m.mOtaManager.skuCalls==1&&"6_L".equals(com.xreal.basicfunc.BasicFuncManager.type),"Keep the One Pro SKU identity while bypassing firmware checks");
        check(p.success==1,"One Pro preparation continues to the native SDK");close(p);
        p=fixture();m=XREALManager.instance;m.mGlassesType=6;m.nextResult=2;
        GlassesConnectionCompat.begin(p);Handler.advance(0);
        com.xreal.basicfunc.BasicFuncManager.type="6";
        Handler.advance(2000);
        check(m.mOtaManager.skuCalls==2&&"6_L".equals(com.xreal.basicfunc.BasicFuncManager.type),"Restore the One Pro SKU after USB re-enumeration resets the base identity");close(p);
        p=fixture();m=XREALManager.instance;m.mGlassesType=6;m.nextResult=2;
        final Presenter pendingSku=p,nextSku=new Presenter();
        m.mOtaManager.onSecondSku=()->{GlassesConnectionCompat.cancel(pendingSku.mActivity);GlassesConnectionCompat.begin(nextSku);};
        GlassesConnectionCompat.begin(p);Handler.advance(2000);
        check(m.starts==1,"Cancellation during a reconnect SKU query cannot overlap a replacement SDK start");close(nextSku);
        p=fixture();m=XREALManager.instance;m.mGlassesType=20;
        GlassesConnectionCompat.begin(p);Handler.advance(0);
        check("20_L".equals(com.xreal.basicfunc.BasicFuncManager.type),"Keep the other SKU-specific model identity");close(p);

        p=fixture();m=XREALManager.instance;m.nextResult=2;Context.usb.devices.clear();Context.usb.devices.put("other",new UsbDevice(1234));
        GlassesConnectionCompat.begin(p);Handler.advance(5000);
        check(m.starts==1,"An unrelated USB device cannot resume the glasses connection");close(p);

        Handler.clear();Context.usb.devices.clear();GlassesConnectionCompat.install(Context.app);
        ai.nreal.nebula.activity.setting.SettingActivity page=new ai.nreal.nebula.activity.setting.SettingActivity();
        Application.ActivityLifecycleCallbacks callbacks=Context.app.callbacks.get(0);
        callbacks.onActivityResumed(page);check(page.refreshes==1,"Refresh the original Connect policy when Settings resumes");
        Handler.advance(1000);check(page.refreshes==1,"Unchanged USB state does not repeatedly rebuild Settings");
        Context.usb.devices.put("mouse",new UsbDevice(1234));Handler.advance(500);
        check(page.refreshes==1,"An unrelated USB attachment cannot enable the glasses action");
        Context.usb.devices.put("glasses",new UsbDevice(13080));Handler.advance(500);
        check(page.refreshes==2,"Refresh a disabled Connect button on glasses hotplug without reopening the app");
        Context.usb.devices.remove("glasses");Handler.advance(500);
        check(page.refreshes==3,"Refresh the action when glasses detach");
        callbacks.onActivityPaused(page);Context.usb.devices.put("glasses",new UsbDevice(13080));Handler.advance(1000);
        check(page.refreshes==3,"Stop USB observation when Settings leaves the foreground");
        callbacks.onActivityResumed(page);check(page.refreshes==4,"Refresh live connection state when returning to Settings");
        callbacks.onActivityDestroyed(page);Handler.advance(1000);check(page.refreshes==4,"Release the Settings observer on destruction");
        System.out.println("PASS connection recovery assertions: "+checks);
    }
}
