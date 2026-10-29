package dev.beampro.adb;

import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import dev.beampro.port.GlassesConnectionCompat;
import java.lang.reflect.*;
import java.util.concurrent.*;

public final class AutoGlassesReceiverTest {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static class Remote implements IBinder {
        int attaches,detaches;
        public boolean isBinderAlive(){return true;}
        public boolean transact(int code,Parcel data,Parcel reply,int flags){
            check(code==0x00FFFFF7&&data.readString().equals("dev.beampro.bridge.AUTO_AR"),"Fixed auto-entry endpoint");
            if(data.readInt()==1)attaches++;else detaches++;reply.writeString(null);return true;
        }
    }
    static void idle(ExecutorService work)throws Exception{work.submit(()->{}).get(5,TimeUnit.SECONDS);}
    public static void main(String[] args)throws Exception {
        Field field=AutoGlassesReceiver.class.getDeclaredField("WORK");field.setAccessible(true);
        ExecutorService work=(ExecutorService)field.get(null);
        try {
            Context context=new Context();Remote remote=new Remote();context.resolver.remote=remote;
            context.getSharedPreferences("unbound-adb",0).put("setup-complete",true);
            UsbDevice glasses=new UsbDevice("one",13080,1080,42);context.usb.devices.put("one",glasses);
            AutoGlassesReceiver receiver=new AutoGlassesReceiver();
            Intent attach=new Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE,glasses);
            Intent detach=new Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE,glasses);
            GlassesConnectionCompat.mirroring=true;AdbBootstrap.ready=false;
            receiver.onReceive(context,attach);idle(work);
            check(remote.attaches==0&&AdbBootstrap.connects==0,"Mirroring attach starts neither AR nor helper bootstrap");
            check(context.getSharedPreferences("unbound-auto-entry",0).isEmpty(),"Mirroring attach records no pending AR connection");
            AutoGlassesReceiver.helperReady(context);idle(work);
            check(remote.attaches==0,"Helper recovery respects mirroring with glasses already attached");
            // Toggle after work has been queued; dispatch must re-read the preference.
            GlassesConnectionCompat.mirroring=false;AdbBootstrap.ready=true;
            CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);
            work.submit(()->{queued.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}});
            queued.await(5,TimeUnit.SECONDS);receiver.onReceive(context,attach);
            GlassesConnectionCompat.mirroring=true;release.countDown();idle(work);
            check(remote.attaches==0,"Queued auto-entry cannot override a later mirroring choice");
            receiver.onReceive(context,detach);idle(work);
            check(remote.detaches==1,"Mirroring still delivers detach cancellation to the helper");
            GlassesConnectionCompat.mirroring=false;receiver.onReceive(context,attach);idle(work);
            check(remote.attaches==1,"Disabling mirroring preserves automatic AR entry");
            AutoGlassesReceiver.helperReady(context);idle(work);
            check(remote.attaches==2,"AR helper-ready recovery still dispatches the connected glasses");
            GlassesConnectionCompat.active=true;receiver.onReceive(context,attach);AutoGlassesReceiver.helperReady(context);idle(work);
            check(remote.attaches==2,"Active sessions suppress duplicate AR entry");GlassesConnectionCompat.active=false;
            GlassesConnectionCompat.failRead=true;receiver.onReceive(context,attach);AutoGlassesReceiver.helperReady(context);idle(work);
            check(remote.attaches==2,"Unreadable preference cannot trigger unsolicited AR startup");GlassesConnectionCompat.failRead=false;
            receiver.onReceive(context,new Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    .putExtra(UsbManager.EXTRA_DEVICE,new UsbDevice("monitor",1234,1080,43)));idle(work);
            check(remote.attaches==2,"Other USB devices do not trigger AR entry");
            System.out.println("PASS automatic mirroring preference assertions: "+checks);
        } finally {work.shutdownNow();}
    }
}
