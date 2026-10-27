import dev.beampro.bridge.ManagedDisplayPolicy;

/** Direct glasses display is the default: route XREAL to Mirror and restore the previous choice. */
public final class PresentationPolicyTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        ManagedDisplayPolicyTest.Fake f=new ManagedDisplayPolicyTest.Fake();f.mirror="0";
        f.choices.put("local:xreal",1); // Previously saved Desktop choice.
        ManagedDisplayPolicyTest.MemoryJournal journal=new ManagedDisplayPolicyTest.MemoryJournal();
        ManagedDisplayPolicy p=new ManagedDisplayPolicy(f,journal);p.tick(0);
        check(f.choices.get("local:xreal")==2,"Use Mirror without visiting Desktop setup");
        check(f.mirror.equals("1"),"Enable mirror content mode");
        check(f.calls.contains("enable:17"),"Enable pending physical glasses without a desktop activity");
        int writes=f.routeWrites;p.tick(500);check(f.routeWrites==writes,"Stable routing is not rewritten");
        p.close();check(f.choices.get("local:xreal")==1&&f.mirror.equals("0"),"Restore the previous connection choice and mirroring policy");
        check(journal.state.isEmpty(),"Clear the recovery journal after restoration");

        f=new ManagedDisplayPolicyTest.Fake();f.mirror="0";f.routeSupported=false;
        p=new ManagedDisplayPolicy(f);p.tick(0);
        check(f.routeWrites==0&&f.calls.contains("enable:17"),"Legacy Samsung/OnePlus uses enable only, without guessed preferences");
        check(f.mirror.equals("0"),"Frameworks without a route API keep their existing mirroring policy");
        p.close();
        System.out.println("PASS direct Presentation routing assertions: "+checks);
    }
}
