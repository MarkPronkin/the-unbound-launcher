import dev.beampro.bridge.ManagedDisplayPolicy;

/** Reconnect timing uses the policy clock; no real-time waits or Android access. */
public final class ManagedDisplayDisconnectTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        ManagedDisplayPolicyTest.Fake f=new ManagedDisplayPolicyTest.Fake();
        ManagedDisplayPolicyTest.MemoryJournal journal=new ManagedDisplayPolicyTest.MemoryJournal();
        ManagedDisplayPolicy p=new ManagedDisplayPolicy(f,journal);p.tick(0);
        f.screens.clear();int before=f.calls.size();p.tick(1000);p.tick(5999);
        check(f.calls.size()==before,"No policy writes before five seconds of continuous absence");
        check(f.respect&&"1".equals(f.mirror)&&f.choices.get("local:xreal")==2,
                "Keep global and routing leases while waiting for the glasses");
        check(!journal.state.isEmpty(),"Retain the recovery journal throughout the grace period");
        p.tick(6000);
        check(!f.respect&&"0".equals(f.mirror)&&f.choices.get("local:xreal")==0,
                "Restore global and routing preferences at five seconds");
        check(journal.state.isEmpty(),"Clear the journal after delayed restoration");
        before=f.calls.size();p.tick(12000);check(f.calls.size()==before,"An expired disconnect restores only once");

        f=new ManagedDisplayPolicyTest.Fake();journal=new ManagedDisplayPolicyTest.MemoryJournal();
        f.preferred.put(17,ManagedDisplayPolicyTest.MONO);
        p=new ManagedDisplayPolicy(f,journal);p.tick(0);f.screens.clear();p.tick(1000);p.tick(5999);
        before=f.calls.size();
        f.screens.put(18,ManagedDisplayPolicyTest.screen(18,"local:xreal",true,
                ManagedDisplayPolicyTest.SBS,ManagedDisplayPolicyTest.SBS));p.tick(6000);
        check(f.calls.subList(before,f.calls.size()).stream().noneMatch(c->c.startsWith("mirror:")||c.startsWith("route:")),
                "A reconnect at the boundary cancels pending Mirror and routing restoration");
        p.close();
        check(ManagedDisplayPolicyTest.MONO.same(f.preferred.get(18)),
                "A changed logical ID retains the original mode restoration preference across the gap");

        f=new ManagedDisplayPolicyTest.Fake();p=new ManagedDisplayPolicy(f);p.tick(0);
        f.screens.clear();p.tick(1000);p.tick(5000);
        f.screens.put(17,ManagedDisplayPolicyTest.screen(17,"local:xreal",true,
                ManagedDisplayPolicyTest.SBS,ManagedDisplayPolicyTest.SBS));p.tick(5001);
        f.screens.clear();p.tick(5002);p.tick(10001);
        check(f.respect&&"1".equals(f.mirror),"Each new disconnect receives its own complete grace period");
        p.tick(10002);check(!f.respect&&"0".equals(f.mirror),"The new grace period expires at its own boundary");

        f=new ManagedDisplayPolicyTest.Fake();p=new ManagedDisplayPolicy(f);p.tick(0);
        f.screens.clear();p.tick(1000);
        f.screens.put(17,ExternalDisplayIsolationTest.monitor(17));
        f.preferred.put(17,ManagedDisplayPolicyTest.MONO);f.choices.put("local:monitor",1);p.tick(1500);
        check(!f.respect&&"0".equals(f.mirror)&&f.choices.get("local:monitor")==1,
                "A replacement physical monitor ends the grace period immediately");
        check(ManagedDisplayPolicyTest.MONO.same(f.preferred.get(17)),
                "Never restore a glasses mode onto the replacement monitor");

        f=new ManagedDisplayPolicyTest.Fake();p=new ManagedDisplayPolicy(f);p.tick(0);
        f.screens.clear();p.tick(1000);f.allowed=false;p.tick(1500);
        check(!f.respect&&"0".equals(f.mirror),"Lock or severe heat bypasses the disconnect delay");

        f=new ManagedDisplayPolicyTest.Fake();p=new ManagedDisplayPolicy(f);p.tick(0);
        f.screens.clear();p.tick(1000);p.close();
        check(!f.respect&&"0".equals(f.mirror),"Watchdog shutdown restores immediately during the grace period");

        f=new ManagedDisplayPolicyTest.Fake();f.refuseHardware=true;p=new ManagedDisplayPolicy(f);p.tick(0);
        f.screens.clear();p.tick(1000);
        f.screens.put(17,ManagedDisplayPolicyTest.screen(17,"local:xreal",true,
                ManagedDisplayPolicyTest.VGA,ManagedDisplayPolicyTest.SBS));p.tick(5000);p.tick(14999);
        boolean timedOut=false;try{p.tick(15000);}catch(IllegalStateException expected){timedOut=true;}finally{p.close();}
        check(timedOut,"Absent time does not consume the returning display's hardware mode-switch deadline");
        System.out.println("PASS disconnect grace assertions: "+checks);
    }
}
