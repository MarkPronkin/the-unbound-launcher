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

        // Pixel 10 Pro / One Pro, build 2924: SurfaceFlinger advertises only
        // VGA after the EDID switch. The 769x480 mode is Android's synthetic
        // anisotropy correction, not a native stereo mode hidden by a vote.
        f=new ManagedDisplayPolicyTest.Fake();f.cap=false;
        var vga=ManagedDisplayPolicyTest.VGA;
        f.screens.put(17,ManagedDisplayPolicyTest.screen(17,"local:xreal",true,vga,
                vga,new ManagedDisplayPolicy.Mode(769,480,60)));
        p=new ManagedDisplayPolicy(f);p.tick(0);p.tick(45000);
        check(f.choices.get("local:xreal")==2,"VGA-only glasses still use the direct Presentation route");
        check(f.preferred.isEmpty()&&!f.respect,"Never manufacture an unadvertised SBS mode or override unrelated policy");
        check(f.screens.get(17).active.width==640,"A routing choice cannot restore a mode missing from the hardware list");
        check(!ManagedDisplayPolicy.limitExcludes("mVotesByDisplay:\n    35:\n"
                +"      PRIORITY_LIMIT_MODE -> CombinedVote{ mVotes=[SizeVote{ mWidth=3840, mHeight=2160, mMinWidth=0, mMinHeight=0 }, "
                +"PhysicalVote{ RefreshRateVote{ mMinRefreshRate=0.0, mMaxRefreshRate=61.0 } }] }\n",
                35,ManagedDisplayPolicyTest.SBS),"The captured Pixel 10 cap permits 3840x1080 at 60 Hz");
        p.close();check(f.choices.get("local:xreal")==0&&f.mirror.equals("0"),"VGA-only sessions restore their routing lease");
        System.out.println("PASS direct Presentation routing assertions: "+checks);
    }
}
