package dev.beampro.bridge;

import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class GlassesNetworkProbeTest {
    static int checks;
    static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    public static void main(String[] args)throws Exception {
        AtomicInteger calls=new AtomicInteger();
        GlassesNetworkProbe probe=new GlassesNetworkProbe(10123,(timeout,command)->{
            calls.incrementAndGet();check(timeout==1500,"Bound command duration");
            check(Arrays.equals(command,new String[]{"/system/bin/ping","-I","eth0","-c","1","-W","1","169.254.2.1"}),"Only the fixed glasses command");
            return new ShellCommand.Result(0,false,"one reply");});
        for(int uid:new int[]{2000,10124})try{probe.ping(uid);throw new AssertionError("Wrong caller accepted");}catch(SecurityException expected){checks++;}
        check(calls.get()==0,"Reject callers before executing anything");check(probe.ping(10123).exitCode==0,"Return the actual ping status");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        GlassesNetworkProbe busy=new GlassesNetworkProbe(10123,(timeout,command)->{entered.countDown();release.await();return new ShellCommand.Result(1,false,"unreachable");});
        Thread first=new Thread(()->{try{busy.ping(10123);}catch(Exception e){throw new RuntimeException(e);}});first.start();
        check(entered.await(2,TimeUnit.SECONDS),"Probe is in progress");check(busy.ping(10123).exitCode==-1,"Do not queue concurrent probes");release.countDown();first.join(2000);
        GlassesNetworkProbe broken=new GlassesNetworkProbe(10123,(timeout,command)->{throw new java.io.IOException("missing ping");});
        for(int i=0;i<2;i++)try{broken.ping(10123);throw new AssertionError("Exception swallowed");}catch(java.io.IOException expected){checks++;}
        System.out.println("PASS glasses ping helper assertions: "+checks);
    }
}
