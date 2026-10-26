package dev.beampro.bridge;

import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.IBinder;
import java.lang.reflect.Method;
import java.io.IOException;

/** Per-connection USB access for the launcher, never a caller-selected package or UID. */
final class XrealUsbPermissions {
    static final int TRANSACTION = 0x00FFFFF8;
    static final String TOKEN = "dev.beampro.bridge.XREAL_USB";
    static final String PACKAGE = "com.xreal.evapro.nebula";
    static final int VENDOR = 13080;
    private final PackageManager packages;
    private final int launcherUid;
    private Object service;
    private Class<?> api;

    XrealUsbPermissions(PackageManager packages, int launcherUid) {
        this.packages=packages; this.launcherUid=launcherUid;
    }

    XrealUsbPermissions(PackageManager packages, int launcherUid, Object service, Class<?> api) {
        this(packages, launcherUid);this.service=service;this.api=api;
    }

    boolean grant(int caller, String name, int vendor, int product, int deviceId) throws Exception {
        if (caller!=launcherUid || packages.getApplicationInfo(PACKAGE, 0).uid!=launcherUid)
            throw new SecurityException("USB access is restricted to the installed launcher");
        if (name==null || vendor!=VENDOR) throw new SecurityException("Only XREAL USB devices are eligible");
        if (service==null) {
            // createShellContext() uses ActivityThread.systemMain(). Android 17 then
            // treats this process as system_server when unparcelling UsbDevice and
            // rejects its remote serial-number reader. Never decode USB here.
            ShellCommand.Result result=ShellCommand.run(10000, "/system/bin/app_process", "/", Worker.class.getName(),
                    Integer.toString(launcherUid), name, Integer.toString(vendor), Integer.toString(product), Integer.toString(deviceId));
            if (!result.succeeded()) throw new IOException("XREAL USB worker failed: "+result.output);
            if (result.output.trim().endsWith("UNBOUND_USB_GRANTED")) return true;
            if (result.output.trim().endsWith("UNBOUND_USB_ABSENT")) return false;
            throw new IOException("XREAL USB worker returned no result");
        }
        return grantOnService(service, api, launcherUid, name, vendor, product, deviceId);
    }

    static boolean grantOnService(Object service, Class<?> api, int launcherUid, String name, int vendor, int product, int deviceId) throws Exception {
        if (vendor!=VENDOR) throw new SecurityException("Only XREAL USB devices are eligible");
        Bundle devices=new Bundle();
        api.getMethod("getDeviceList", Bundle.class).invoke(service, devices);
        Object value=devices.get(name);
        if (!(value instanceof UsbDevice)) return false;
        UsbDevice device=(UsbDevice)value;
        if (!name.equals(device.getDeviceName()) || device.getVendorId()!=VENDOR
                || device.getProductId()!=product || device.getDeviceId()!=deviceId) return false;
        Method modern;
        try { modern=api.getMethod("grantDevicePermission", UsbDevice.class, String.class, int.class, boolean.class); }
        catch (NoSuchMethodException olderAndroid) { modern=null; }
        // Pixel Android 17 adds package and persistence arguments. A rejected call
        // must not be retried through another signature. Both paths are temporary.
        if (modern!=null) modern.invoke(service, device, PACKAGE, launcherUid, false);
        else api.getMethod("grantDevicePermission", UsbDevice.class, int.class).invoke(service, device, launcherUid);
        System.out.println("BEAM_XREAL_USB_GRANTED device="+name+" uid="+launcherUid+" persistent=false");
        return true;
    }

    /** Deliberately has no ActivityThread, Context, or LaunchServer initialization. */
    public static final class Worker {
        public static void main(String[] args) {
            try {
                if (android.os.Process.myUid()!=2000 || args.length!=5) throw new SecurityException("Shell USB worker only");
                int uid=Integer.parseInt(args[0]),vendor=Integer.parseInt(args[2]);
                if (vendor!=VENDOR) throw new SecurityException("Only XREAL USB devices are eligible");
                Class<?> manager=Class.forName("android.content.pm.IPackageManager");
                Object packages=service("package", "android.content.pm.IPackageManager");
                Method lookup;
                try { lookup=manager.getMethod("getPackageUid", String.class, long.class, int.class); }
                catch (NoSuchMethodException older) { lookup=manager.getMethod("getPackageUid", String.class, int.class, int.class); }
                Object flags=lookup.getParameterTypes()[1]==long.class ? (Object)Long.valueOf(0) : Integer.valueOf(0);
                if ((Integer)lookup.invoke(packages, PACKAGE, flags, uid/100000)!=uid)
                    throw new SecurityException("Launcher installation changed");
                Class<?> api=Class.forName("android.hardware.usb.IUsbManager");
                boolean granted=grantOnService(service("usb", "android.hardware.usb.IUsbManager"),api,uid,
                        args[1],vendor,Integer.parseInt(args[3]),Integer.parseInt(args[4]));
                System.out.println(granted?"UNBOUND_USB_GRANTED":"UNBOUND_USB_ABSENT");
            } catch (Throwable failure) { failure.printStackTrace(System.err);System.exit(1); }
        }
        private static Object service(String name, String type) throws Exception {
            IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
            Object value=Class.forName(type+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            if (value==null) throw new IllegalStateException("Android service unavailable: "+name);
            return value;
        }
    }
}
