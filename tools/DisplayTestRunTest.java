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
    static void mono(DisplayTestRun run,long now,float hz){run.sample(now,true,7,1920,1080,hz,true);run.sample(now+1000,true,7,1920,1080,hz,true);}
    static void stereo(DisplayTestRun run,long now){run.sample(now,true,8,3840,1080,60,true);run.sample(now+1000,true,8,3840,1080,60,true);}
    static DisplayTestRun start(Driver driver,DisplayTestRun.Kind kind){
        DisplayTestRun run=new DisplayTestRun(kind,driver,0);run.ready(true,"ready",1);return run;
    }
    static DisplayTestRun sbs(Driver driver){
        DisplayTestRun run=start(driver,DisplayTestRun.Kind.SBS);run.capturedBefore(2);mono(run,3,120);return run;
    }
    public static void main(String[] args){
        Driver d=new Driver();DisplayTestRun run=start(d,DisplayTestRun.Kind.SBS);
        check(run.phase==DisplayTestRun.Phase.BASELINE&&d.modes.isEmpty()&&d.captures.equals(java.util.Arrays.asList("before")),"Capture the existing connection before any native mode change");
        run.capturedBefore(2);mono(run,3,120);
        check(d.modes.equals(java.util.Arrays.asList("SBS")),"Request SBS from native 1080p120 without forcing EDID 1");
        run.reply(d.request,true,"ack",1004);run.sample(1005,true,8,640,480,60,true);
        check(d.patterns==0&&!d.done,"A successful SDK return cannot pass a VGA-only test");
        for(int i=0;i<200;i++)run.sample(1200+i,true,8,640,480,60,true);
        check(java.util.Collections.frequency(d.captures,"stereo")==1,"Capture the first fallback without a dump storm");
        run.sample(13_100,true,8,640,480,60,true);
        check(run.phase==DisplayTestRun.Phase.RESTORING&&d.restores==1,"SBS timeout requests recovery once");
        run.reply(1,true,"late SBS completion",13_101);check(run.phase==DisplayTestRun.Phase.RESTORING,"Late command completion cannot revive a cancelled phase");
        run.recoveryTiming(120000);run.reply(d.request,true,"original EDID requested",13_102);mono(run,13_103,60);
        check(!d.done,"Recovery cannot claim the original 120 Hz timing was restored at 60 Hz");
        mono(run,14_104,120);
        check(d.done&&d.restored&&d.result.contains("640"),"Confirm original physical timing separately from recovery command");
        check(d.captures.equals(java.util.Arrays.asList("before","stereo","restored")),"Export original, switched and recovered output in order");

        d=new Driver();run=sbs(d);run.reply(d.request,true,"ok",1004);stereo(run,1005);
        check(run.phase==DisplayTestRun.Phase.PICTURE&&d.patterns==1&&d.stereo,"Stable SBS output displays a two-eye pattern");
        run.sample(22_102,true,8,3840,1080,60,true);check(d.restores==1,"The pattern requests recovery after twenty seconds");
        run.reply(d.request,true,"restored",22_103);mono(run,22_104,60);
        check(d.done&&d.result.contains("SBS output reached")&&!d.result.contains("looked clear"),"Timing does not establish optical correctness");

        d=new Driver();run=start(d,DisplayTestRun.Kind.TWO_D);
        check(d.modes.isEmpty(),"The 2D test captures original output before changing it");
        run.capturedBefore(2);run.reply(d.request,true,"ok",3);mono(run,4,120);run.sample(12_002,true,7,1920,1080,120,true);
        check(d.patterns==0&&d.restores==1,"A 60 Hz test cannot pass on a different refresh rate");
        run.reply(d.request,true,"restored",12_003);mono(run,12_004,120);
        check(d.done&&d.restored,"Recognize recovered native 2D after a failed test");
        d=new Driver();run=start(d,DisplayTestRun.Kind.TWO_D);run.capturedBefore(2);run.reply(d.request,true,"ok",3);run.sample(4,true,8,640,480,60,true);
        check(d.captures.equals(java.util.Arrays.asList("before","stereo")),"Retain VGA failure during 2D switching too");

        d=new Driver();run=start(d,DisplayTestRun.Kind.SBS);run.capturedBefore(2);run.sample(3,true,7,640,480,60,true);run.sample(12003,true,7,640,480,60,true);
        check(d.modes.isEmpty()&&d.restores==1,"Do not start SBS from an already broken VGA baseline");
        d=new Driver();run=start(d,DisplayTestRun.Kind.SBS);run.stop(2);run.capturedBefore(3);
        check(d.modes.isEmpty()&&d.restores==1,"Late baseline capture cannot send commands after cancellation");
        run.sample(22_000,false,-1,0,0,0,false);
        check(d.done&&!d.restored&&d.result.contains("Unplug and reconnect"),"Unconfirmed recovery requests a physical reconnect");
        d=new Driver();run=start(d,DisplayTestRun.Kind.SBS);run.sample(34_000,true,7,1920,1080,120,true);
        check(d.restores==1&&d.modes.isEmpty(),"A stalled baseline capture cannot send a deferred SBS command");
        d=new Driver();run=new DisplayTestRun(DisplayTestRun.Kind.SBS,d,0);run.stop(1);run.stop(2);run.ready(true,"late startup",3);
        check(d.restores==1&&d.modes.isEmpty(),"Late initialization after cancellation performs cleanup only");
        d=new Driver();run=new DisplayTestRun(DisplayTestRun.Kind.SBS,d,0);run.sample(35_001,true,7,1920,1080,120,true);
        check(d.restores==1&&d.modes.isEmpty(),"Native initialization has a bounded UI deadline");
        d=new Driver();run=new DisplayTestRun(DisplayTestRun.Kind.SBS,d,0);run.sample(1,false,-1,0,0,0,false);
        check(d.restores==1,"USB loss during preparation requests cancellation");
        d=new Driver();run=sbs(d);run.reply(d.request,false,"rejected",1004);
        check(d.restores==1&&d.patterns==0,"A rejected command cannot display a successful pattern");
        d=new Driver();run=sbs(d);run.reply(d.request,true,"ok",1004);stereo(run,1005);run.sample(3000,true,8,640,480,60,true);
        check(d.restores==1,"Loss of native output during the pattern requests recovery");
        check(run.remaining(100_000)==0,"Expired countdowns never become negative");
        d=new Driver();run=start(d,DisplayTestRun.Kind.RESTORE);
        check(d.modes.isEmpty()&&d.restores==1,"Dedicated recovery never requests SBS");
        run.reply(d.request,true,"ok",2);mono(run,3,60);check(d.done&&d.restored,"Recovery requires physical native 2D confirmation");
        System.out.println("PASS guided display test assertions: "+checks);
    }
}
