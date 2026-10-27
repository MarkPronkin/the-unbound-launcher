package dev.beampro.bridge;

import android.os.ServiceManager;
import android.view.Display;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

/** Real AndroidPlatform and policy; only Binder and command endpoints are simulated. */
public final class ManagedDisplayPlatformTest {
    static int assertions;static String mirror="0",capDump;static boolean ignoreSettingWrites;
    static int commandCalls;
    static final Display.Mode VGA=new Display.Mode(640,480,59.94f),SBS=new Display.Mode(3840,1080,60);
    static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    public static final class Product {public String getManufacturerPnpId(){return "MRG";}}
    public static final class Info {
        public int type=2;public String uniqueId="local:xreal";
        public Product deviceProductInfo=new Product();public Display.Mode[] supportedModes={SBS,VGA};
        Display.Mode active=VGA;public Display.Mode getMode(){return active;}
    }
    public static final class Displays implements android.hardware.display.IDisplayManager {
        Info info=new Info();boolean attached=true,respect,failReads;int choice,enables,modeWrites,routeWrites;
        Display.Mode preferred;
        public int[] getDisplayIds(boolean disabled){
            if(failReads)throw new IllegalStateException("Display service read failed");
            if(!disabled)throw new AssertionError("Disabled glasses must be discoverable");
            return attached?new int[]{0,17}:new int[]{0};
        }
        public Object getDisplayInfo(int id){return id==17&&attached?info:null;}
        public int getConnectionPreference(String unique){return choice;}
        public void setConnectionPreference(String unique,int value){
            if(!unique.equals("local:xreal")||value<0||value>2)throw new AssertionError("Unexpected connection target");choice=value;routeWrites++;
        }
        public Display.Mode getUserPreferredDisplayMode(int id){return preferred;}
        public void setUserPreferredDisplayMode(int id,Display.Mode mode,boolean store){
            if(id!=17||store)throw new AssertionError("Never persist resolution or touch phone");
            preferred=mode;modeWrites++;apply();
        }
        public boolean shouldAlwaysRespectAppRequestedMode(){return respect;}
        public void setShouldAlwaysRespectAppRequestedMode(boolean value){respect=value;apply();}
        void apply(){info.active=preferred!=null&&respect?preferred:VGA;}
        public void enableConnectedDisplay(int id){
            if(id!=17||choice!=2||!"1".equals(mirror)||preferred==null||!respect)
                throw new AssertionError("Routing and mode must be prepared before enable");enables++;
        }
    }
    public static final class Windows implements android.view.IWindowManager {
        boolean locked,fail;public boolean isKeyguardLocked(){
            if(fail)throw new IllegalStateException("Window service unavailable");return locked;
        }
    }
    public static final class Thermal implements android.os.IThermalService {
        int status;public int getCurrentThermalStatus(){return status;}
    }
    static final class Journal implements ManagedDisplayPolicy.Journal {
        Properties state=new Properties();public Properties load(){Properties p=new Properties();p.putAll(state);return p;}
        public void save(Properties p){state=new Properties();state.putAll(p);}
    }
    static ManagedDisplayPolicy.Platform adapter()throws Exception {
        Class<?> type=Class.forName("dev.beampro.bridge.ManagedDisplay$AndroidPlatform");
        java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructors()[0];ctor.setAccessible(true);
        return (ManagedDisplayPolicy.Platform)(ctor.getParameterTypes().length==0
                ?ctor.newInstance():ctor.newInstance(new android.content.Context()));
    }
    public static void main(String[] args)throws Exception {
        capDump=new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8");
        Displays displays=new Displays();Windows windows=new Windows();Thermal thermal=new Thermal();
        ServiceManager.services.put("display",displays);ServiceManager.services.put("window",windows);
        ServiceManager.services.put("thermalservice",thermal);
        ShellCommand.runner=command->{
            commandCalls++;
            if(command[0].equals("sh"))return new ShellCommand.Result(0,capDump);
            if(command.length<4||!command[0].equals("settings")||!command[2].equals("secure")
                    ||!command[3].equals("mirror_built_in_display"))throw new AssertionError("Unexpected shell command");
            if(command[1].equals("get"))return new ShellCommand.Result(0,(mirror==null?"null":mirror)+"\n");
            if(!ignoreSettingWrites)mirror=command[1].equals("delete")?null:command[4];
            return new ShellCommand.Result(0,"");
        };
        ManagedDisplayPolicy.Platform platform=adapter();
        check(platform.unlockedAndCool(),"Read lock and temperature with ApplicationSharedMemory absent");
        check(android.content.Context.factoryCalls==0,"Never construct app-bound service wrappers");
        Journal journal=new Journal();ManagedDisplayPolicy policy=new ManagedDisplayPolicy(platform,journal);
        policy.tick(0);
        check(displays.choice==2&&"1".equals(mirror),"Full adapter routes XREAL to Mirror");
        check(displays.respect&&displays.preferred.getPhysicalWidth()==3840&&displays.enables==1,"Full adapter reaches temporary SBS preparation and enable");
        check(platform.screens().get(0).active.width==3840,"Observe resulting native mode through fresh direct snapshots");
        int writes=displays.modeWrites;policy.tick(1000);
        check(displays.modeWrites==writes&&displays.enables==1,"Repeated polls preserve a working mode");
        displays.choice=1;policy.tick(1100);policy.tick(1200);
        check(displays.choice==1&&displays.routeWrites==1,"A manual Desktop choice is not reversed by polling");
        check("0".equals(mirror),"Release the owned mirror override when Desktop is selected");
        check(displays.choice==1&&displays.routeWrites==1,
                "A manual Desktop choice is not revisited for the same glasses");
        displays.choice=2;policy.tick(1500);
        check("1".equals(mirror)&&displays.routeWrites==1,"A manual return to Mirror resumes AR routing");
        windows.locked=true;policy.tick(2000);
        check(!displays.respect&&displays.preferred==null&&"0".equals(mirror),"Lock restores mode and mirror policy");
        windows.locked=false;thermal.status=3;policy.tick(3000);
        check(!displays.respect&&displays.preferred==null,"Severe thermal status blocks overrides");
        thermal.status=2;policy.tick(4000);
        check(displays.respect&&displays.preferred!=null,"Cooling and unlocking allow recovery");
        windows.fail=true;LinkedBlockingQueue<String> queue=new LinkedBlockingQueue<>();queue.add("PING");
        boolean failed=false;try{policy.runLease(queue,100);}catch(Exception expected){failed=true;}
        check(failed&&!displays.respect&&displays.preferred==null&&"0".equals(mirror),"Guard query failure rolls back instead of bypassing the guard");
        windows.fail=false;policy=new ManagedDisplayPolicy(platform,journal);policy.tick(5000);
        displays.failReads=true;failed=false;try{policy.close();}catch(Exception expected){failed=true;}
        check(failed&&!displays.respect&&"0".equals(mirror),"Global restoration survives a failed display snapshot");
        check(!journal.state.isEmpty(),"Keep pending per-display restoration in recovery journal");
        displays.failReads=false;new ManagedDisplayPolicy(platform,journal).close();
        check(displays.preferred==null&&journal.state.isEmpty(),"Recover deferred per-display restoration");
        int calls=commandCalls;failed=false;try{platform.mirror("0; bad");}catch(IllegalArgumentException expected){failed=true;}
        check(failed&&commandCalls==calls,"Reject invalid setting value before executing any command");
        mirror="0";ignoreSettingWrites=true;failed=false;try{platform.mirror("1");}catch(IllegalStateException expected){failed=true;}
        check(failed,"Read back setting writes rather than trusting an exit code");
        ignoreSettingWrites=false;platform.mirror(null);check(platform.mirror()==null,"Preserve an absent setting on restoration");
        ServiceManager.services.remove("window");failed=false;
        try{adapter().unlockedAndCool();}catch(IllegalStateException expected){failed=true;}
        check(failed,"Missing guard service fails closed");
        System.out.println("PASS headless Android platform regressions: "+assertions);
    }
}
