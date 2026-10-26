package dev.beampro.port;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Keep the SDK callback, but obtain XREAL access before Android can open its USB dialog. */
public final class UsbPermissionCompat {
    private static final String TAG="UnboundUSB";
    private static final String PACKAGE="com.xreal.evapro.nebula";
    private static final Map<Key,Request> pending=new HashMap<>();
    private static volatile Context application;
    static ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();

    public static void install(Context context) {
        Context app=context.getApplicationContext();application=app==null?context:app;
    }

    /** Low-level SDK callers also run from provider initialization before Application.onCreate. */
    public static void requestPermission(UsbManager manager, UsbDevice device, PendingIntent callback) {
        Context context=application;
        if (context==null && device!=null && device.getVendorId()==13080 && PACKAGE.equals(callback.getCreatorPackage())) {
            worker.schedule(()->requestPermission(manager,device,callback),100,TimeUnit.MILLISECONDS);
            return;
        }
        if (context==null) { manager.requestPermission(device,callback);return; }
        requestPermission(context,manager,device,callback);
    }

    private static final class Key {
        final String name;final PendingIntent callback;
        Key(UsbDevice device,PendingIntent callback){name=device.getDeviceName();this.callback=callback;}
        public int hashCode(){return 31*name.hashCode()+callback.hashCode();}
        public boolean equals(Object value){return value instanceof Key && name.equals(((Key)value).name)&&callback.equals(((Key)value).callback);}
    }

    public static void requestPermission(Context context, UsbManager manager, UsbDevice device, PendingIntent callback) {
        if (device==null || device.getVendorId()!=13080 || !PACKAGE.equals(context.getPackageName())
                || !PACKAGE.equals(callback.getCreatorPackage())) {
            manager.requestPermission(device, callback);
            return;
        }
        Context app=context.getApplicationContext();
        Request request=new Request(app==null?context:app, manager, device, callback);
        synchronized (pending) { pending.put(request.key, request); }
        worker.execute(request);
    }

    private static final class Request implements Runnable {
        final Context context;
        final UsbManager manager;
        final UsbDevice device;
        final PendingIntent callback;
        final Key key;
        boolean waitingLogged;
        Request(Context context, UsbManager manager, UsbDevice device, PendingIntent callback) {
            this.context=context;this.manager=manager;this.device=device;this.callback=callback;
            this.key=new Key(device,callback);
        }
        boolean current() { synchronized (pending) { return pending.get(key)==this; } }
        boolean remove() {
            synchronized (pending) {
                if (pending.get(key)!=this) return false;
                pending.remove(key);return true;
            }
        }
        void retry() { if (current()) worker.schedule(this, 1, TimeUnit.SECONDS); }
        void respond(UsbDevice result, boolean granted) {
            if (!remove()) return;
            try { callback.send(context, 0, new Intent().putExtra(UsbManager.EXTRA_DEVICE, result)
                    .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, granted)); }
            catch (PendingIntent.CanceledException ignored) { }
        }
        void denied() { respond(device, false); }
        public void run() {
            if (!current()) return;
            try {
                UsbDevice live=manager.getDeviceList().get(device.getDeviceName());
                if (live==null || live.getVendorId()!=device.getVendorId() || live.getProductId()!=device.getProductId()
                        || live.getDeviceId()!=device.getDeviceId()) { denied();return; }
                if (!manager.hasPermission(live)) {
                    Bundle bundle=context.getContentResolver().call(Uri.parse("content://dev.beampro.portprobe.bridge"), "get", null, null);
                    IBinder remote=bundle==null?null:bundle.getBinder("binder");
                    if (remote==null || !remote.isBinderAlive()) {
                        if (!waitingLogged) { Log.i(TAG, "Waiting for on-device ADB setup before XREAL USB access");waitingLogged=true; }
                        retry();return;
                    }
                    Parcel data=Parcel.obtain(),reply=Parcel.obtain();
                    try {
                        data.writeInterfaceToken("dev.beampro.bridge.XREAL_USB");
                        data.writeString(live.getDeviceName());data.writeInt(live.getVendorId());
                        data.writeInt(live.getProductId());data.writeInt(live.getDeviceId());
                        if (!remote.transact(0x00FFFFF8, data, reply, 0)) throw new IllegalStateException("Update and restart the on-device ADB helper");
                        reply.readException();
                        String error=reply.readString();
                        boolean granted=reply.readInt()!=0;
                        if (error!=null) throw new IllegalStateException(error);
                        if (!granted) { denied();return; }
                    } finally { data.recycle();reply.recycle(); }
                    // Android still applies camera/microphone privacy and app permission
                    // checks. Never open the system dialog if the grant was ineffective.
                    if (!manager.hasPermission(live)) throw new IllegalStateException("Android did not enable XREAL USB access; check app permissions and reconnect");
                }
                // Match Android's already-authorized PendingIntent result after readback.
                // Calling requestPermission again would reopen a dialog if a detach or
                // revocation raced between the readback and that second framework call.
                respond(live, true);
                Log.i(TAG, "XREAL USB access ready without a system dialog");
            } catch (RemoteException restarting) { retry(); }
            catch (Exception error) {
                Log.e(TAG, "XREAL USB access failed; reconnect after starting on-device ADB", error);
                denied();
            }
        }
    }
}
