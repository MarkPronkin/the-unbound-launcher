package dev.beampro.bridge;

import java.util.*;

public final class AutoArEntryTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static final class Platform implements AutoArEntry.Platform {
        static class Task {Runnable r;long at;Task(Runnable r,long at){this.r=r;this.at=at;}}
        List<Task> tasks=new ArrayList<>();int uid=10123,grants,launches;boolean unlocked=true,present=true;long now;Runnable duringGrant;
        public int installedUid(){return uid;}public boolean unlocked(){return unlocked;}
        public boolean grant(AutoArEntry.Key key){grants++;if(duringGrant!=null)duringGrant.run();return present;}
        public void launch(){launches++;}public void later(Runnable r,long delay){tasks.add(new Task(r,now+delay));}public void log(String s){}
        void advance(long ms){long end=now+ms;int count=0;for(;;){Task next=null;for(Task t:tasks)if(t.at<=end&&(next==null||t.at<next.at))next=t;
            if(next==null)break;if(++count>1000)throw new AssertionError("busy automatic-entry loop");tasks.remove(next);now=next.at;next.r.run();}now=end;}
    }
    interface Action {void run()throws Exception;}
    static void rejected(Action action)throws Exception {try{action.run();throw new AssertionError("accepted invalid request");}catch(SecurityException expected){checks++;}}
    public static void main(String[] args)throws Exception {
        for(int uid:new int[]{10123,1010123}) {
            List<String> command=Arrays.asList(AutoArEntry.launchCommand(uid));
            check(command.get(command.indexOf("--display")+1).equals("0"),"Connection UI targets the phone even if DeX has focus");
            check(command.get(command.indexOf("--windowingMode")+1).equals("1"),"Do not reuse a freeform DeX window on the phone");
            check(command.get(command.indexOf("--user")+1).equals(Integer.toString(uid/100000)),"Retain the installed app's Android user");
            check(command.get(command.indexOf("-n")+1).equals("dev.markpronkin.unbound/ai.nreal.nebula.activity.splash.SplashActivity"),"Only the fixed connection entry can launch");
        }
        AutoArEntry.Key one=new AutoArEntry.Key("/dev/bus/usb/001/002",13080,1080,2);
        Platform p=new Platform();AutoArEntry entry=new AutoArEntry(p,10123);
        entry.request(10123,one,true);entry.request(10123,one,true);p.advance(749);
        check(p.launches==0,"Debounce before automatic entry");p.advance(1);
        check(p.grants==1&&p.launches==1,"A real authorized connection starts AR once");
        entry.request(10123,one,true);p.advance(5000);check(p.launches==1,"Duplicate USB callbacks cannot relaunch AR");
        entry.request(10123,one,false);entry.request(10123,one,true);p.advance(750);check(p.launches==2,"Physical reconnect permits a new automatic entry");

        Platform locked=new Platform();locked.unlocked=false;AutoArEntry waiting=new AutoArEntry(locked,10123);
        waiting.request(10123,one,true);locked.advance(5000);check(locked.grants==0&&locked.launches==0,"Do not start behind the lock screen");
        locked.unlocked=true;locked.advance(1000);check(locked.launches==1,"Resume the pending attachment after unlock");

        Platform detached=new Platform();AutoArEntry cancelled=new AutoArEntry(detached,10123);
        cancelled.request(10123,one,true);cancelled.request(10123,one,false);detached.advance(2000);
        check(detached.launches==0&&detached.grants==0,"Detach cancels queued automatic entry");
        detached.present=false;cancelled.request(10123,one,true);detached.advance(750);
        check(detached.grants==1&&detached.launches==0,"Revalidate live USB hardware immediately before launch");

        Platform restricted=new Platform();AutoArEntry secure=new AutoArEntry(restricted,10123);
        rejected(()->secure.request(2000,one,true));rejected(()->secure.request(10124,one,true));
        rejected(()->secure.request(10123,new AutoArEntry.Key("other",999,1080,2),true));
        rejected(()->secure.request(10123,new AutoArEntry.Key(null,13080,1080,2),true));
        restricted.uid=10124;rejected(()->secure.request(10123,one,true));
        restricted.uid=10123;secure.request(10123,one,true);restricted.uid=10124;restricted.advance(750);
        check(restricted.launches==0&&restricted.grants==0,"A replaced installation cannot inherit automatic entry");

        Platform racing=new Platform();AutoArEntry race=new AutoArEntry(racing,10123);
        racing.duringGrant=()->{try{race.request(10123,one,false);}catch(Exception e){throw new RuntimeException(e);}};
        race.request(10123,one,true);racing.advance(750);check(racing.launches==0,"Detach racing the USB grant cannot open an activity");

        Platform replacement=new Platform();AutoArEntry latest=new AutoArEntry(replacement,10123);
        AutoArEntry.Key two=new AutoArEntry.Key("/dev/bus/usb/001/003",13080,1078,3);
        latest.request(10123,one,true);latest.request(10123,two,true);latest.request(10123,one,false);replacement.advance(750);
        check(replacement.launches==1&&replacement.grants==1,"Only the latest actual attachment may launch");
        Platform relocked=new Platform();AutoArEntry guarded=new AutoArEntry(relocked,10123);
        relocked.duringGrant=()->relocked.unlocked=false;guarded.request(10123,one,true);relocked.advance(750);
        check(relocked.launches==0,"Recheck the lock screen after the isolated USB grant");
        relocked.duringGrant=null;relocked.unlocked=true;relocked.advance(1000);
        check(relocked.launches==1,"Resume after a lock that raced the USB grant");
        System.out.println("PASS automatic AR entry assertions: "+checks);
    }
}
