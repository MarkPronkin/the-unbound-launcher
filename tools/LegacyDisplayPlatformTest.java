package dev.beampro.bridge;

import android.os.ServiceManager;
import android.view.Display;
import java.util.*;

/** Real adapter and policy against the Android 16 connected-display contract. */
public final class LegacyDisplayPlatformTest {
    static int assertions;
    static void check(boolean value,String reason) {
        assertions++;
        if(!value)throw new AssertionError(reason);
    }
    public static final class Product {
        String pnp="MRG";
        public String getManufacturerPnpId(){return pnp;}
    }
    public static final class Info {
        public int type=2;
        public String uniqueId="local:xreal";
        public Product deviceProductInfo=new Product();
        // Deliberately no supportedModes: legacy startup must not manage modes.
        Display.Mode active=new Display.Mode(1920,1080,60);
        public Display.Mode getMode(){return active;}
    }
    public static final class Displays implements android.hardware.display.IDisplayManager {
        Map<Integer,Info> infos=new LinkedHashMap<>();
        List<Integer> enabled=new ArrayList<>();
        boolean failEnable,includeDisabled;
        public int[] getDisplayIds(boolean disabled) {
            includeDisabled=disabled;
            return infos.keySet().stream().mapToInt(Integer::intValue).toArray();
        }
        public Object getDisplayInfo(int id){return infos.get(id);}
        // This is intentionally also declared for the unsupported interface test:
        // only the named framework interface defines available capabilities.
        public void enableConnectedDisplay(int id) {
            if(failEnable)throw new SecurityException("MANAGE_DISPLAYS denied");
            Info info=infos.get(id);
            if(id<=0||info.type!=2||!info.uniqueId.startsWith("local:")
                    ||!info.deviceProductInfo.pnp.equals("MRG"))
                throw new AssertionError("Enabled an unrelated display");
            enabled.add(id);
        }
    }
    public static final class Windows implements android.view.IWindowManager {
        boolean locked,fail;
        public boolean isKeyguardLocked() {
            if(fail)throw new IllegalStateException("Guard unavailable");
            return locked;
        }
    }
    public static final class Thermal implements android.os.IThermalService {
        int status;
        public int getCurrentThermalStatus(){return status;}
    }
    static ManagedDisplayPolicy.Platform adapter()throws Exception {
        Class<?> type=Class.forName("dev.beampro.bridge.ManagedDisplay$AndroidPlatform");
        java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor();ctor.setAccessible(true);
        return (ManagedDisplayPolicy.Platform)ctor.newInstance();
    }
    public static void main(String[] args)throws Exception {
        boolean supported=args[0].equals("supported");
        Displays displays=new Displays();Windows windows=new Windows();Thermal thermal=new Thermal();
        ServiceManager.services.put("display",displays);
        ServiceManager.services.put("window",windows);
        ServiceManager.services.put("thermalservice",thermal);
        ShellCommand.runner=command->{throw new AssertionError("Legacy enable must not write settings or mode policy");};
        ManagedDisplayPolicy.Platform platform=adapter();
        ManagedDisplayPolicy policy=new ManagedDisplayPolicy(platform);
        check(platform.enableWithoutDesktopPreference()==supported,"Probe actual framework capabilities");
        policy.tick(0);
        check(displays.enabled.isEmpty(),"No glasses does nothing");
        Info unknown=new Info();unknown.deviceProductInfo.pnp="DEL";displays.infos.put(16,unknown);
        policy.tick(1);
        check(displays.enabled.isEmpty(),"Ordinary monitors keep their consent prompt");
        Info virtual=new Info();virtual.type=5;displays.infos.put(16,virtual);policy.tick(2);
        check(displays.enabled.isEmpty(),"Virtual displays are excluded");
        Info internal=new Info();internal.type=1;displays.infos.put(16,internal);policy.tick(3);
        check(displays.enabled.isEmpty(),"Internal display is excluded");
        Info nonlocal=new Info();nonlocal.uniqueId="virtual:xreal";
        displays.infos.put(16,nonlocal);policy.tick(4);
        check(displays.enabled.isEmpty(),"Matching identity without physical local address is excluded");
        displays.infos.clear();Info mono=new Info();displays.infos.put(17,mono);
        policy.tick(100);
        check(displays.includeDisabled,"Find the pending display before consent");
        if(!supported) {
            check(displays.enabled.isEmpty(),"Unsupported framework is unchanged");
            policy.close();
            System.out.println("PASS legacy connected-display regressions: "+assertions);
            return;
        }
        check(displays.enabled.equals(Arrays.asList(17)),"Enable mono connection automatically");
        check(mono.active.getPhysicalWidth()==1920,"Leave working mono timing intact");
        policy.tick(200);
        check(displays.enabled.size()==1,"Do not repeatedly enable a working connection");
        displays.infos.clear();Info stereo=new Info();stereo.active=new Display.Mode(3840,1080,60);
        displays.infos.put(18,stereo);policy.tick(300);
        check(displays.enabled.equals(Arrays.asList(17,18)),"Enable the stereo EDID reconnect without a second Cast press");
        check(stereo.active.getPhysicalWidth()==3840,"Leave the SDK's native stereo timing intact");
        displays.infos.clear();policy.tick(400);displays.infos.put(18,stereo);policy.tick(500);
        check(displays.enabled.equals(Arrays.asList(17,18,18)),"Enable a later physical reconnection");
        displays.infos.clear();displays.infos.put(19,new Info());windows.locked=true;policy.tick(600);
        check(displays.enabled.size()==3,"Never auto-enable while the phone is locked");
        windows.locked=false;policy.tick(700);
        check(displays.enabled.get(3)==19,"Recover after unlocking");
        displays.infos.clear();displays.infos.put(20,new Info());thermal.status=3;policy.tick(800);
        check(displays.enabled.size()==4,"Never auto-enable during severe thermal status");
        thermal.status=2;policy.tick(900);
        check(displays.enabled.get(4)==20,"Recover after cooling");
        displays.infos.clear();displays.infos.put(21,new Info());displays.failEnable=true;
        boolean failed=false;try{policy.tick(1000);}catch(Exception expected){failed=true;}
        check(failed&&displays.enabled.size()==5,"Permission failure does not mark a display enabled");
        displays.failEnable=false;policy.tick(1100);
        check(displays.enabled.get(5)==21,"A failed enable can retry");
        displays.infos.clear();displays.infos.put(22,new Info());windows.fail=true;
        failed=false;try{policy.tick(1200);}catch(Exception expected){failed=true;}
        check(failed&&displays.enabled.size()==6,"Guard failures prevent automatic enabling");
        windows.fail=false;policy.close();
        check(android.content.Context.factoryCalls==0,"Avoid app-bound service factories");
        System.out.println("PASS legacy connected-display regressions: "+assertions);
    }
}
