package dev.beampro.bridge;

import android.os.Bundle;
import java.lang.reflect.Method;

/** Match the provider's framework contract without loading Android 12 classes on Android 11. */
final class ProviderCallCompat {
    private ProviderCallCompat() {}

    static Object shellAttribution(int uid) throws Exception {
        Class<?> builder;
        try { builder = Class.forName("android.content.AttributionSource$Builder"); }
        catch (ClassNotFoundException android11) { return null; }
        Object source = builder.getConstructor(int.class).newInstance(uid);
        builder.getMethod("setPackageName", String.class).invoke(source, "com.android.shell");
        return builder.getMethod("build").invoke(source);
    }

    static Bundle publish(Object provider, Class<?> api, Object source, String authority, Bundle payload) throws Exception {
        Method call = null;
        if (source != null) {
            try { call = api.getMethod("call", source.getClass(), String.class, String.class, String.class, Bundle.class); }
            catch (NoSuchMethodException olderContract) { /* Some builds retain the Android 11 contract. */ }
        }
        // Only signature absence selects another contract. Never retry a rejected or
        // failed publication through an older signature, or change the shell identity.
        if (call != null) return (Bundle) call.invoke(provider, source, authority, "publish", null, payload);
        call = api.getMethod("call", String.class, String.class, String.class, String.class, String.class, Bundle.class);
        return (Bundle) call.invoke(provider, "com.android.shell", null, authority, "publish", null, payload);
    }
}
