import dev.beampro.bridge.ManagedDisplayPolicy;
import dev.beampro.bridge.ManagedDisplayPolicy.Screen;
import java.util.*;

/** Ordinary-monitor handoff on Pixel routing and Samsung/legacy display APIs. */
public final class ExternalDisplayIsolationTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static Screen monitor(int id){return ManagedDisplayPolicyTest.screen(id,"local:monitor",false,
            ManagedDisplayPolicyTest.MONO,ManagedDisplayPolicyTest.SBS,ManagedDisplayPolicyTest.MONO);}
    public static void main(String[] args)throws Exception {
        for(boolean routing:new boolean[]{true,false})for(String initial:new String[]{null,"0","1"}) {
            ManagedDisplayPolicyTest.Fake f=new ManagedDisplayPolicyTest.Fake();
            f.routeSupported=routing;f.mirror=initial;f.respect=true;
            f.screens.clear();f.screens.put(17,monitor(17));
            f.choices.put("local:monitor",1);f.preferred.put(17,ManagedDisplayPolicyTest.MONO);
            ManagedDisplayPolicyTest.MemoryJournal journal=new ManagedDisplayPolicyTest.MemoryJournal();
            ManagedDisplayPolicy p=new ManagedDisplayPolicy(f,journal);
            p.tick(0);p.tick(500);p.close();
            check(f.calls.isEmpty(),"Monitor-only startup/idle/shutdown must not mutate display state");
            check(journal.state.isEmpty(),"Monitor-only startup must not acquire policy leases");
            check(Objects.equals(f.mirror,initial)&&f.respect&&f.choices.get("local:monitor")==1,
                    "Preserve the user's Desktop/Mirror and mode policy");

            f=new ManagedDisplayPolicyTest.Fake();f.routeSupported=routing;f.mirror=initial;
            journal=new ManagedDisplayPolicyTest.MemoryJournal();p=new ManagedDisplayPolicy(f,journal);p.tick(0);
            f.screens.clear();f.screens.put(17,monitor(17)); // Android can recycle a logical ID.
            f.preferred.put(17,ManagedDisplayPolicyTest.MONO);f.choices.put("local:monitor",1);
            int before=f.calls.size();p.tick(1);
            check(Objects.equals(f.mirror,initial)&&!f.respect,
                    "The first no-glasses snapshot must release every global override");
            check(f.preferred.get(17).same(ManagedDisplayPolicyTest.MONO)
                    &&f.calls.subList(before,f.calls.size()).stream().noneMatch(c->c.startsWith("mode:")||c.startsWith("enable:")),
                    "Do not restore glasses timing or enable a replacement monitor");
            check(f.choices.get("local:monitor")==1&&(!routing||f.choices.get("local:xreal")==0),
                    "Restore only the glasses' prior route; preserve the monitor's desktop choice");
            check(journal.state.isEmpty(),"No stale glasses state after handing off to a monitor");
            before=f.calls.size();p.tick(2);p.tick(10000);p.close();
            check(f.calls.size()==before,"Background polling must leave monitor mirroring/desktop alone");

            f=new ManagedDisplayPolicyTest.Fake();f.routeSupported=routing;f.mirror=initial;
            journal=new ManagedDisplayPolicyTest.MemoryJournal();p=new ManagedDisplayPolicy(f,journal);p.tick(0);
            f.screens.clear();new ManagedDisplayPolicy(f,journal).close();
            check(Objects.equals(f.mirror,initial)&&!f.respect&&journal.state.isEmpty(),
                    "A helper restart without glasses must recover global preferences");

            f=new ManagedDisplayPolicyTest.Fake();f.routeSupported=routing;f.mirror=initial;
            p=new ManagedDisplayPolicy(f);p.tick(0);f.screens.clear();p.tick(1);
            check(f.respect&&(routing?"1".equals(f.mirror):Objects.equals(f.mirror,initial)),
                    "A brief unplug with no replacement monitor retains policies during the grace period");
            f.screens.put(17,ManagedDisplayPolicyTest.screen(17,"local:xreal",true,
                    ManagedDisplayPolicyTest.VGA,ManagedDisplayPolicyTest.SBS));p.tick(2);
            check(f.respect&&f.preferred.get(17).same(ManagedDisplayPolicyTest.SBS),
                    "Glasses can reconnect during the restoration grace period");p.close();
        }

        ManagedDisplayPolicyTest.Fake f=new ManagedDisplayPolicyTest.Fake();
        f.screens.put(18,ManagedDisplayPolicyTest.screen(18,"local:second-glasses",true,
                ManagedDisplayPolicyTest.MONO,ManagedDisplayPolicyTest.MONO));
        ManagedDisplayPolicy p=new ManagedDisplayPolicy(f);p.tick(0);
        f.screens.remove(17);p.tick(1);
        check(f.choices.get("local:xreal")==0&&f.choices.get("local:second-glasses")==2,
                "Retire an unplugged route even while a different pair of glasses stays connected");p.close();

        f=new ManagedDisplayPolicyTest.Fake();f.preferred.put(17,ManagedDisplayPolicyTest.MONO);
        p=new ManagedDisplayPolicy(f);p.tick(0);
        f.screens.put(17,ManagedDisplayPolicyTest.screen(17,"local:replacement-glasses",true,
                ManagedDisplayPolicyTest.VGA,ManagedDisplayPolicyTest.SBS));f.preferred.remove(17);p.tick(1);
        check(Collections.frequency(f.calls,"enable:17")==2,
                "A new physical display at a reused ID receives its own enable request");
        p.close();check(!f.preferred.containsKey(17),"Never apply a different pair's previous timing to reused IDs");

        f=new ManagedDisplayPolicyTest.Fake();f.respect=true;f.mirror=null;
        p=new ManagedDisplayPolicy(f);p.tick(0);f.respect=false;f.mirror="0";f.screens.clear();p.tick(1);
        check(!f.respect&&"0".equals(f.mirror),"Disconnect preserves later user/system changes to global policy");

        f=new ManagedDisplayPolicyTest.Fake(){
            @Override public void temporaryMode(Screen screen,ManagedDisplayPolicy.Mode mode){
                if(mode==null)throw new IllegalStateException("Mode restoration unavailable");
                super.temporaryMode(screen,mode);
            }
        };
        p=new ManagedDisplayPolicy(f);p.tick(0);f.allowed=false;
        boolean failed=false;try{p.tick(1);}catch(IllegalStateException expected){failed=true;}
        check(failed&&!f.respect&&"0".equals(f.mirror)&&f.choices.get("local:xreal")==0,
                "One failed mode restore must not prevent global and routing restoration");
        System.out.println("PASS external display isolation assertions: "+checks);
    }
}
