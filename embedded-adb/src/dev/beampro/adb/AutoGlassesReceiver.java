package dev.beampro.adb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.provider.Settings;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** USB broadcasts remain available when the app is closed; no USB handler Activity is advertised. */
public final class AutoGlassesReceiver extends BroadcastReceiver {
    private static final String PACKAGE="dev.markpronkin.unbound";
    private static final String TAG="UnboundAutoAR";
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor();
    private static SharedPreferences state(Context context) { return context.getSharedPreferences("unbound-auto-entry",0); }
    private static boolean enabled(Context context) {
        return PACKAGE.equals(context.getPackageName())
            && context.getSharedPreferences("unbound-adb",0).getBoolean("setup-complete",false)
            && dev.beampro.settings.TermsConsent.hasAccepted(context)
            && Settings.canDrawOverlays(context)
            && arEnabled(context);
    }
    private static boolean arEnabled(Context context) {
        try {
            boolean mirroring=(Boolean)Class.forName("dev.beampro.port.GlassesConnectionCompat")
                .getMethod("startInScreenMirroring",Context.class).invoke(null,context);
            if(mirroring)Log.i(TAG,"Screen mirroring selected; skipping automatic AR entry");
            return !mirroring;
        } catch(Exception error) { Log.w(TAG,"Cannot read the connection preference; skipping automatic AR entry",error);return false; }
    }
    private static boolean glasses(UsbDevice device) {
        if(device==null)return false;
        try {
            Class<?> identity=Class.forName("dev.beampro.compat.GlassesIdentity");
            if(!(Boolean)identity.getMethod("usb",int.class,int.class).invoke(null,device.getVendorId(),device.getProductId()))return false;
            Class<?> models=Class.forName("com.xreal.glassesdisplayplugevent.helper.GlassesTypeUtil");
            int model=(Integer)models.getMethod("checkGlassesType",UsbDevice.class).invoke(null,device);
            return model>1;
        } catch(Exception error) { Log.w(TAG,"Cannot identify glasses model",error);return false; }
    }
    private static boolean sessionActive() {
        try { return (Boolean)Class.forName("dev.beampro.port.GlassesConnectionCompat").getMethod("sessionActive").invoke(null); }
        catch(Exception absent) { return false; }
    }
    @Override public void onReceive(Context source,Intent intent) {
        if(!PACKAGE.equals(source.getPackageName()))return;
        String action=intent.getAction();
        if(!UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)&&!UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action))return;
        UsbDevice device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if(!glasses(device))return;
        Context context=source.getApplicationContext();
        boolean attached=UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action);
        if(attached && (!enabled(context)||sessionActive()))return;
        if(attached)state(context).edit().putString("device",device.getDeviceName()).putInt("product",device.getProductId())
            .putInt("id",device.getDeviceId()).apply();
        else if(device.getDeviceName().equals(state(context).getString("device",null)))state(context).edit().clear().apply();
        PendingResult result=goAsync();
        WORK.execute(()->{
            try {
                if(attached && !AdbBootstrap.ready(context)) {
                    if(AdbBootstrap.usesShizuku(context)||AdbBootstrap.paired(context))AdbBootstrap.connect(context,0,null);
                    return; // The bootstrap calls helperReady once its Binder is available.
                }
                dispatch(context,device,attached);
            } finally { result.finish(); }
        });
    }
    /** Resume a physical attachment after helper startup or after setup finishes. */
    public static void helperReady(Context source) {
        Context context=source.getApplicationContext();
        if(!enabled(context))return;
        WORK.execute(()->{
            try {
                if(sessionActive())return;
                UsbManager usb=context.getSystemService(UsbManager.class);
                if(usb!=null)for(UsbDevice device:usb.getDeviceList().values())if(glasses(device)) { dispatch(context,device,true);break; }
            } catch(Exception error) { Log.e(TAG,"Cannot resume automatic entry after helper startup",error); }
        });
    }
    private static void dispatch(Context context,UsbDevice device,boolean attached) {
        if(attached && (!enabled(context)||sessionActive()))return;
        try {
            Bundle result=context.getContentResolver().call(Uri.parse("content://"+AdbBootstrap.AUTHORITY),"get",null,null);
            IBinder binder=result==null?null:result.getBinder("binder");
            if(binder==null||!binder.isBinderAlive())return;
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try {
                data.writeInterfaceToken("dev.beampro.bridge.AUTO_AR");data.writeInt(attached?1:0);
                data.writeString(device.getDeviceName());data.writeInt(device.getVendorId());data.writeInt(device.getProductId());data.writeInt(device.getDeviceId());
                if(!binder.transact(0x00FFFFF7,data,reply,0))throw new IllegalStateException("Restart the updated on-device helper");
                reply.readException();String error=reply.readString();if(error!=null)throw new IllegalStateException(error);
                Log.i(TAG,attached?"Automatic entry queued for connected glasses":"Automatic entry cancelled on USB detach");
            } finally { data.recycle();reply.recycle(); }
        } catch(Exception error) { Log.e(TAG,"Automatic glasses entry failed",error); }
    }
}
