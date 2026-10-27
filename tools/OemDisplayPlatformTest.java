package dev.beampro.bridge;

import android.os.ServiceManager;
import android.view.Display;
import java.lang.reflect.*;
import java.util.*;

/** Capability combinations are synthetic contracts, not claims of OEM hardware validation. */
public final class OemDisplayPlatformTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static final class Product {public String getManufacturerPnpId(){return "MRG";}}
    public static final class Info {
        public int type=2;
        public String uniqueId="local:glasses";
        public Product deviceProductInfo=new Product();
        public Display.Mode[] supportedModes={new Display.Mode(3840,1080,60)};
        public Display.Mode getMode(){return new Display.Mode(1280,720,60);}
    }
    public static void main(String[] args)throws Exception {
        Set<String> caps=new HashSet<>(Arrays.asList(args[0].split(",")));
        boolean routing=caps.contains("desktopGet")&&caps.contains("desktopSet");
        boolean modes=caps.contains("modeGet")&&caps.contains("modeSet");
        Class<?> api=Class.forName("android.hardware.display.IDisplayManager");
        List<String> calls=new ArrayList<>();Display.Mode[] preferred={null};Info info=new Info();
        int[] choice={1};String[] mirror={"0"};
        Object service=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,method,values)->{
            String name=method.getName();calls.add(name);
            switch(name) {
                case "getDisplayIds":return new int[]{0,17};
                case "getDisplayInfo":check((Integer)values[0]==17,"Primary display excluded");return info;
                case "getConnectionPreference":check(routing,"Do not use a partial routing contract");return choice[0];
                case "setConnectionPreference":
                    check(routing&&((Integer)values[1]==2||(Integer)values[1]==1),"Route to Mirror or restore the previous choice");
                    choice[0]=(Integer)values[1];return null;
                case "enableConnectedDisplay":check((Integer)values[0]==17,"Enable identified glasses only");return null;
                case "getUserPreferredDisplayMode":check(modes,"Do not use a partial mode contract");return preferred[0];
                case "setUserPreferredDisplayMode":check(modes&&Boolean.FALSE.equals(values[2]),"Never persist resolution");preferred[0]=(Display.Mode)values[1];return null;
                default:throw new AssertionError("Unexpected optional API: "+name);
            }
        });
        ServiceManager.services.put("display",(android.os.IBinder)service);
        ServiceManager.services.put("window",new android.view.IWindowManager(){public boolean isKeyguardLocked(){return false;}});
        ServiceManager.services.put("thermalservice",new android.os.IThermalService(){public int getCurrentThermalStatus(){return 0;}});
        ShellCommand.runner=command->{
            if(!routing)throw new AssertionError("Legacy routing must not write global settings");
            check(command.length>=4&&command[0].equals("settings")&&command[2].equals("secure")
                    &&command[3].equals("mirror_built_in_display"),"Unexpected settings command");
            if(command[1].equals("get"))return new ShellCommand.Result(0,(mirror[0]==null?"null":mirror[0])+"\n");
            mirror[0]=command[1].equals("delete")?null:command[4];
            return new ShellCommand.Result(0,"");
        };
        Constructor<?> ctor=Class.forName("dev.beampro.bridge.ManagedDisplay$AndroidPlatform").getDeclaredConstructor();ctor.setAccessible(true);
        ManagedDisplayPolicy.Platform platform=(ManagedDisplayPolicy.Platform)ctor.newInstance();
        check(platform.enableWithoutDesktopPreference()==(caps.contains("enable")&&!routing),"Independent enable capability");
        ManagedDisplayPolicy policy=new ManagedDisplayPolicy(platform);policy.tick(0);
        check(calls.contains("enableConnectedDisplay")==caps.contains("enable"),"Automatic enable only when available");
        check(calls.contains("getConnectionPreference")==routing,"Complete routing capability only");
        check(calls.contains("setConnectionPreference")==routing,"Route identified XREAL glasses to Mirror");
        check((preferred[0]!=null)==modes,"Complete temporary mode capability only");
        policy.close();check(preferred[0]==null,"Temporary mode restored");
        check(!routing||choice[0]==1,"Restore the previous connection choice");
        check(android.content.Context.factoryCalls==0,"No app-bound service factories");
        System.out.println("PASS OEM API combination: "+args[0]+" assertions="+checks);
    }
}
