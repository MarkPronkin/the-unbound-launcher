package dev.beampro.port;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.content.Context;
import android.view.InputDevice;
import dev.beampro.compat.GlassesIdentity;

/** Exact R1 aliases at SDK identity checks; real USB descriptors remain unchanged. */
public final class RogR1Compat {
    private RogR1Compat() {}

    public static int sdkUsbType(int original, UsbDevice device) {
        return device != null && GlassesIdentity.rogR1Usb(device.getVendorId(), device.getProductId()) ? 6 : original;
    }

    public static int sdkUsbVendor(UsbDevice device) {
        int vendor = device.getVendorId();
        return GlassesIdentity.rogR1Usb(vendor, device.getProductId()) ? 13080 : vendor;
    }

    public static int sdkInputVendor(InputDevice device) {
        int vendor = device.getVendorId();
        return GlassesIdentity.rogR1Usb(vendor, device.getProductId()) ? 13080 : vendor;
    }

    /** A UI-only identity, kept separate from the native SDK's type 6 profile. */
    public static int uiType(int sdkType) {
        if (sdkType != 6) return sdkType;
        try {
            Class<?> init = Class.forName("com.xreal.glassesdisplayplugevent.GlassesInitSetting");
            Context context = (Context) init.getMethod("getContext").invoke(init.getMethod("getInstance").invoke(null));
            UsbManager usb = context == null ? null : context.getSystemService(UsbManager.class);
            if (usb != null) for (UsbDevice device : usb.getDeviceList().values())
                if (GlassesIdentity.rogR1Usb(device.getVendorId(), device.getProductId())) return 1003;
        } catch (ReflectiveOperationException | RuntimeException unavailable) { /* Keep the SDK identity. */ }
        return sdkType;
    }
}
