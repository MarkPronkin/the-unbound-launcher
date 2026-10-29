package dev.beampro.bridge;

import android.content.pm.PackageManager;
import java.io.IOException;

/** Enable Android's Ethernet service for an identified glasses startup request. */
final class GlassesEthernet {
    private final PackageManager packages;
    private final int launcherUid;
    private final GlassesNetworkProbe.Runner runner;

    GlassesEthernet(PackageManager packages, int uid) { this(packages,uid,ShellCommand::run); }
    GlassesEthernet(PackageManager packages, int uid, GlassesNetworkProbe.Runner runner) {
        this.packages=packages;launcherUid=uid;this.runner=runner;
    }

    String enableIfDisabled(String name, int vendor, int product, int deviceId) throws Exception {
        if (packages.getApplicationInfo(XrealUsbPermissions.PACKAGE,0).uid!=launcherUid)
            throw new SecurityException("Launcher installation changed");
        // State inspection and USB decoding share one bounded process. USB parcels
        // must be decoded outside the helper's system Context.
        ShellCommand.Result result=runner.run(1500,"/system/bin/app_process","/",Worker.class.getName(),
                Integer.toString(launcherUid),name,Integer.toString(vendor),Integer.toString(product),Integer.toString(deviceId));
        if (result.succeeded()) {
            if (result.output.trim().endsWith("UNBOUND_ETHERNET_ENABLED"))
                return "Enabled Android Ethernet for the connected glasses; waiting for network readiness";
            if (result.output.trim().endsWith("UNBOUND_ETHERNET_UNCHANGED"))
                return "Ethernet is enabled or its state is unavailable; waiting for network readiness";
        }
        throw new IOException("Cannot enable Ethernet automatically; enable it in phone connection settings: "+result.output);
    }

    static boolean disabled(String output) {
        int disabled=0,enabled=0;
        for (String line:output.split("\\r?\\n")) {
            if (line.trim().equals("Ethernet State: disabled")) disabled++;
            if (line.trim().equals("Ethernet State: enabled")) enabled++;
        }
        return disabled==1 && enabled==0;
    }

    static boolean enableOnService(Object usb, Class<?> usbApi, Object ethernet, Class<?> ethernetApi,
            String name, int vendor, int product, int deviceId, GlassesNetworkProbe.Runner runner) throws Exception {
        if (XrealUsbPermissions.findDevice(usb,usbApi,name,vendor,product,deviceId)==null)
            throw new IOException("Glasses detached or changed before Ethernet setup");
        ShellCommand.Result state=runner.run(500,"/system/bin/dumpsys","-t","1","ethernet");
        // Unknown/absent services on older phones keep the original ping behavior.
        if (!state.succeeded() || !disabled(state.output)) return false;
        if (XrealUsbPermissions.findDevice(usb,usbApi,name,vendor,product,deviceId)==null)
            throw new IOException("Glasses detached or changed during Ethernet setup");
        ethernetApi.getMethod("setEthernetEnabled",boolean.class).invoke(ethernet,true);
        return true;
    }

    /** No ActivityThread or Context: retain the real UsbDevice parcel contract. */
    public static final class Worker {
        public static void main(String[] args) {
            try {
                if (android.os.Process.myUid()!=2000 || args.length!=5) throw new SecurityException("Shell Ethernet worker only");
                int uid=Integer.parseInt(args[0]);
                XrealUsbPermissions.Worker.requireLauncher(uid);
                Class<?> usbApi=Class.forName("android.hardware.usb.IUsbManager");
                Class<?> ethernetApi=Class.forName("android.net.IEthernetManager");
                boolean enabled=enableOnService(XrealUsbPermissions.Worker.service("usb",usbApi.getName()),usbApi,
                        XrealUsbPermissions.Worker.service("ethernet",ethernetApi.getName()),ethernetApi,
                        args[1],Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4]),ShellCommand::run);
                System.out.println(enabled?"UNBOUND_ETHERNET_ENABLED":"UNBOUND_ETHERNET_UNCHANGED");
            } catch (Throwable failure) { failure.printStackTrace(System.err);System.exit(1); }
        }
    }
}
