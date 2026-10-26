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
        boolean desktop=caps.contains("desktopGet")&&caps.contains("desktopSet");
        boolean modes=caps.contains("modeGet")&&caps.contains("modeSet");
        Class<?> api=Class.forName("android.hardware.display.IDisplayManager");
        List<String> calls=new ArrayList<>();Display.Mode[] preferred={null};Info info=new Info();
        Object service=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,method,values)->{
            String name=method.getName();calls.add(name);
            switch(name) {
                case "getDisplayIds":return new int[]{0,17};
                case "getDisplayInfo":check((Integer)values[0]==17,"Primary display excluded");return info;
                case "getConnectionPreference":check(desktop,"Do not use a partial desktop contract");return 1;
                case "setConnectionPreference":throw new AssertionError("Already desktop");
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
            check(desktop&&Arrays.equals(command,new String[]{"settings","get","secure","mirror_built_in_display"}),
                    "No unsupported global mode policy or settings mutations");
            return new ShellCommand.Result(0,"0\n");
        };
        Constructor<?> ctor=Class.forName("dev.beampro.bridge.ManagedDisplay$AndroidPlatform").getDeclaredConstructor();ctor.setAccessible(true);
        ManagedDisplayPolicy.Platform platform=(ManagedDisplayPolicy.Platform)ctor.newInstance();
        check(platform.enableWithoutDesktopPreference()==(caps.contains("enable")&&!desktop),"Independent enable capability");
        ManagedDisplayPolicy policy=new ManagedDisplayPolicy(platform);policy.tick(0);
        check(calls.contains("enableConnectedDisplay")==caps.contains("enable"),"Automatic enable only when available");
        check(calls.contains("getConnectionPreference")==desktop,"Complete desktop capability only");
        check((preferred[0]!=null)==modes,"Complete temporary mode capability only");
        policy.close();check(preferred[0]==null,"Temporary mode restored");
        check(android.content.Context.factoryCalls==0,"No app-bound service factories");
        System.out.println("PASS OEM API combination: "+args[0]+" assertions="+checks);
    }
}
