package dev.beampro.port;

import dev.beampro.compat.GlassesIdentity;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.util.concurrent.*;

/** Replace only the SDK's unprivileged eth0 ping; preserve its polling and native startup. */
public final class EthernetReadinessCompat {
    static final int TRANSACTION = 0x00FFFFF4;
    static final String TOKEN = "dev.beampro.bridge.GLASSES_PING";
    private static final String TAG = "UnboundEthernet";
    interface Probe { boolean attached(); Reply ping() throws Exception; }
    interface Factory { Probe create() throws Exception; }
    static Factory factory = AndroidProbe::new;
    static final class Reply {
        final int exit; final boolean timedOut; final String output;
        Reply(int exit, boolean timedOut, String output) { this.exit=exit; this.timedOut=timedOut; this.output=output; }
        boolean ready() { return exit==0 && !timedOut; }
    }
    private static final ThreadPoolExecutor remote = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new SynchronousQueue<Runnable>(), job -> {
                Thread thread=new Thread(job,"unbound-glasses-ping");thread.setDaemon(true);return thread;
            });

    /** Called from the original SDK ping Runnable, on its original SDK worker. */
    public static boolean ping(Object runnable) {
        Future<Reply> call = null;
        try {
            Object helper = GlassesConnectionCompat.get(runnable,"this$0");
            Object ticket = PingCallbackCompat.ticket(helper);
            if (ticket==null) return false;
            Probe probe = factory.create();
            if (!probe.attached() || !PingCallbackCompat.active(helper,ticket)) return false;
            call = remote.submit(probe::ping);
            long deadline = SystemClock.uptimeMillis()+4000;
            while (PingCallbackCompat.active(helper,ticket) && probe.attached()) {
                long remaining=deadline-SystemClock.uptimeMillis();
                if (remaining<=0) throw new IOException("Glasses readiness helper timed out");
                try {
                    Reply reply=call.get(Math.min(100,remaining),TimeUnit.MILLISECONDS);
                    boolean ready=reply.ready() && probe.attached() && PingCallbackCompat.active(helper,ticket);
                    Log.i(TAG,"Glasses readiness via shell helper: exit="+reply.exit+"; timedOut="+reply.timedOut
                            +"; ready="+ready+"\n"+reply.output);
                    return ready;
                } catch (TimeoutException stillRunning) { /* Check cancellation before waiting again. */ }
            }
            Log.i(TAG,"Discarded glasses ping after cancellation or USB detach");
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.w(TAG,"Cannot check glasses readiness; start the updated helper under On-device ADB",error);
        } finally { if (call!=null) call.cancel(true); }
        return false;
    }

    static final class AndroidProbe implements Probe {
        final Context context;
        final UsbManager usb;
        final UsbDevice device;
        AndroidProbe() throws Exception {
            Class<?> init=Class.forName("com.xreal.glassesdisplayplugevent.GlassesInitSetting");
            context=(Context)init.getMethod("getContext").invoke(init.getMethod("getInstance").invoke(null));
            if (context==null) throw new IOException("Glasses context unavailable");
            usb=context.getSystemService(UsbManager.class);
            UsbDevice found=null;
            if (usb!=null) for (UsbDevice value:usb.getDeviceList().values()) {
                if(!GlassesIdentity.usb(value.getVendorId(),value.getProductId()) || !usb.hasPermission(value)) continue;
                if(found!=null) throw new IOException("More than one authorized XREAL USB device");
                found=value;
            }
            device=found;
        }
        public boolean attached() {
            if(device==null) return false;
            UsbDevice live=usb.getDeviceList().get(device.getDeviceName());
            return live!=null && live.getVendorId()==device.getVendorId() && live.getProductId()==device.getProductId()
                    && live.getDeviceId()==device.getDeviceId() && usb.hasPermission(live);
        }
        public Reply ping() throws Exception {
            Bundle bundle=context.getContentResolver().call(Uri.parse("content://dev.beampro.portprobe.bridge"),"get",null,null);
            IBinder binder=bundle==null?null:bundle.getBinder("binder");
            if(binder==null || !binder.isBinderAlive()) throw new IOException("On-device ADB helper is not running");
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try {
                data.writeInterfaceToken(TOKEN);
                data.writeString(device.getDeviceName());data.writeInt(device.getVendorId());
                data.writeInt(device.getProductId());data.writeInt(device.getDeviceId());
                if(!binder.transact(TRANSACTION,data,reply,0)) throw new IOException("Restart the updated on-device ADB helper");
                reply.readException();
                int exit=reply.readInt();boolean timeout=reply.readInt()!=0;String output=reply.readString();
                if(output==null || output.length()>2048) throw new IOException("Invalid glasses readiness reply");
                return new Reply(exit,timeout,output);
            } finally {data.recycle();reply.recycle();}
        }
    }
    private EthernetReadinessCompat() { }
}
