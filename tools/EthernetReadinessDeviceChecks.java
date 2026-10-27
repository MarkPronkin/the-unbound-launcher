package dev.beampro.port;

import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exercise the shipped SDK ping Runnable and its callback guard on an isolated emulator. */
public final class EthernetReadinessDeviceChecks {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static class Attempt {
        final Object helper;
        final Handler handler;
        final Runnable poll;
        final CountDownLatch done=new CountDownLatch(1);
        final AtomicInteger callbacks=new AtomicInteger(),successes=new AtomicInteger();
        final AtomicReference<String> callbackThread=new AtomicReference<>();
        Attempt()throws Exception {
            Class<?> type=Class.forName("com.xreal.glasses.servicesdk.helper.PingGSeriesHelper");helper=type.getConstructor().newInstance();
            handler=(Handler)GlassesConnectionCompat.get(helper,"mSdkHandler");poll=(Runnable)GlassesConnectionCompat.get(helper,"mRunnablePing");
            Class<?> api=Class.forName("com.xreal.glasses.servicesdk.helper.PingGSeriesHelper$PingCallback");
            Object callback=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(p,m,a)->{
                if(m.getName().equals("onPingResult")){callbacks.incrementAndGet();if((Boolean)a[0])successes.incrementAndGet();
                    callbackThread.set(Thread.currentThread().getName());done.countDown();}return null;});
            GlassesConnectionCompat.field(helper,"mCallback").set(helper,PingCallbackCompat.wrap(helper,callback));
            GlassesConnectionCompat.field(helper,"mStartPingTime").setLong(helper,System.currentTimeMillis());
        }
        void start(){handler.post(poll);}
        void cancel()throws Exception {Method release=helper.getClass().getDeclaredMethod("onRelease");release.setAccessible(true);release.invoke(helper);}
        void result(boolean expected)throws Exception {
            check(done.await(3,TimeUnit.SECONDS),"Original SDK delivered a result");
            check(callbacks.get()==1&&successes.get()==(expected?1:0),"SDK success/failure and once-only callback");
            check("ping_sdk_glasses_thread".equals(callbackThread.get()),"Original callback worker preserved");
        }
    }
    static class Probe implements EthernetReadinessCompat.Probe {
        volatile boolean attached=true;
        final AtomicInteger calls=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),drained=new CountDownLatch(1);
        boolean block,ready=true;
        public boolean attached(){return attached;}
        public EthernetReadinessCompat.Reply ping() {
            calls.incrementAndGet();entered.countDown();
            if(block)while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}
            drained.countDown();return new EthernetReadinessCompat.Reply(ready?0:1,false,"controlled helper reply");
        }
    }
    public static void main(String[] args) {
        try {
            if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
            Probe success=new Probe();EthernetReadinessCompat.factory=()->success;
            Attempt a=new Attempt();a.start();a.result(true);check(success.calls.get()==1,"SDK worker uses the delegated probe");
            a.cancel();check(a.callbacks.get()==1,"Cleanup cannot duplicate success");

            Probe failure=new Probe();failure.ready=false;EthernetReadinessCompat.factory=()->failure;
            Attempt b=new Attempt();GlassesConnectionCompat.field(b.helper,"mStartPingTime").setLong(b.helper,System.currentTimeMillis()-6000);
            b.start();b.result(false);check(failure.calls.get()==1,"SDK deadline still rejects unreachable glasses");

            Probe retry=new Probe();retry.ready=false;EthernetReadinessCompat.factory=()->retry;
            Attempt c=new Attempt();c.start();check(retry.entered.await(2,TimeUnit.SECONDS),"Failed poll started");
            long end=System.currentTimeMillis()+2000;while(retry.calls.get()<2&&System.currentTimeMillis()<end)Thread.sleep(20);
            check(retry.calls.get()>=2&&c.callbacks.get()==0,"Unsuccessful probe retains SDK polling rather than reporting success");
            c.cancel();c.result(false);Thread.sleep(100);

            Probe blocked=new Probe();blocked.block=true;EthernetReadinessCompat.factory=()->blocked;
            Attempt d=new Attempt();d.start();check(blocked.entered.await(2,TimeUnit.SECONDS),"Blocking probe reached helper");
            long cancelAt=System.nanoTime();d.cancel();d.result(false);
            check((System.nanoTime()-cancelAt)/1000000<1000,"Cancellation bypasses outstanding helper IPC promptly");
            blocked.release.countDown();check(blocked.drained.await(2,TimeUnit.SECONDS),"Late reply drained");Thread.sleep(250);
            check(d.callbacks.get()==1&&d.successes.get()==0&&blocked.calls.get()==1,"No late success or residual SDK polling after cancel");

            Probe recovered=new Probe();EthernetReadinessCompat.factory=()->recovered;
            Attempt e=new Attempt();e.start();e.result(true);
            System.out.println("PASS actual SDK Ethernet polling assertions: "+checks);System.exit(0);
        } catch(Throwable error){error.printStackTrace();System.exit(1);}
    }
}
