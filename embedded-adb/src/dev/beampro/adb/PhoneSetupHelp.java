package dev.beampro.adb;

import java.util.Locale;

/** Brand names only customize instructions; they never gate pairing, display or rendering. */
final class PhoneSetupHelp {
    private PhoneSetupHelp() {}

    static String developerHint(String manufacturer, String brand) {
        String maker = normalize(manufacturer), label = normalize(brand);
        if (is(maker, label, "samsung"))
            return "Samsung: open About phone → Software information, then tap Build number seven times. Return to Settings → Developer options.";
        if (is(maker, label, "honor") || is(maker, label, "huawei"))
            return "Honor / Huawei: search Settings for Build number and tap it seven times. Then search for Developer options → Wireless debugging.";
        if (is(maker, label, "oppo") || is(maker, label, "realme") || is(maker, label, "oneplus"))
            return "Oppo / Realme / OnePlus: look in About device (or About phone) → Version. Tap Version number or Build number seven times, then search Settings for Developer options.";
        return "In Settings, search for Build number and tap it seven times. Then search for Developer options → Wireless debugging. Menu names vary by phone and Android version.";
    }

    static String details(String manufacturer, String brand) {
        return developerHint(manufacturer, brand)
                + "\n\nThis APK uses the capabilities of your phone, including Samsung, Honor, Oppo, Realme, Xiaomi, Redmi, Poco, Vivo, Motorola, Sony, OnePlus and Pixel. Each model still needs testing."
                + "\n\nYou need Android 11 or newer, a 64-bit ARM phone, Wireless debugging with pairing codes, and USB-C video output (DisplayPort Alt Mode). A USB-C charging port alone is not enough. Check your model's wired video specifications or try a USB-C monitor."
                + "\n\nIf Wireless debugging is missing or disabled, check the explanation in Settings. A work policy or the phone's firmware can make it unavailable; Unbound cannot enable it for you."
                + "\n\nIf automatic port detection fails, keep the pairing dialog open and reply to the notification with code:pairing-port, for example 123456:37123. Use your own code and port. Enter the separate connection port from the main Wireless debugging screen under Other ways to pair."
                + "\n\nIf setup stops when you switch to Settings, check Unbound's notification and background/battery permissions in App info. If a desktop or casting app opens in the glasses, return to Unbound and enter AR Space; approve Android's display prompt if it appears. Automatic display setup depends on the phone's available APIs.";
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static boolean is(String maker, String brand, String name) { return maker.equals(name) || brand.equals(name); }
}
