package dev.beampro.settings;

import java.util.ArrayList;
import java.util.List;

public final class DisplayTestRunTest {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static final class Driver implements DisplayTestRun.Driver {
        final List<String> modes=new ArrayList<>(),captures=new ArrayList<>();
        int request,restores,patterns;boolean stereo,restored,done;String result;
        public void mode(boolean stereo,int id){this.stereo=stereo;request=id;modes.add(stereo?"SBS":"2D");}
        public void restore(int id){request=id;restores++;}
        public void capture(String stage){captures.add(stage);}
        public void pattern(boolean stereo){patterns++;this.stereo=stereo;}
        public void status(String text){}
        public void complete(String text,boolean restored){result=text;this.restored=restored;done=true;}
    }
    static void mono(DisplayTestRun run,long now){run.sample(now,true,7,1920,1080,60,true);run.sample(now+1000,true,7,1920,1080,60,true);}
    static void stereo(DisplayTestRun run,long now){run.sample(now,true,8,3840,1080,60,true);run.sample(now+1000,true,8,3840,1080,60,true);}
    static DisplayTestRun sbs(Driver driver){
        DisplayTestRun run=new DisplayTestRun(DisplayTestRun.Kind.SBS,driver,0);run.ready(true,"ready",1);run.reply(driver.request,true,"ack",2);mono(run,3);return run;
    }
    public static void main(String[] args){
        Driver d=new Driver();DisplayTestRun run=sbs(d);
        check(run.phase==DisplayTestRun.Phase.BASELINE&&d.modes.size()==1,"Do not switch while the 2D hardware snapshot is still being captured");
        run.capturedBefore(1100);check(d.modes.equals(java.util.Arrays.asList("2D","SBS")),"SBS test always establishes and captures 2D first");
        run.reply(d.request,true,"SBS command accepted",1101);
        run.sample(1102,true,8,640,480,60,true);
        check(d.patterns==0&&!d.done,"A successful SDK return cannot pass a VGA-only test");
        for(int i=0;i<200;i++)run.sample(1200+i,true,8,640,480,60,true);
        check(java.util.Collections.frequency(d.captures,"stereo")==1,"First VGA output is captured immediately without a dump storm");
        run.sample(13_100,true,8,640,480,60,true);
        check(run.phase==DisplayTestRun.Phase.RESTORING&&d.restores==1,"SBS timeout requests recovery once");
        run.reply(2,true,"late SBS completion",13_101);check(run.phase==DisplayTestRun.Phase.RESTORING,"Late command completion cannot revive a cancelled phase");
        run.reply(d.request,true,"2D requested",13_102);mono(run,13_103);
        check(d.done&&d.restored&&d.result.contains("640"),"Failed SBS output retains the observed fallback and confirms recovered physical 2D");
        check(d.captures.equals(java.util.Arrays.asList("before","stereo","restored")),"Export labels the before, stereo and restored states");

        d=new Driver();run=sbs(d);run.capturedBefore(1100);run.reply(d.request,true,"ok",1101);stereo(run,1102);
        check(run.phase==DisplayTestRun.Phase.PICTURE&&d.patterns==1&&d.stereo,"Stable native SBS output displays a two-eye pattern");
        run.sample(22_102,true,8,3840,1080,60,true);check(d.restores==1,"The picture test automatically returns to 2D after twenty seconds");
        run.reply(d.request,true,"restored",22_103);mono(run,22_104);
        check(d.done&&d.result.contains("SBS output reached")&&!d.result.contains("looked clear"),"Observed resolution is not reported as wearer-confirmed optical correctness");

        d=new Driver();run=new DisplayTestRun(DisplayTestRun.Kind.TWO_D,d,0);run.ready(true,"ready",1);run.reply(d.request,true,"ok",2);
        run.sample(3,true,7,1920,1080,120,true);run.sample(12_002,true,7,1920,1080,120,true);
        check(d.patterns==0&&d.restores==1,"The 60 Hz test cannot pass on a different refresh rate");
        run.reply(d.request,true,"no change needed",12_003);run.sample(12_004,true,7,1920,1080,120,true);run.sample(13_004,true,7,1920,1080,120,true);
        check(d.done&&d.restored,"Recovery can recognize an unchanged native 2D mode after preparation failed");

        d=new Driver();run=new DisplayTestRun(DisplayTestRun.Kind.SBS,d,0);run.stop(1);run.stop(2);run.ready(true,"late startup",3);
        check(d.restores==1&&d.modes.isEmpty(),"Cancel during initialization never sends a late SBS request or queues duplicate recovery");
        run.reply(d.request,true,"closed",4);mono(run,5);check(d.done,"Cancellation still observes the recovered display");

        d=new Driver();run=sbs(d);run.stop(1100);run.capturedBefore(1200);
        check(d.modes.size()==1&&d.restores==1,"A late baseline capture cannot switch the glasses after leaving the page");
        run.sample(22_000,false,-1,0,0,0,false);
        check(d.done&&!d.restored&&d.result.contains("Unplug and reconnect"),"Failed recovery gives an explicit hardware reconnect action");

        d=new Driver();run=sbs(d);run.sample(34_000,true,7,1920,1080,60,true);
        check(d.restores==1&&d.modes.size()==1,"A stalled baseline dump has a deadline and cannot leave a deferred SBS switch");

        d=new Driver();run=new DisplayTestRun(DisplayTestRun.Kind.SBS,d,0);run.sample(35_001,true,7,1920,1080,120,true);
        check(d.restores==1&&d.modes.isEmpty(),"A stalled native initialization has a bounded UI deadline");
        d=new Driver();run=new DisplayTestRun(DisplayTestRun.Kind.SBS,d,0);run.sample(1,false,-1,0,0,0,false);
        check(d.restores==1,"USB loss during preparation requests cancellation immediately");

        d=new Driver();run=new DisplayTestRun(DisplayTestRun.Kind.TWO_D,d,0);run.ready(true,"ready",1);run.reply(d.request,false,"rejected",2);
        check(d.restores==1&&d.patterns==0,"A rejected native mode command cannot display a successful test pattern");

        d=new Driver();run=sbs(d);run.capturedBefore(1100);run.reply(d.request,true,"ok",1101);stereo(run,1102);
        run.sample(3000,true,8,640,480,60,true);check(d.restores==1,"Loss of native timing during the pattern requests immediate recovery");
        check(run.remaining(100_000)==0,"Expired countdowns never become negative");

        d=new Driver();run=new DisplayTestRun(DisplayTestRun.Kind.RESTORE,d,0);run.ready(true,"ready",1);
        check(d.modes.isEmpty()&&d.restores==1,"The dedicated return-to-2D action never requests SBS");
        run.reply(d.request,true,"ok",2);mono(run,3);check(d.done&&d.restored,"Dedicated recovery records physical 2D confirmation");
        System.out.println("PASS guided display test assertions: "+checks);
    }
}
