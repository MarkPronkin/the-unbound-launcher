package dev.beampro.compat;

/** Hardware identities shared by the app and its separately packaged shell helper. */
public final class GlassesIdentity {
    private GlassesIdentity() {}

    // USB IDs: obs-nyan-real-3dof/src/core/device_registry.cpp (ROG R1 entries).
    // EDID: R1 user's 2026-10-27 debug export. EDID and USB product IDs differ.
    public static boolean rogR1Usb(int vendor, int product) {
        return vendor == 0x0b05 && (product == 0x1d9c || product == 0x1d9d);
    }

    public static boolean usb(int vendor, int product) {
        return vendor == 13080 || rogR1Usb(vendor, product);
    }

    public static boolean rogR1Display(String product, String manufacturer) {
        return "AUS".equals(manufacturer) && "43593".equals(product);
    }

    /** Reuse the SDK's One Pro Ethernet/SBS profile; hardware validation is pending. */
    public static int sdkDisplayType(int original, String product, String manufacturer) {
        return rogR1Display(product, manufacturer) ? 6 : original;
    }
}
