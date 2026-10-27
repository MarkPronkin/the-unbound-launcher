package dev.beampro.port;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.xreal.glasses.servicesdk.helper.PingGSeriesHelper.PingCallback;

public final class EthernetReadinessTest {
    static int checks;
    static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    public static class Helper {public Handler mSdkHandler=new Handler();public Runnable mRunnablePing=()->{};}
    public static class Poll {public Helper this$0;Poll(Helper h){this$0=h;}}
    static class Remote implements IBinder {
        int calls,exit;boolean alive=true,supported=true,timeout;String output="reply from glasses";Runnable before;
        public boolean isBinderAlive(){return alive;}
        public boolean transact(int code,Parcel data,Parcel reply,int flags){
            check(code==0x00FFFFF4&&data.readString().equals("dev.beampro.bridge.GLASSES_PING"),"Use only the fixed readiness transaction");
            calls++;if(!supported)return false;if(before!=null)before.run();
            reply.writeInt(exit);reply.writeInt(timeout?1:0);reply.writeString(output);return true;
        }
    }
    static class Setup {
        Context context=new Context();Helper helper=new Helper();Remote remote=new Remote();AtomicInteger results=new AtomicInteger();
        PingCallback callback;
        Setup(){context.usb.granted=true;context.usb.devices.put("glasses",new UsbDevice("glasses",13080,1078,12));
            context.resolver.remote=remote;com.xreal.glassesdisplayplugevent.GlassesInitSetting.context=context;
            callback=(PingCallback)PingCallbackCompat.wrap(helper,(PingCallback)ok->results.incrementAndGet());}
        boolean ping(){return EthernetReadinessCompat.ping(new Poll(helper));}
        void close(){PingCallbackCompat.released(helper);helper.mSdkHandler.drain();}
    }
    public static void main(String[] args)throws Exception {
        Setup s=new Setup();check(s.ping()&&s.remote.calls==1,"A helper reply allows readiness without an app-side SO_BINDTODEVICE call");
        s.callback.onPingResult(true);s.close();check(s.results.get()==1,"Normal completion remains once-only");
        s=new Setup();s.remote.exit=2;check(!s.ping(),"A failed ping cannot report readiness");s.close();
        s=new Setup();s.remote.timeout=true;check(!s.ping(),"Timeout is failure even with exit zero");s.close();
        s=new Setup();s.context.resolver.remote=null;check(!s.ping(),"An absent helper cannot report readiness");s.close();
        s=new Setup();s.remote.supported=false;check(!s.ping(),"An older helper cannot report readiness");s.close();
        s=new Setup();s.remote.alive=false;check(!s.ping(),"A dead helper cannot report readiness");s.close();
        s=new Setup();s.context.usb.granted=false;check(!s.ping()&&s.remote.calls==0,"Unauthorized USB never invokes the helper");s.close();
        s=new Setup();s.context.usb.devices.clear();s.context.usb.devices.put("other",new UsbDevice("other",1,2,3));
        check(!s.ping()&&s.remote.calls==0,"Unrelated USB cannot satisfy readiness");s.close();
        s=new Setup();final Setup replaced=s;s.remote.before=()->replaced.context.usb.devices.put("glasses",new UsbDevice("glasses",13080,1078,13));
        check(!s.ping(),"Re-enumeration cannot reuse the preceding device's ping reply");s.close();
        s=new Setup();final Setup detached=s;s.remote.before=()->detached.context.usb.devices.clear();
        check(!s.ping(),"USB disappearance invalidates a positive helper reply");s.close();

        s=new Setup();final Setup cancelled=s;CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        s.remote.before=()->{entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}finished.countDown();};
        AtomicBoolean ready=new AtomicBoolean(true);Thread worker=new Thread(()->ready.set(cancelled.ping()));worker.start();
        check(entered.await(2,TimeUnit.SECONDS),"Helper request started");long start=System.nanoTime();s.close();worker.join(1000);
        check(!worker.isAlive()&&!ready.get()&&(System.nanoTime()-start)/1000000<1000,"Cancellation does not wait for a blocked helper reply");
        Setup waiting=new Setup();check(!waiting.ping()&&waiting.remote.calls==0,"Blocked IPC cannot accumulate more helper requests");waiting.close();
        release.countDown();check(finished.await(2,TimeUnit.SECONDS),"Old helper request drained");Thread.sleep(100);
        check(s.results.get()==1,"Late helper success cannot duplicate the cancelled callback");

        s=new Setup();CountDownLatch unblock=new CountDownLatch(1),timeoutDrained=new CountDownLatch(1);
        s.remote.before=()->{while(unblock.getCount()>0)try{unblock.await();}catch(InterruptedException ignored){}timeoutDrained.countDown();};
        long deadlineStart=System.nanoTime();check(!s.ping(),"A stuck helper times out without reporting readiness");
        check((System.nanoTime()-deadlineStart)/1000000<5500,"IPC has a hard four-second client deadline");s.close();
        unblock.countDown();check(timeoutDrained.await(2,TimeUnit.SECONDS),"Timed-out request drains");Thread.sleep(100);

        s=new Setup();s.remote.output=new String(new char[2049]);check(!s.ping(),"Reject oversized helper responses");s.close();
        s=new Setup();check(s.ping(),"A new request succeeds after cancellation drains");s.close();
        System.out.println("PASS Ethernet readiness assertions: "+checks);
    }
}
