package dev.beampro.port;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import ai.nreal.nebula.manager.XREALManager;
import com.xreal.glasses.servicesdk.NRServiceControl;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class DisplayTestCompatTest {
    static int checks;
    static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    static final class Answer implements Consumer<Bundle> {
        final CountDownLatch done=new CountDownLatch(1);Bundle value;int calls;
        public void accept(Bundle value){this.value=value;calls++;done.countDown();}
        Bundle await() throws Exception {if(!done.await(3,TimeUnit.SECONDS))throw new AssertionError("missing callback");return value;}
    }
    static final class Fixture {
        final Context context=new Context();final NRServiceControl sdk;final Object token;
        Fixture(boolean ethernet,boolean borrowed) throws Exception {
            XREALManager.instance=new XREALManager();XREALManager.instance.mGlassesType=ethernet?6:2;
            context.usb.granted=true;context.usb.devices.put("glasses",new UsbDevice("glasses",13080,0x0436,7));
            sdk=XREALManager.instance.mNRServiceControl;sdk.isEthernetSeries=ethernet;sdk.isStartSdk=borrowed;
            GlassesNetworkCompat.ethernet=ethernet;token=DisplayTestCompat.reserve(context);
        }
        Answer start() throws Exception {Answer answer=new Answer();DisplayTestCompat.start(token,answer);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(answer.done.getCount()!=0&&System.nanoTime()<deadline){sdk.mPingHelper.mSdkHandler.drain();Thread.sleep(1);}
            answer.await();return answer;}
        Bundle mode(boolean stereo) throws Exception {Answer answer=new Answer();DisplayTestCompat.mode(token,stereo,answer);sdk.mPingHelper.mSdkHandler.drain();return answer.await();}
        Bundle close() throws Exception {Answer answer=new Answer();DisplayTestCompat.finish(token,answer);sdk.mPingHelper.mSdkHandler.drain();return answer.await();}
    }
    public static void main(String[] args) throws Exception {
        Fixture f=new Fixture(true,false);
        check(f.start().value.getBoolean("ok")&&f.sdk.starts==1,"Initialize only the background control SDK");
        check(f.mode(false).getBoolean("ok")&&f.mode(true).getBoolean("ok"),"Known mono and SBS requests execute");
        check(f.sdk.modes.equals(Arrays.asList(1,5)),"Ethernet tests use mono EDID 1 and SBS EDID 5");
        check(com.xreal.basicfunc.BasicFuncManager.type.equals("6_test-sku"),"Keep the SDK's One Pro SKU identity");
        check(f.close().getBoolean("ok")&&f.sdk.modes.equals(Arrays.asList(1,5,1)),"End by restoring 2D");
        check(f.sdk.stops==1&&f.sdk.teardowns==1,"Release the test only after native teardown's worker barrier");
        check(GlassesNetworkCompat.open==0&&GlassesNetworkCompat.closed>=3,"Each native mode command restores its process-network lease");

        f=new Fixture(false,false);f.start();f.mode(true);f.close();
        check(f.sdk.modes.equals(Arrays.asList(3,1)),"Legacy glasses use their separate SBS mode 3 namespace");

        f=new Fixture(true,true);f.start();f.mode(true);f.close();
        check(f.sdk.starts==0&&f.sdk.stops==0&&f.sdk.isStartSdk,"A borrowed control service is kept alive after recovery");

        f=new Fixture(true,false);f.start();Answer stale=new Answer();DisplayTestCompat.mode(f.token,true,stale);
        Answer stopped=new Answer();DisplayTestCompat.finish(f.token,stopped);f.sdk.mPingHelper.mSdkHandler.drain();
        check(!stale.await().getBoolean("ok")&&stopped.await().getBoolean("ok"),"Queued SBS work is rejected after cancellation");
        check(f.sdk.modes.isEmpty(),"A cancelled queued request retains the working mode without an unnecessary recovery write");

        f=new Fixture(true,false);f.start();Answer afterMode=new Answer();final Fixture inFlight=f;
        f.sdk.duringMode=()->DisplayTestCompat.finish(inFlight.token,afterMode);
        f.mode(true);check(afterMode.await().getBoolean("ok")&&f.sdk.modes.equals(Arrays.asList(5,1)),"Cancellation during a native write serializes recovery after that write");

        f=new Fixture(true,false);f.sdk.hold=true;Answer delayed=new Answer();DisplayTestCompat.start(f.token,delayed);
        check(f.sdk.entered.await(3,TimeUnit.SECONDS),"Delayed native startup begins");
        Answer duplicate=new Answer();DisplayTestCompat.start(f.token,duplicate);
        check(!duplicate.await().getBoolean("ok")&&f.sdk.starts==1,"A second test start cannot overlap an outstanding native initialization");
        Answer cancelledStart=new Answer();DisplayTestCompat.finish(f.token,cancelledStart);
        check(cancelledStart.done.getCount()==1&&f.sdk.modes.isEmpty(),"Do not race recovery against a still-running native initialization");
        f.sdk.complete(true);f.sdk.mPingHelper.mSdkHandler.drain();
        check(cancelledStart.await().getBoolean("ok")&&f.sdk.modes.isEmpty(),"Late startup completion cleans up without changing the untouched display mode");

        f=new Fixture(true,false);f.start();Answer replaced=new Answer();DisplayTestCompat.mode(f.token,true,replaced);
        f.context.usb.devices.put("glasses",new UsbDevice("glasses",13080,0x0436,8));f.sdk.mPingHelper.mSdkHandler.drain();
        check(!replaced.await().getBoolean("ok")&&!DisplayTestCompat.attached(f.token),"A replacement USB attachment cannot receive an old test command");
        check(!f.close().getBoolean("ok")&&f.sdk.modes.isEmpty(),"Do not send recovery settings to replacement hardware");

        f=new Fixture(true,false);XREALManager.instance.mOtaManager.bad=true;
        check(!f.start().value.getBoolean("ok")&&f.sdk.starts==0&&XREALManager.instance.mOtaManager.skuCalls==0,"Firmware mismatch blocks native initialization and SKU configuration");f.close();

        f=new Fixture(true,false);f.context.usb.granted=false;
        check(!f.start().value.getBoolean("ok")&&f.sdk.starts==0,"USB permission/helper failure is reported before SDK startup");f.close();

        f=new Fixture(true,false);f.context.usb.devices.put("other",new UsbDevice("other",13080,0x0436,9));
        check(!f.start().value.getBoolean("ok")&&f.sdk.starts==0,"Ambiguous glasses attachments cannot be mutated");f.close();

        f=new Fixture(true,false);f.sdk.throwOnStart=true;
        check(!f.start().value.getBoolean("ok"),"Native startup exceptions are reported");f.close();
        check(f.sdk.stops==1&&f.sdk.teardowns==1,"Partially initialized control is torn down before another test");

        f=new Fixture(true,false);
        try{DisplayTestCompat.reserve(f.context);throw new AssertionError("overlapping session");}catch(IllegalStateException expected){checks++;}
        f.close();Answer late=new Answer();DisplayTestCompat.start(f.token,late);
        check(!late.await().getBoolean("ok")&&f.sdk.starts==0,"A closed test token cannot initialize the SDK later");

        f=new Fixture(true,false);f.sdk.currentMode=9;
        f.context.displays.displays=new android.view.Display[]{new android.view.Display(3,true,new android.view.Display.Mode(11,1920,1080,120))};
        Bundle original=f.start().value;
        check(original.getString("message").contains("supportedModeMask=1023")&&original.getString("message").contains("input=0"),"Export firmware mode capabilities and input-mode readbacks with the original physical timing");
        check(original.getInt("restore_mode",-1)==9&&original.getInt("restore_hz_milli",0)==120000,"Remember the original native 1080p120 EDID and recovery timing");
        Bundle switched=f.mode(true);
        check(switched.getString("message").contains("beforeMode=9")&&switched.getString("message").contains("afterMode=5"),"Report original and observed EDID independently of command success");
        check(f.close().getInt("mode",-1)==9&&f.sdk.modes.equals(Arrays.asList(5,9)),"Restore the observed original mono EDID instead of forcing failing mode 1");

        f=new Fixture(true,false);f.sdk.currentMode=5;
        f.context.displays.displays=new android.view.Display[]{new android.view.Display(3,true,new android.view.Display.Mode(11,1920,1080,120))};
        check(f.start().value.getInt("restore_mode",-1)==1,"Never restore a stereo EDID as the original 2D mode");f.close();

        f=new Fixture(true,false);f.sdk.currentMode=9;
        f.context.displays.displays=new android.view.Display[]{new android.view.Display(3,true,new android.view.Display.Mode(11,640,480,60))};
        check(f.start().value.getInt("restore_mode",-1)==1,"A VGA-only attachment does not establish a working native recovery mode");f.close();

        f=new Fixture(true,false);f.sdk.currentMode=5;f.start();Answer recovery=new Answer();
        DisplayTestCompat.recover(f.token,recovery);f.sdk.mPingHelper.mSdkHandler.drain();
        check(recovery.await().getBoolean("ok")&&f.sdk.modes.equals(Arrays.asList(1)),"Explicit 2D recovery still sends a mono request without a preceding test write");

        GlassesConnectionCompat.available=false;
        try{DisplayTestCompat.reserve(new Context());throw new AssertionError("overlapped AR");}catch(IllegalStateException expected){checks++;}
        check(GlassesNetworkCompat.open==0,"No test leaves a network lease behind");
        System.out.println("PASS display test SDK adapter assertions: "+checks);
    }
}
