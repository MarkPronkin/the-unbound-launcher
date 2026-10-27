import dev.beampro.bridge.ManagedDisplayPolicy;
import dev.beampro.bridge.ManagedDisplayPolicy.Mode;
import dev.beampro.bridge.ManagedDisplayPolicy.Screen;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.nio.file.*;

/** Run the real policy and watchdog loop; no Android commands or device access. */
public class ManagedDisplayPolicyTest {
    static int assertions;
    static final Mode VGA=new Mode(640,480,59.94f),SBS=new Mode(3840,1080,60),MONO=new Mode(1920,1080,60);
    static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    static Screen screen(int id,String unique,boolean xreal,Mode active,Mode... modes){return new Screen(id,unique,xreal,active,Arrays.asList(modes));}
    static class MemoryJournal implements ManagedDisplayPolicy.Journal {
        Properties state=new Properties();boolean fail;
        public Properties load(){Properties p=new Properties();p.putAll(state);return p;}
        public void save(Properties p)throws Exception{if(fail)throw new java.io.IOException("journal failed");state=new Properties();state.putAll(p);}
    }
    static class Fake implements ManagedDisplayPolicy.Platform {
        Map<Integer,Screen> screens=new LinkedHashMap<>();Map<Integer,Mode> preferred=new HashMap<>();
        Map<String,Integer> choices=new HashMap<>();List<String> calls=new ArrayList<>();
        boolean allowed=true,cap=true,respect,routeSupported=true,throwEnable,refuseHardware;String mirror="0"; // Pixel default: phone mirroring off
        int routeWrites;LinkedBlockingQueue<String> stopQueue;String queued="STOP";
        Fake(){screens.put(17,screen(17,"local:xreal",true,VGA,SBS,VGA));}
        public List<Screen> screens(){return new ArrayList<>(screens.values());}
        public boolean unlockedAndCool(){return allowed;}
        public int connectionPreference(Screen screen){return routeSupported?choices.getOrDefault(screen.uniqueId,0):-1;}
        public void connectionPreference(Screen screen,int value){
            if(!routeSupported)throw new UnsupportedOperationException();
            choices.put(screen.uniqueId,value);routeWrites++;calls.add("route:"+value);
        }
        public boolean enableWithoutDesktopPreference(){return !routeSupported;}
        public void enable(Screen screen)throws Exception{
            calls.add("enable:"+screen.id);
            if(throwEnable)throw new java.io.IOException("enable failed");
            if(stopQueue!=null)stopQueue.offer(queued);
        }
        public String mirror(){return mirror;}public void mirror(String value){mirror=value;calls.add("mirror:"+value);}
        public Mode preferred(Screen screen){return preferred.get(screen.id);}
        public void temporaryMode(Screen screen,Mode mode){
            if(mode==null)preferred.remove(screen.id);else preferred.put(screen.id,mode);
            calls.add("mode:"+screen.id+":"+(mode==null?"none":mode.width));apply();
        }
        void apply(){for(Screen screen:new ArrayList<>(screens.values())){
            Mode value=preferred.get(screen.id);
            if(value==null)value=VGA;
            else if(cap&&!respect&&value.width>3440)value=VGA;
            if(refuseHardware)value=VGA;
            screens.put(screen.id,new Screen(screen.id,screen.uniqueId,screen.xreal,value,screen.modes));
        }}
        public boolean limitExcludes(Screen screen,Mode mode){return cap&&mode.width>3440;}
        public boolean respectsAppModes(){return respect;}
        public void respectsAppModes(boolean value){respect=value;calls.add("respect:"+value);apply();}
        public void log(String s){}
    }
    public static void main(String[] args)throws Exception{
        String dump=Files.readString(Path.of(args[0]));
        check(ManagedDisplayPolicy.limitExcludes(dump,17,SBS),"Real Pixel dump rejects SBS width");
        check(!ManagedDisplayPolicy.limitExcludes(dump,17,MONO),"Real cap allows normal 1080p width");
        check(!ManagedDisplayPolicy.limitExcludes(dump,0,SBS),"External cap must not be attributed to phone");
        Fake f=new Fake();MemoryJournal journal=new MemoryJournal();ManagedDisplayPolicy p=new ManagedDisplayPolicy(f,journal);
        p.tick(0);check(f.choices.get("local:xreal")==2,"Route XREAL to Mirror");
        check(f.mirror.equals("1")&&f.calls.contains("mirror:1"),"Select mirror content mode");
        check(SBS.same(f.preferred.get(17)),"Temporary user mode is native SBS");
        check(f.respect,"Override the stronger Pixel cap");
        check(f.screens.get(17).active.width==3840,"Mode becomes selectable in simulated Pixel policy");
        check(f.calls.indexOf("respect:true")<f.calls.indexOf("enable:17"),"Prepare policy before enabling display");
        int mutations=f.calls.size();p.tick(1000);check(f.calls.size()==mutations,"Do not repeat settings/mode mutations each poll");
        check(!journal.state.isEmpty(),"Recoverable state recorded before runtime changes");
        p.close();check(!f.respect&&f.mirror.equals("0")&&f.preferred.get(17)==null,"Restore global policies and prior preference");
        check(f.choices.get("local:xreal")==0,"Restore the previous connection choice");
        check(journal.state.isEmpty(),"Clear journal only after restoration");

        f=new Fake();f.mirror=null;f.choices.put("local:xreal",0);journal=new MemoryJournal();
        p=new ManagedDisplayPolicy(f,journal);p.tick(0);
        ManagedDisplayPolicy recovered=new ManagedDisplayPolicy(f,journal);
        check(f.choices.get("local:xreal")==0&&f.mirror==null,"Recover Ask and an absent setting after a helper crash");
        check(journal.state.isEmpty(),"Crash recovery consumes route leases");

        f=new Fake();p=new ManagedDisplayPolicy(f);p.tick(0);
        f.choices.put("local:xreal",1);p.tick(500);
        check(f.choices.get("local:xreal")==1&&f.routeWrites==1,"Do not fight a later manual Desktop choice");
        p.close();check(f.choices.get("local:xreal")==1,"Restoration preserves a user's later choice");

        f=new Fake();f.routeSupported=false;p=new ManagedDisplayPolicy(f);p.tick(0);
        check(f.routeWrites==0&&f.calls.contains("enable:17"),"Legacy Samsung/OnePlus uses enable only, without guessed preferences");
        check(f.mirror.equals("0")&&f.calls.stream().noneMatch(c->c.startsWith("mirror:")),"No global mirroring mutation on frameworks without the preference API");p.close();

        f=new Fake();f.allowed=false;p=new ManagedDisplayPolicy(f);p.tick(0);
        check(f.calls.isEmpty(),"Locked or hot devices cannot acquire direct routing");
        f.allowed=true;p.tick(500);f.allowed=false;p.tick(1000);
        check(f.choices.get("local:xreal")==0&&!f.respect&&f.preferred.get(17)==null,"Lock restores direct routing and mode policy");

        f=new Fake();p=new ManagedDisplayPolicy(f);LinkedBlockingQueue<String> queue=new LinkedBlockingQueue<>();
        queue.add("STOP");p.runLease(queue,100);
        check(f.calls.isEmpty(),"A stopped lease never selects a default route");
        f=new Fake();p=new ManagedDisplayPolicy(f);queue=new LinkedBlockingQueue<>();f.stopQueue=queue;
        queue.add("PING");p.runLease(queue,100);
        check(f.choices.get("local:xreal")==0&&f.preferred.get(17)==null,"Pipe shutdown restores a direct session");

        f=new Fake();journal=new MemoryJournal();journal.fail=true;
        p=new ManagedDisplayPolicy(f,journal);boolean failed=false;try{p.tick(0);}catch(java.io.IOException expected){failed=true;}
        check(failed&&f.routeWrites==0,"Record restoration before writing Mirror preference");

        f=new Fake();f.cap=false;p=new ManagedDisplayPolicy(f);p.tick(0);
        check(!f.respect&&!f.calls.contains("respect:true"),"No global override when no cap excludes the mode");p.close();
        f=new Fake();f.respect=true;f.mirror=null;p=new ManagedDisplayPolicy(f);p.tick(0);p.close();
        check(f.respect&&f.mirror==null,"Preserve pre-existing override and absent mirror setting");
        f=new Fake();f.screens.put(0,screen(0,"local:phone",true,VGA,SBS,VGA));
        f.screens.put(22,screen(22,"local:monitor",false,VGA,SBS,VGA));p=new ManagedDisplayPolicy(f);p.tick(0);
        check(!f.preferred.containsKey(0)&&!f.preferred.containsKey(22),"Never manage phone or arbitrary monitor");
        check(!f.choices.containsKey("local:phone")&&!f.choices.containsKey("local:monitor"),"No choice saved for other displays");p.close();
        f=new Fake();f.screens.put(17,screen(17,"local:xreal",true,MONO,MONO,VGA));p=new ManagedDisplayPolicy(f);p.tick(0);
        check(f.preferred.isEmpty()&&!f.respect,"Mono connection only gets Mirror routing");p.close();
        f=new Fake();f.screens.put(17,screen(17,"local:xreal",true,MONO,SBS,MONO,VGA));p=new ManagedDisplayPolicy(f);p.tick(0);
        check(f.preferred.isEmpty()&&!f.respect,"Do not force SBS from an ambiguous mixed EDID");p.close();
        f=new Fake();f.screens.put(17,screen(17,"local:xreal",true,new Mode(3840,1080,120),SBS,VGA));p=new ManagedDisplayPolicy(f);p.tick(0);
        check(f.preferred.isEmpty()&&!f.respect,"Leave already-working stereo refresh timing untouched");p.close();

        f=new Fake();journal=new MemoryJournal();p=new ManagedDisplayPolicy(f,journal);p.tick(0);
        recovered=new ManagedDisplayPolicy(f,journal);
        check(!f.respect&&f.mirror.equals("0")&&f.preferred.isEmpty(),"Restart recovers state after simulated hard kill");
        check(journal.state.isEmpty(),"Recovered journal is cleared");
        recovered.tick(0);f.allowed=false;recovered.tick(10);
        check(!f.respect&&f.mirror.equals("0")&&f.preferred.isEmpty(),"Lock or severe thermal state restores policies");
        f.allowed=true;recovered.tick(20);check(f.respect,"Unlock/cooling can resume the managed stereo session");recovered.close();

        f=new Fake();p=new ManagedDisplayPolicy(f);p.tick(0);
        f.screens.remove(17);f.screens.put(18,screen(18,"local:xreal",true,VGA,SBS,VGA));p.tick(50);
        check(SBS.same(f.preferred.get(18))&&f.respect,"Re-enumeration prepares the new logical ID");
        p.close();check(f.preferred.get(18)==null&&!f.respect,"Re-enumeration retains original restoration state");
        f=new Fake();p=new ManagedDisplayPolicy(f);p.tick(0);
        f.screens.put(17,screen(17,"local:other",false,MONO,MONO));f.preferred.put(17,MONO);p.close();
        check(MONO.same(f.preferred.get(17)),"Never restore a stale preference onto a recycled display ID");

        f=new Fake();p=new ManagedDisplayPolicy(f);queue=new LinkedBlockingQueue<>();
        queue.add("PING");p.runLease(queue,100);
        check(!f.respect&&f.mirror.equals("0")&&f.preferred.isEmpty(),"Pipe EOF/STOP restores in finally");
        f=new Fake();p=new ManagedDisplayPolicy(f);queue=new LinkedBlockingQueue<>();queue.add("PING");p.runLease(queue,30);
        check(!f.respect&&f.preferred.isEmpty(),"Lost parent heartbeat restores policies");
        f=new Fake();p=new ManagedDisplayPolicy(f);queue=new LinkedBlockingQueue<>();queue.add("PING");f.stopQueue=queue;f.queued="invalid";
        boolean rejected=false;try{p.runLease(queue,100);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected&&!f.respect&&f.preferred.isEmpty(),"Invalid watchdog command restores before failing");
        f=new Fake();p=new ManagedDisplayPolicy(f);f.throwEnable=true;queue=new LinkedBlockingQueue<>();queue.add("PING");
        boolean failedEnable=false;try{p.runLease(queue,100);}catch(java.io.IOException expected){failedEnable=true;}
        check(failedEnable&&!f.respect&&f.mirror.equals("0")&&f.preferred.isEmpty(),"Partial startup failure rolls back");
        f=new Fake();journal=new MemoryJournal();p=new ManagedDisplayPolicy(f,journal);journal.fail=true;
        boolean journalFailure=false;try{p.tick(0);}catch(java.io.IOException expected){journalFailure=true;}
        check(journalFailure&&f.mirror.equals("0")&&!f.respect&&f.preferred.isEmpty(),"Do not mutate runtime policy without a recovery checkpoint");
        f=new Fake();f.refuseHardware=true;p=new ManagedDisplayPolicy(f);p.tick(0);
        boolean refused=false;try{p.tick(10000);}catch(IllegalStateException expected){refused=true;}finally{p.close();}
        check(refused&&!f.respect&&f.preferred.isEmpty(),"Reject and restore a hardware switch that never completes");
        System.out.println("PASS "+assertions+" managed-display assertions");
    }
}
