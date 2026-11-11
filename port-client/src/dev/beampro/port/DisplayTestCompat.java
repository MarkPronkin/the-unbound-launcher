package dev.beampro.port;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import dev.beampro.compat.GlassesIdentity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Explicit display experiments using the control service, without XREALManager's AR launch path. */
public final class DisplayTestCompat {
    private static final String TAG="UnboundDisplayTest";
    private static final ExecutorService PREPARE=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,TAG);t.setDaemon(true);return t;});
    private static Session current;

    public static synchronized Object reserve(Context context) throws Exception {
        if(current!=null||!GlassesConnectionCompat.displayTestsAvailable())
            throw new IllegalStateException("Close AR Space or cancel the connection before running display tests.");
        current=new Session(context.getApplicationContext());return current;
    }
    public static void start(Object token,Consumer<Bundle> callback) { ((Session)token).start(callback); }
    public static void mode(Object token,boolean stereo,Consumer<Bundle> callback) { ((Session)token).mode(stereo,callback); }
    public static void finish(Object token,Consumer<Bundle> callback) { ((Session)token).finish(callback); }
    public static boolean attached(Object token) { return ((Session)token).connected(); }
    public static void cancelAutomaticEntry(Context context) throws Exception {
        UsbDevice device=singleDevice(context.getSystemService(UsbManager.class));if(device==null)return;
        Bundle connection=context.getContentResolver().call(Uri.parse("content://dev.beampro.portprobe.bridge"),"get",null,null);
        IBinder binder=connection==null?null:connection.getBinder("binder");if(binder==null||!binder.isBinderAlive())return;
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{
            data.writeInterfaceToken("dev.beampro.bridge.AUTO_AR");data.writeInt(0);data.writeString(device.getDeviceName());
            data.writeInt(device.getVendorId());data.writeInt(device.getProductId());data.writeInt(device.getDeviceId());
            if(binder.transact(0x00FFFFF7,data,reply,0)){reply.readException();String error=reply.readString();if(error!=null)throw new IllegalStateException(error);}
        }finally{data.recycle();reply.recycle();}
    }

    private static Object get(Object owner,String name) throws Exception {
        for(Class<?> c=owner.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
        catch(NoSuchFieldException absent){}
        throw new NoSuchFieldException(name);
    }
    private static Object call(Object owner,String name,Class<?>[] types,Object... args) throws Exception {
        Method method=owner.getClass().getMethod(name,types);return method.invoke(owner,args);
    }
    private static Bundle result(boolean ok,String message,int mode) {
        Bundle out=new Bundle();out.putBoolean("ok",ok);out.putString("message",message);out.putInt("mode",mode);return out;
    }
    private static String message(Throwable failure) {
        Throwable cause=failure.getCause()==null?failure:failure.getCause();
        Log.w(TAG,"Display test control request failed",cause);
        return cause.getMessage()==null?"Glasses control failed. Reconnect the glasses and try again.":cause.getMessage();
    }
    private static final class Session {
        final Context context;
        final AtomicBoolean delivered=new AtomicBoolean();
        volatile boolean closing,preparing,waiting,prepared,ownsSdk,closeQueued,closed;
        volatile Consumer<Bundle> closeCallback;
        UsbDevice device;UsbManager usb;Object sdk;Handler handler;boolean ethernet;String firmwareVersion="";
        Session(Context context){this.context=context;}
        synchronized void start(Consumer<Bundle> callback) {
            if(preparing||waiting||prepared||delivered.get()||closed||closing){callback.accept(result(false,"The display test has already started or stopped",-1));return;}
            preparing=true;
            PREPARE.execute(()->{
                try {
                    usb=context.getSystemService(UsbManager.class);device=singleDevice(usb);
                    if(device==null)throw new IllegalStateException("Connect one pair of supported XREAL glasses.");
                    requirePermission();
                    Object manager=Class.forName("ai.nreal.nebula.manager.XREALManager").getMethod("getInstance").invoke(null);
                    int type=((Number)get(manager,"mGlassesType")).intValue();
                    if(type!=2&&type!=3&&type!=4&&type!=5&&type!=6&&type!=7&&type!=9&&type!=20)
                        throw new IllegalStateException("Automatic mode tests are unavailable for this glasses model. The connection check still works.");
                    requireDevice();if(closing){preparing=false;closeIfPossible();return;}
                    call(manager,"setOtaManagerGlassesType",new Class<?>[0]);
                    Object reader=get(manager,"mOtaManager");
                    int policy=GlassesIdentity.rogR1Usb(device.getVendorId(),device.getProductId())?1003:type;
                    FirmwarePolicy.Result firmware=FirmwarePolicy.check(policy,component->{
                        requireDevice();String value=(String)call(reader,"readFWVersion",new Class<?>[]{int.class},component);requireDevice();return value;
                    });
                    if(!firmware.supported)throw new IllegalStateException("Firmware verification paused the test: "+firmware.detail);
                    firmwareVersion=firmware.detected;
                    if(type==6||type==20){
                        String sku=(String)call(reader,"getSku",new Class<?>[0]);
                        if(sku!=null&&!sku.isEmpty())Class.forName("com.xreal.basicfunc.BasicFuncManager")
                                .getMethod("setGlassesType",String.class).invoke(null,type+"_"+sku);
                    }
                    sdk=get(manager,"mNRServiceControl");handler=(Handler)get(get(sdk,"mPingHelper"),"mSdkHandler");
                    requireDevice();
                    if(closing){preparing=false;closeIfPossible();return;}
                    if(Boolean.TRUE.equals(get(sdk,"isStartSdk"))){preparing=false;started(true,callback);return;}
                    ownsSdk=true;waiting=true;
                    Class<?> api=Class.forName("com.xreal.glasses.servicesdk.NRServiceControl$OnStartSDKCallback");
                    Object proxy=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(p,m,args)->{
                        if(m.getName().equals("onStartSDKResult")){waiting=false;started(Boolean.TRUE.equals(args[0]),callback);return null;}
                        if(m.getName().equals("toString"))return TAG;
                        if(m.getName().equals("hashCode"))return System.identityHashCode(p);
                        if(m.getName().equals("equals"))return p==args[0];
                        return null;
                    });
                    preparing=false;
                    // This initializes only the native control client. It does not select an EDID or launch Unity.
                    call(sdk,"startBackgroundSdk",new Class<?>[]{api},proxy);
                } catch(Exception|LinkageError failure){preparing=waiting=false;if(delivered.compareAndSet(false,true))callback.accept(result(false,message(failure),-1));closeIfPossible();}
            });
        }
        private void started(boolean ok,Consumer<Bundle> callback) {
            if(!delivered.compareAndSet(false,true))return;
            try {
                ethernet=Boolean.TRUE.equals(get(sdk,"isEthernetSeries"));prepared=ok;
                callback.accept(result(ok,ok?"Firmware "+firmwareVersion+" accepted; glasses control ready ("+(ethernet?"Ethernet EDID":"legacy display mode")+")":"Glasses control could not start. Reconnect and try again.",-1));
            } catch(Exception failure){callback.accept(result(false,message(failure),-1));}
            closeIfPossible();
        }
        void mode(boolean stereo,Consumer<Bundle> callback) {
            final int mode=stereo?(ethernet?5:3):1;
            Handler worker=handler;
            if(!prepared||closing||worker==null){callback.accept(result(false,"Glasses control is not ready",mode));return;}
            if(!worker.post(()->{
                try {
                    if(closing)throw new IllegalStateException("The display test has stopped");
                    boolean ok=setMode(mode);
                    callback.accept(result(ok,ok?(ethernet?"Ethernet EDID ":"Legacy mode ")+mode+" returned true"
                            :"The glasses rejected the "+(stereo?"SBS":"2D")+" display request",mode));
                } catch(Exception|LinkageError failure){callback.accept(result(false,message(failure),mode));}
            }))callback.accept(result(false,"The glasses control worker stopped. Reconnect the glasses.",mode));
        }
        private boolean setMode(int mode) throws Exception {
            requireDevice();
            GlassesNetworkCompat.Lease lease=GlassesNetworkCompat.select(context);
            try {
                requireDevice();
                boolean ok=Boolean.TRUE.equals(call(sdk,"set2D3D",new Class<?>[]{int.class},mode));
                requireDevice();return ok;
            } finally {if(lease!=null)lease.close();}
        }
        synchronized void finish(Consumer<Bundle> callback) {
            if(closed){callback.accept(result(true,"Display test is closed",1));return;}
            if(closeCallback!=null)return;
            closing=true;closeCallback=callback;closeIfPossible();
        }
        private synchronized void closeIfPossible() {
            if(!closing||closed||closeQueued||preparing||waiting)return;
            closeQueued=true;
            if(handler==null){released(result(true,"Test closed before mode changes",1));return;}
            if(!handler.post(()->{
                Bundle restored;
                try {
                    boolean ready=Boolean.TRUE.equals(get(sdk,"isStartSdk"));
                    boolean ok=ready?setMode(1):!prepared;
                    restored=result(ok,ready?"Return to 2D (mode 1) returned "+ok:"Control startup did not change the display mode",1);
                } catch(Exception|LinkageError failure){restored=result(false,message(failure),1);}
                try {if(ownsSdk)call(sdk,"stopSdk",new Class<?>[0]);}
                catch(Exception|LinkageError failure){restored=result(false,message(failure),1);}
                Bundle answer=restored;
                // stopSdk queues its native teardown on this same worker. Release only after that barrier.
                if(!handler.post(()->released(answer)))released(result(false,"Glasses control stopped during cleanup. Reconnect the glasses.",1));
            }))released(result(false,"The glasses control worker stopped. Reconnect the glasses.",1));
        }
        private void released(Bundle answer) {
            synchronized(DisplayTestCompat.class){if(current==this)current=null;closed=true;}
            Consumer<Bundle> callback=closeCallback;if(callback!=null)callback.accept(answer);
        }
        boolean sameDevice() {
            return device!=null&&connected()&&usb.hasPermission(device);
        }
        boolean connected() {
            if(device==null)return true; // Preparation has not selected an attachment yet.
            if(usb==null)return false;
            UsbDevice live=singleDevice(usb);
            return live!=null&&live.getDeviceName().equals(device.getDeviceName())&&live.getDeviceId()==device.getDeviceId()
                    &&live.getVendorId()==device.getVendorId()&&live.getProductId()==device.getProductId();
        }
        private void requireDevice() {if(!sameDevice())throw new IllegalStateException("The glasses disconnected or changed. Reconnect them before another test.");}
        private void requirePermission() throws Exception {
            if(usb.hasPermission(device))return;
            Bundle connection=context.getContentResolver().call(Uri.parse("content://dev.beampro.portprobe.bridge"),"get",null,null);
            IBinder binder=connection==null?null:connection.getBinder("binder");
            if(binder==null||!binder.isBinderAlive())throw new IllegalStateException("Start the on-device ADB helper, then try again.");
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try {
                data.writeInterfaceToken("dev.beampro.bridge.XREAL_USB");data.writeString(device.getDeviceName());
                data.writeInt(device.getVendorId());data.writeInt(device.getProductId());data.writeInt(device.getDeviceId());
                if(!binder.transact(0x00FFFFF8,data,reply,0))throw new IllegalStateException("Restart the updated on-device ADB helper.");
                reply.readException();String error=reply.readString();boolean granted=reply.readInt()!=0;
                if(error!=null||!granted)throw new IllegalStateException(error==null?"Allow USB access to the glasses, then try again.":error);
            } finally {data.recycle();reply.recycle();}
            requireDevice();
        }
    }
    private static UsbDevice singleDevice(UsbManager usb) {
        if(usb==null)return null;UsbDevice found=null;
        for(UsbDevice device:usb.getDeviceList().values())if(GlassesIdentity.usb(device.getVendorId(),device.getProductId())){
            if(found!=null)return null;found=device;
        }
        return found;
    }
    private DisplayTestCompat() {}
}
