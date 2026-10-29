package dev.beampro.bridge;

import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import java.util.Arrays;

public final class GlassesEthernetTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static class Usb {
        Bundle devices=new Bundle();
        public void getDeviceList(Bundle out){out.putAll(devices);}
    }
    public static class Ethernet {
        int calls;boolean enabled,deny;
        public void setEthernetEnabled(boolean value){calls++;if(deny)throw new SecurityException("blocked");enabled=value;}
    }
    static class Runner implements GlassesNetworkProbe.Runner {
        String state="  Ethernet State: disabled\n",worker="UNBOUND_ETHERNET_ENABLED\n";
        int reads,enables,stateExit,workerExit;boolean timed;Runnable beforeState;
        public ShellCommand.Result run(long timeout,String... command){
            if(command[0].equals("/system/bin/dumpsys")) {
                reads++;check(timeout==500&&Arrays.equals(command,new String[]{"/system/bin/dumpsys","-t","1","ethernet"}),"Only a bounded fixed Ethernet state query");
                if(beforeState!=null)beforeState.run();return new ShellCommand.Result(stateExit,timed,state);
            }
            enables++;check(timeout==1500&&Arrays.equals(command,new String[]{"/system/bin/app_process","/",
                "dev.beampro.bridge.GlassesEthernet$Worker","10123","glasses","13080","1080","12"}),"Worker arguments identify only the installed launcher and glasses");
            return new ShellCommand.Result(workerExit,false,worker);
        }
    }
    public static void main(String[] args)throws Exception {
        check(GlassesEthernet.disabled("  Ethernet State: disabled\r\n"),"Recognize the Samsung disabled-state evidence");
        for(String text:new String[]{"","Ethernet State: enabled","Ethernet State: unknown","Can't find service: ethernet",
                "xEthernet State: disabled","Ethernet State: disabled\nEthernet State: enabled",
                "Ethernet State: disabled\nEthernet State: disabled"})
            check(!GlassesEthernet.disabled(text),"Unknown, enabled and ambiguous states never authorize a change");
        PackageManager pm=new PackageManager();Runner runner=new Runner();
        GlassesEthernet setup=new GlassesEthernet(pm,10123,runner);
        check(setup.enableIfDisabled("glasses",13080,1080,12).startsWith("Enabled Android Ethernet"),"Disabled service requests enable");
        check(runner.reads==0&&runner.enables==1,"One worker bounds USB validation, state inspection and enabling together");
        runner.worker="UNBOUND_ETHERNET_UNCHANGED";
        check(setup.enableIfDisabled("glasses",13080,1080,12).startsWith("Ethernet is enabled"),"Unchanged service retains readiness polling");
        int enables=runner.enables;pm.uid=10456;
        try{setup.enableIfDisabled("glasses",13080,1080,12);throw new AssertionError("Replaced installation accepted");}catch(SecurityException expected){checks++;}
        check(runner.enables==enables,"Validate the current installed UID before commands");pm.uid=10123;
        runner.worker="UNBOUND_ETHERNET_ABSENT";
        try{setup.enableIfDisabled("glasses",13080,1080,12);throw new AssertionError("Absent glasses accepted");}catch(java.io.IOException expected){checks++;}
        runner.worker="UNBOUND_ETHERNET_ENABLED";runner.workerExit=1;
        try{setup.enableIfDisabled("glasses",13080,1080,12);throw new AssertionError("Failed worker accepted");}catch(java.io.IOException expected){checks++;}

        Usb usb=new Usb();Ethernet ethernet=new Ethernet();
        usb.devices.put("glasses",new UsbDevice("glasses",13080,1080,12));
        check(GlassesEthernet.enableOnService(usb,Usb.class,ethernet,Ethernet.class,"glasses",13080,1080,12,runner),"Live matching glasses can enable through the framework API");
        check(ethernet.calls==1&&ethernet.enabled,"Call setEthernetEnabled(true)");
        for(int mismatch=0;mismatch<3;mismatch++) {
            try{GlassesEthernet.enableOnService(usb,Usb.class,ethernet,Ethernet.class,
                mismatch==0?"missing":"glasses",13080,mismatch==1?1086:1080,mismatch==2?13:12,runner);
                throw new AssertionError("Detached/replaced glasses accepted");}catch(java.io.IOException expected){checks++;}
        }
        try{GlassesEthernet.enableOnService(usb,Usb.class,ethernet,Ethernet.class,"glasses",1,1080,12,runner);throw new AssertionError("Unrelated vendor accepted");}catch(SecurityException expected){checks++;}
        check(ethernet.calls==1,"Identity mismatches have no network side effect");
        int reads=runner.reads;
        runner.state="Ethernet State: enabled";
        check(!GlassesEthernet.enableOnService(usb,Usb.class,ethernet,Ethernet.class,"glasses",13080,1080,12,runner),"Enabled service is untouched");
        runner.state="Ethernet State: disabled";runner.timed=true;
        check(!GlassesEthernet.enableOnService(usb,Usb.class,ethernet,Ethernet.class,"glasses",13080,1080,12,runner),"State-query timeout cannot authorize enable");
        runner.timed=false;runner.stateExit=1;
        check(!GlassesEthernet.enableOnService(usb,Usb.class,ethernet,Ethernet.class,"glasses",13080,1080,12,runner),"Failed state capture cannot authorize enable");
        check(ethernet.calls==1&&runner.reads==reads+3,"State checks never mutate enabled or unknown services");runner.stateExit=0;
        runner.beforeState=()->usb.devices.clear();
        try{GlassesEthernet.enableOnService(usb,Usb.class,ethernet,Ethernet.class,"glasses",13080,1080,12,runner);throw new AssertionError("Detach during state query accepted");}catch(java.io.IOException expected){checks++;}
        check(ethernet.calls==1,"Revalidate the attachment after state inspection before enabling");
        runner.beforeState=null;usb.devices.put("glasses",new UsbDevice("glasses",13080,1080,12));
        ethernet.deny=true;
        try{GlassesEthernet.enableOnService(usb,Usb.class,ethernet,Ethernet.class,"glasses",13080,1080,12,runner);throw new AssertionError("Denied call accepted");}
        catch(java.lang.reflect.InvocationTargetException expected){check(expected.getCause() instanceof SecurityException,"Keep framework permission failure");}
        try{GlassesEthernet.enableOnService(usb,Usb.class,new Object(),Object.class,"glasses",13080,1080,12,runner);throw new AssertionError("Old API accepted");}catch(NoSuchMethodException expected){checks++;}
        check(ethernet.calls==2,"No alternate mutation after a framework denial or missing API");
        System.out.println("PASS glasses Ethernet recovery assertions: "+checks);
    }
}
