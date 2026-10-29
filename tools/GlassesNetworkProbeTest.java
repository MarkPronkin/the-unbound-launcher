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
        AtomicInteger recoveries=new AtomicInteger(),pings=new AtomicInteger();
        GlassesNetworkProbe recovering=new GlassesNetworkProbe(10123,(timeout,command)->{
            pings.incrementAndGet();return new ShellCommand.Result(2,false,"Network is unreachable");
        },(name,vendor,product,id)->{
            check(name.equals("glasses")&&vendor==13080&&product==1080,"Forward the exact attachment for live revalidation");
            recoveries.incrementAndGet();return "Enabled Ethernet";
        });
        check(recovering.ping(10123).exitCode==2&&recoveries.get()==0,"Legacy read-only requests never enable Ethernet");
        ShellCommand.Result enabled=recovering.ping(10123,"glasses",13080,1080,12);
        check(enabled.exitCode==2&&enabled.output.contains("Enabled Ethernet"),"Enabling Ethernet cannot invent ping success");
        recovering.ping(10123,"glasses",13080,1080,12);
        check(recoveries.get()==1,"Do not re-enable during repeated polling or override a later manual disable");
        recovering.ping(10123,"glasses",13080,1080,13);
        check(recoveries.get()==2,"A new attachment can recover again");
        int beforePings=pings.get();
        try{recovering.ping(2000,"glasses",13080,1080,14);throw new AssertionError("Shell caller accepted");}catch(SecurityException expected){checks++;}
        try{recovering.ping(10123,"other",1,2,14);throw new AssertionError("Other USB accepted");}catch(SecurityException expected){checks++;}
        try{recovering.ping(10123,null,13080,1080,14);throw new AssertionError("Missing identity accepted");}catch(SecurityException expected){checks++;}
        check(pings.get()==beforePings&&recoveries.get()==2,"Reject invalid requests before ping or network changes");
        GlassesNetworkProbe online=new GlassesNetworkProbe(10123,(timeout,command)->new ShellCommand.Result(0,false,"reply"),
                (n,v,p,id)->{throw new AssertionError("Recovery for already reachable glasses");});
        check(online.ping(10123,"glasses",13080,1080,12).exitCode==0,"Reachable glasses skip recovery");
        GlassesNetworkProbe timed=new GlassesNetworkProbe(10123,(timeout,command)->new ShellCommand.Result(-1,true,"timeout"),
                (n,v,p,id)->{throw new AssertionError("Recovery after command timeout");});
        check(timed.ping(10123,"glasses",13080,1080,12).timedOut,"A stuck ping cannot trigger extra work");
        AtomicInteger denials=new AtomicInteger();
        GlassesNetworkProbe denied=new GlassesNetworkProbe(10123,(timeout,command)->new ShellCommand.Result(2,false,"unreachable"),
                (n,v,p,id)->{denials.incrementAndGet();throw new SecurityException("denied");});
        check(denied.ping(10123,"glasses",13080,1080,12).output.contains("Ethernet recovery failed"),"Keep actionable recovery errors in the reply");
        check(denied.ping(10123,"glasses",13080,1080,12).exitCode==2&&denials.get()==1,"Denied recovery preserves failure without retrying the mutation");
        AtomicBoolean networkUp=new AtomicBoolean();
        GlassesNetworkProbe restored=new GlassesNetworkProbe(10123,
                (timeout,command)->new ShellCommand.Result(networkUp.get()?0:2,false,networkUp.get()?"reply":"unreachable"),
                (n,v,p,id)->{networkUp.set(true);return "Enabled Ethernet";});
        check(restored.ping(10123,"glasses",13080,1080,12).exitCode==2,"Recovery still completes the failed poll as failure");
        check(restored.ping(10123,"glasses",13080,1080,12).exitCode==0,"The next real ping observes successful recovery");
        System.out.println("PASS glasses ping helper assertions: "+checks);
    }
}
