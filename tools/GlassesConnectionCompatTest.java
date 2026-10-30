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
        Handler.clear();SystemClock.now=0;ConnectionAnimation.defer=false;ConnectionHandoff.defer=false;FirmwareWarningView.last=null;
        ConnectionHandoff.last=null;ai.nreal.nebula.util.helper.DbHelpUtil.mirroring=false;
        Context.app=new Application();Context.displays=new DisplayManager();Context.manager=new ActivityManager();Context.usb=new UsbManager();
        Context.usb.devices.put("glasses",new UsbDevice(13080));XREALManager.instance=new XREALManager();
        dev.beampro.settings.DebugData.events.clear();dev.beampro.settings.DebugData.fail=false;
        return new Presenter();
    }
    static void close(Presenter p){GlassesConnectionCompat.cancel(p.mActivity);Handler.advance(0);}
    public static void main(String[] args)throws Exception {
        Presenter mirror=fixture();XREALManager mirrorManager=XREALManager.instance;
        ai.nreal.nebula.util.helper.DbHelpUtil.mirroring=true;
        check(GlassesConnectionCompat.startInScreenMirroring(mirror.mActivity),"Read the original persisted mirroring choice");
        GlassesConnectionCompat.begin(mirror);Handler.advance(0);
        check(mirror.success==1&&mirrorManager.launches==0&&ConnectionHandoff.last==null,
                "Mirroring SDK success closes the connection flow without launching or waiting for AR");
        check(ConnectionAnimation.last.disposed&&!ConnectionAnimation.last.failed,"Mirroring removes the loading animation");
        Handler.advance(90000);
        check(!ConnectionAnimation.last.failed&&mirror.success==1,"Mirroring cannot later time out waiting for AR output");
        close(mirror);
        check(mirrorManager.mNRServiceControl.stops==0,"Successful mirroring cleanup leaves the SDK connection intact");
        for(String firmware:new String[]{"1.8.0.20260101000000","2.0.0.20260901182643","1.9.1.20260914175625-beta",""}) {
            Presenter blocked=fixture();XREALManager manager=XREALManager.instance;manager.mOtaManager.software=firmware;
            GlassesConnectionCompat.begin(blocked);Handler.advance(0);
            check(manager.starts==0&&manager.mOtaManager.skuCalls==0,"Unsupported firmware cannot enter SDK or SKU initialization: "+firmware);
            check(FirmwareWarningView.last!=null&&!FirmwareWarningView.last.checking,"Unsupported firmware shows the dedicated stop screen");
            Handler.advance(90000);GlassesConnectionCompat.prepare(blocked);GlassesConnectionCompat.startSdk(blocked);
            GlassesConnectionCompat.confirm(blocked);GlassesConnectionCompat.newIntent(blocked);GlassesConnectionCompat.launch(manager);Handler.advance(0);
            check(manager.starts==0&&manager.launches==0,"Warning survives startup deadlines and cannot be bypassed by retries, intents or late launches");
            FirmwareWarningView.last.proceed.run();FirmwareWarningView.last.proceed.run();Handler.advance(0);
            check(manager.starts==1&&blocked.success==1,"An explicit connection-only override resumes exactly once");close(blocked);
            Presenter fresh=new Presenter();GlassesConnectionCompat.begin(fresh);Handler.advance(0);
            check(manager.starts==1&&FirmwareWarningView.last!=null,"Override does not carry over to the next connection");
            FirmwareWarningView.last.exit.run();Handler.advance(0);
            check(fresh.mActivity.finishing&&manager.starts==1,"Exit leaves the SDK stopped");
        }
        Presenter unreadable=fixture();XREALManager unreadableManager=XREALManager.instance;
        unreadableManager.mOtaManager.readFailure=true;GlassesConnectionCompat.begin(unreadable);Handler.advance(0);
        check(unreadableManager.starts==0&&FirmwareWarningView.last.result.detail.contains("could not be verified"),"Read failures fail closed with an explanation");
        close(unreadable);check(unreadableManager.mNRServiceControl.stops==0,"Exit before SDK startup does not send SDK shutdown commands");
        unreadable=fixture();unreadableManager=XREALManager.instance;
        unreadableManager.mOtaManager.onRead=()->{
            Handler.advance(15000);
            check(FirmwareWarningView.last!=null&&FirmwareWarningView.last.checking,"A stalled read shows the warning without starting the SDK");
            FirmwareWarningView.last.proceed.run();
            check(XREALManager.instance.starts==0,"Override cannot overlap an in-flight native firmware read");
        };
        unreadableManager.mOtaManager.software="2.0.0.20260901182643";
        GlassesConnectionCompat.begin(unreadable);Handler.advance(0);Handler.advance(15000);
        check(!FirmwareWarningView.last.checking&&unreadableManager.starts==0,"Read completion enables the explicit override after a timeout");close(unreadable);
        unreadable=fixture();unreadableManager=XREALManager.instance;
        final Presenter cancelledRead=unreadable;
        unreadableManager.mOtaManager.onRead=()->GlassesConnectionCompat.cancel(cancelledRead.mActivity);
        GlassesConnectionCompat.begin(unreadable);Handler.advance(0);
        check(unreadableManager.starts==0&&unreadableManager.mOtaManager.reads.size()==1,"Cancel during a firmware read discards the result and remaining queries");
        unreadable=fixture();unreadableManager=XREALManager.instance;unreadableManager.mOtaManager.software="2.0.0.20260901182643";
        GlassesConnectionCompat.begin(unreadable);Handler.advance(0);FirmwareWarningView oldWarning=FirmwareWarningView.last;
        Context.usb.devices.put("glasses",new UsbDevice(13080));oldWarning.proceed.run();Handler.advance(0);
        check(unreadable.mActivity.finishing&&unreadableManager.starts==0,"A replaced USB device cannot inherit an earlier firmware override");

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

        p=fixture();m=XREALManager.instance;m.nextResult=2;m.mOtaManager.software="2.0.0.20260901182643";
        GlassesConnectionCompat.begin(p);Handler.advance(0);FirmwareWarningView.last.proceed.run();Handler.advance(0);
        Context.usb.devices.put("glasses",new UsbDevice(13080));Handler.advance(2000);
        check(m.starts==2&&p.success==1&&m.mOtaManager.reads.size()==1,"An explicit override survives the SDK's own USB configuration change within the same connection");close(p);

        p=fixture();m=XREALManager.instance;ConnectionAnimation.defer=true;
        GlassesConnectionCompat.begin(p);Handler.advance(0);ConnectionAnimation pending=ConnectionAnimation.last;
        check(pending.success&&m.launches==1&&p.success==0,"The controller starts beneath the cover before the success morph completes");
        GlassesConnectionCompat.launch(m);pending.complete();Handler.advance(0);
        check(m.launches==1&&p.success==1&&p.launchesAtSuccess==1,"Handoff launches once before the original presenter callback");
        pending.complete();m.lateSuccess();Handler.advance(0);
        check(m.launches==1&&p.success==1,"Duplicate completion and stale native success cannot relaunch the controller");close(p);

        p=fixture();m=XREALManager.instance;ConnectionAnimation.defer=true;
        GlassesConnectionCompat.begin(p);Handler.advance(0);pending=ConnectionAnimation.last;close(p);
        pending.complete();m.lateSuccess();Handler.advance(0);
        check(m.launches==1&&p.success==0&&ConnectionHandoff.last.cancelled,"Closing during the morph cancels the covered controller and prevents completion");

        p=fixture();m=XREALManager.instance;ConnectionHandoff.defer=true;
        GlassesConnectionCompat.begin(p);Handler.advance(5000);pending=ConnectionAnimation.last;
        check(m.launches==1&&!pending.success&&p.success==0,"SDK success launches AR while the connection animation keeps running until real output is ready");
        check(!GlassesConnectionCompat.acceptSdkCallback(m.callback),"Late native callbacks cannot mutate display or USB state while waiting for the renderer");
        check(GlassesConnectionCompat.sessionActive(),"Automatic USB entry stays suppressed while the renderer is starting");
        ConnectionHandoff.last.ready();Handler.advance(0);
        check(pending.success&&p.success==1,"Actual controller and renderer readiness completes the handoff");
        ConnectionHandoff.last.ready();m.lateSuccess();Handler.advance(0);
        check(m.launches==1&&p.success==1,"Duplicate renderer readiness cannot complete or launch twice");close(p);

        p=fixture();m=XREALManager.instance;ConnectionHandoff.defer=true;
        GlassesConnectionCompat.begin(p);Handler.advance(0);
        ConnectionHandoff abandoned=ConnectionHandoff.last;close(p);abandoned.ready();Handler.advance(0);
        check(abandoned.cancelled&&p.success==0,"Late controller readiness after cancellation cannot finish startup");

        p=fixture();m=XREALManager.instance;ConnectionHandoff.defer=true;
        GlassesConnectionCompat.begin(p);Handler.advance(45000);
        check(ConnectionAnimation.last.failed&&ConnectionHandoff.last.cancelled&&p.success==0,"A renderer that never becomes ready times out with recovery and cancels its controller");
        ConnectionHandoff.defer=false;GlassesConnectionCompat.confirm(p);Handler.advance(0);
        check(m.starts==2&&m.launches==2&&p.success==1,"Retry after renderer timeout starts a fresh covered session");close(p);

        p=fixture();m=XREALManager.instance;ConnectionHandoff.defer=true;ConnectionAnimation.defer=true;
        GlassesConnectionCompat.begin(p);Handler.advance(44750);ConnectionHandoff.last.ready();Handler.advance(1000);
        check(!ConnectionAnimation.last.failed&&p.success==0,"Readiness just before the deadline allows the success morph to finish");
        ConnectionAnimation.last.complete();Handler.advance(0);
        check(p.success==1,"The completed morph finishes a session already acknowledged as ready");close(p);

        p=fixture();m=XREALManager.instance;ConnectionAnimation.defer=true;
        GlassesConnectionCompat.begin(p);Handler.advance(0);ConnectionHandoff.last.failed();
        ConnectionAnimation.last.complete();Handler.advance(0);
        check(ConnectionAnimation.last.failed&&ConnectionHandoff.last.cancelled&&p.success==0,"A controller lost during the ready morph returns to recovery instead of reporting success");close(p);

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
        check(m.mOtaManager.skuCalls==1&&"6_L".equals(com.xreal.basicfunc.BasicFuncManager.type),"Keep the One Pro SKU identity after passing the firmware gate");
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

        for(int product:new int[]{0x1d9c,0x1d9d}) {
            p=fixture();m=XREALManager.instance;m.mGlassesType=6;
            Context.usb.devices.put("glasses",new UsbDevice(0x0b05,product));
            m.mOtaManager.mcu="MCM103";m.mOtaManager.software="1.9.0.20260709183133";
            GlassesConnectionCompat.begin(p);Handler.advance(0);
            check(m.mOtaManager.reads.equals(java.util.Arrays.asList(1,6,3)),"Both R1 USB identities reach the actual firmware reader");
            check(FirmwareWarningView.last==null&&m.starts==1&&p.success==1,"ASUS R1 firmware passes its own policy without an override");
            check("6_L".equals(com.xreal.basicfunc.BasicFuncManager.type),"R1 policy type 1003 never replaces the native One Pro SKU identity");
            check(dev.beampro.settings.DebugData.events.stream().filter(e->e[1].equals("firmware_component")).count()==3,
                    "Retain every detected firmware component for the next hardware report");close(p);
        }
        p=fixture();m=XREALManager.instance;m.mGlassesType=6;
        Context.usb.devices.put("glasses",new UsbDevice(0x0b05,0x1d9d));m.mOtaManager.mcu="MCM102";
        GlassesConnectionCompat.begin(p);Handler.advance(0);
        check(m.starts==0&&m.mOtaManager.skuCalls==0&&FirmwareWarningView.last.result.model.equals("ROG XREAL R1"),
                "Older R1 firmware pauses before native startup with the correct model name");
        check(FirmwareWarningView.last.result.detected.equals("MCM102")&&FirmwareWarningView.last.result.updater.equals(FirmwarePolicy.ROG_UPDATER),
                "R1 warning reports the actual firmware and ASUS updater");close(p);
        p=fixture();m=XREALManager.instance;m.mGlassesType=6;
        Context.usb.devices.put("glasses",new UsbDevice(0x0b05,0x1d9d));m.mOtaManager.readFailure=true;
        GlassesConnectionCompat.begin(p);Handler.advance(0);
        check(m.starts==0&&m.mOtaManager.reads.equals(java.util.Arrays.asList(1))&&FirmwareWarningView.last.result.detected.equals("Not readable"),
                "A real R1 read failure remains distinct from a missing model policy");close(p);
        p=fixture();m=XREALManager.instance;m.mGlassesType=6;m.nextResult=2;
        Context.usb.devices.put("glasses",new UsbDevice(0x0b05,0x1d9c));
        m.mOtaManager.mcu="MCM103";m.mOtaManager.software="1.9.0.20260709183133";
        GlassesConnectionCompat.begin(p);Handler.advance(0);
        Context.usb.devices.put("glasses",new UsbDevice(0x0b05,0x1d9d));Handler.advance(2000);
        check(m.starts==2&&p.success==1&&m.mOtaManager.reads.size()==3,
                "R1 approval survives its own boot/app USB reconfiguration without overlapping reads");close(p);

        p=fixture();m=XREALManager.instance;m.nextResult=2;Context.usb.devices.clear();Context.usb.devices.put("other",new UsbDevice(1234));
        GlassesConnectionCompat.begin(p);Handler.advance(5000);
        check(m.starts==0&&m.mOtaManager.reads.isEmpty(),"An unrelated USB device cannot enter the firmware reader or SDK");close(p);

        p=fixture();m=XREALManager.instance;m.nextResult=1;
        GlassesConnectionCompat.begin(p);Handler.advance(0);
        java.util.List<String[]> events=dev.beampro.settings.DebugData.events;
        String failedAttempt=events.get(0)[0];
        check(!failedAttempt.isEmpty()&&events.get(0)[1].equals("begin"),"Record an attempt identity before SDK work");
        for(String event:new String[]{"sdk_start","sdk_result","failure"}) {
            boolean found=false;for(String[] record:events)if(record[0].equals(failedAttempt)&&record[1].equals(event))found=true;
            check(found,"Correlate diagnostic event "+event+" with the same attempt");
        }
        GlassesConnectionCompat.confirm(p);Handler.advance(0);
        boolean freshAttempt=false;for(String[] record:events)if(record[1].equals("begin")&&!record[0].equals(failedAttempt))freshAttempt=true;
        check(freshAttempt&&p.success==1,"Retry uses a fresh diagnostic attempt ID without changing startup success");
        check(m.mNRServiceControl.queries==0,"Diagnostics do not issue extra native display/status queries");close(p);
        p=fixture();m=XREALManager.instance;dev.beampro.settings.DebugData.fail=true;
        GlassesConnectionCompat.begin(p);Handler.advance(0);
        check(m.starts==1&&p.success==1,"Diagnostic storage failure cannot break a valid glasses startup");close(p);
        dev.beampro.settings.DebugData.fail=false;

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
