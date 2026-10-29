package dev.beampro.port;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.net.*;
import android.os.*;
import com.xreal.glasses.servicesdk.helper.PingGSeriesHelper.PingCallback;
import com.xreal.glassesdisplayplugevent.GlassesInitSetting;
import java.lang.reflect.*;

public final class GlassesNetworkTest {
    static int checks;
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static class Helper {public Handler mSdkHandler=new Handler();public Runnable mRunnablePing=()->{};}
    static class Setup implements PingCallback {
        Context context=new Context();ConnectivityManager cm=context.connectivity;
        Network wifi=new Network(100),data=new Network(101),eye=new Network(102),glasses=new Network(103);
        int calls;boolean result,throwCallback;Network socketNetwork;Runnable nativeStartup;
        Setup()throws Exception {
            SystemClock.now=0;SystemClock.sleepHook=null;GlassesInitSetting.ethernet=true;GlassesInitSetting.context=context;
            context.usb.granted=true;context.usb.devices.put("glasses",new UsbDevice("glasses",13080,1078,12));
            add(wifi,"wlan0","10.0.0.109/24","10.0.0.0/24");cm.caps.get(wifi).ethernet=false;
            add(data,"rmnet0","192.0.0.2/24","192.0.0.0/24");cm.caps.get(data).ethernet=false;
            add(eye,"eth1","169.254.1.10/24","169.254.1.0/24");
            add(glasses,"eth0","169.254.2.10/24","169.254.2.0/24");
        }
        void add(Network n,String iface,String address,String prefix)throws Exception {
            LinkProperties link=new LinkProperties();link.iface=iface;link.addresses.add(new LinkAddress(address));
            link.routes.add(new RouteInfo(prefix,iface));cm.links.put(n,link);cm.caps.put(n,new NetworkCapabilities());
        }
        public void onPingResult(boolean ready){calls++;result=ready;socketNetwork=cm.bound;
            if(nativeStartup!=null)nativeStartup.run();if(throwCallback)throw new IllegalStateException("native startup failure");}
        void deliver(boolean ok)throws Exception {GlassesNetworkCompat.deliver(PingCallback.class.getMethod("onPingResult",boolean.class),this,ok);}
    }
    public static void main(String[] args)throws Exception {
        Setup s=new Setup();s.deliver(true);
        check(s.calls==1&&s.result&&s.glasses.equals(s.socketNetwork),"Native startup selects Ethernet instead of default Wi-Fi");
        check(s.cm.bound==null&&s.cm.bindCalls==2,"Default process routing restored after startup");
        s=new Setup();s.cm.bound=s.wifi;s.deliver(true);check(s.cm.bound.equals(s.wifi),"Restore explicit prior Wi-Fi binding");
        s=new Setup();s.cm.bound=s.glasses;s.deliver(true);check(s.cm.bindCalls==0&&s.cm.bound.equals(s.glasses),"Preserve a pre-existing glasses binding");
        s=new Setup();s.deliver(false);check(s.calls==1&&!s.result&&s.cm.bindCalls==0,"Failed readiness never binds or starts native SDK");
        s=new Setup();GlassesInitSetting.ethernet=false;s.context.usb.devices.clear();s.deliver(true);
        check(s.calls==1&&s.result&&s.cm.bindCalls==0,"Air/H-series bypass networking and preserve native startup");
        s=new Setup();s.cm.reject=s.glasses;s.deliver(true);check(s.calls==1&&!s.result&&s.cm.bound==null,"Rejected Android network cannot report successful readiness");
        s=new Setup();s.cm.throwOn=s.glasses;s.deliver(true);check(s.calls==1&&!s.result&&s.cm.bound==null,"Permission exception fails before native startup");
        s=new Setup();s.throwCallback=true;try{s.deliver(true);throw new AssertionError("Expected native exception");}catch(InvocationTargetException expected){}
        check(s.calls==1&&s.cm.bound==null,"Native exception restores routing without duplicate callback");
        s=new Setup();s.context.usb.devices.clear();s.deliver(true);check(!s.result&&s.cm.bindCalls==0,"Detached glasses cannot authorize binding");
        s=new Setup();s.context.usb.granted=false;s.deliver(true);check(!s.result&&s.cm.bindCalls==0,"USB permission required");
        s=new Setup();s.context.usb.devices.put("second",new UsbDevice("second",13080,1078,14));s.deliver(true);
        check(!s.result&&s.cm.bindCalls==0,"Ambiguous USB devices are rejected");
        s=new Setup();final Setup swap=s;s.cm.afterBind=()->swap.context.usb.devices.put("glasses",new UsbDevice("glasses",13080,1078,13));s.deliver(true);
        check(!s.result&&s.cm.bound==null,"USB re-enumeration after bind stops native startup and restores routing");
        s=new Setup();s.cm.links.remove(s.glasses);s.deliver(true);check(!s.result&&s.cm.bindCalls==0,"Wi-Fi, cellular and eth1 cannot substitute for the control network");
        s=new Setup();s.cm.caps.get(s.glasses).unrestricted=false;s.deliver(true);check(!s.result,"Restricted Ethernet rejected");
        s=new Setup();s.cm.links.get(s.glasses).routes.clear();s.deliver(true);check(!s.result,"Matching address alone is insufficient");
        s=new Setup();s.cm.links.get(s.glasses).routes.get(0).gateway=true;s.deliver(true);check(!s.result,"Peer route must be direct");
        s=new Setup();s.cm.links.get(s.glasses).routes.get(0).iface="wlan0";s.deliver(true);check(!s.result,"Route must belong to the matching Ethernet interface");
        s=new Setup();s.add(new Network(104),"eth2","169.254.2.10/24","169.254.2.0/24");s.deliver(true);
        check(!s.result&&s.cm.bindCalls==0,"Ambiguous control networks are rejected");
        s=new Setup();s.cm.links.remove(s.glasses);final Setup delayed=s;
        SystemClock.sleepHook=()->{try{delayed.add(delayed.glasses,"eth0","169.254.2.10/24","169.254.2.0/24");}catch(Exception e){throw new RuntimeException(e);}};
        s.deliver(true);check(s.result&&s.cm.bound==null,"Wait for Android publication after USB reconfiguration");
        s=new Setup();s.cm.links.remove(s.glasses);final Setup detached=s;SystemClock.sleepHook=()->detached.context.usb.devices.clear();
        s.deliver(true);check(!s.result&&SystemClock.now==100,"USB detach ends network wait promptly");
        s=new Setup();s.cm.bound=s.wifi;final Setup lost=s;s.nativeStartup=()->lost.cm.reject=lost.wifi;s.deliver(true);
        check(s.result&&s.cm.bound==null,"Lost prior network falls back to default instead of leaving Ethernet bound");
        s=new Setup();s.cm.bound=s.wifi;final Setup revoked=s;s.nativeStartup=()->revoked.cm.throwOn=revoked.wifi;s.deliver(true);
        check(s.result&&s.cm.bound==null,"Revoked prior network permission also clears the temporary binding");
        s=new Setup();final Setup other=s;s.nativeStartup=()->other.cm.bound=other.data;s.deliver(true);
        check(s.cm.bound.equals(s.data),"Do not overwrite another owner's new process binding");
        s=new Setup();s.deliver(true);s.cm.links.remove(s.glasses);s.glasses=new Network(200);s.add(s.glasses,"eth0","169.254.2.10/24","169.254.2.0/24");
        s.deliver(true);check(s.calls==2&&s.socketNetwork.equals(s.glasses)&&s.cm.bound==null,"Reconnect selects a fresh Android network without stale IDs");
        s=new Setup();Helper helper=new Helper();PingCallback guarded=(PingCallback)PingCallbackCompat.wrap(helper,s);
        guarded.onPingResult(true);guarded.onPingResult(true);PingCallbackCompat.released(helper);
        check(s.calls==1&&s.glasses.equals(s.socketNetwork)&&s.cm.bound==null,"Production ping guard invokes scoped networking once");
        System.out.println("PASS glasses control-network assertions: "+checks);
    }
}
