package dev.beampro.port;

import android.os.Handler;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.WeakHashMap;

/** Complete a cancelled SDK ping once, on its existing SDK worker. */
public final class PingCallbackCompat {
    private static final Map<Object, Pending> pending = new WeakHashMap<>();

    static Object ticket(Object helper) { synchronized(pending) { return pending.get(helper); } }
    static boolean active(Object helper, Object ticket) {
        synchronized(pending) { return ticket!=null && pending.get(helper)==ticket && !((Pending)ticket).completed; }
    }

    public static Object wrap(Object helper, Object callback) {
        try {
            Class<?> api = Class.forName("com.xreal.glasses.servicesdk.helper.PingGSeriesHelper$PingCallback");
            Handler handler = (Handler) GlassesConnectionCompat.get(helper, "mSdkHandler");
            Pending request = new Pending(helper, callback, handler, api.getMethod("onPingResult", boolean.class));
            synchronized (pending) {
                if (pending.containsKey(helper)) throw new IllegalStateException("Overlapping glasses network check");
                pending.put(helper, request);
            }
            return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (proxy, method, args) -> {
                if (method.getName().equals("onPingResult")) { request.complete((Boolean) args[0], false); return null; }
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                if (method.getName().equals("toString")) return "UnboundPingCallback";
                return null;
            });
        } catch (Exception error) { throw new IllegalStateException("Cannot guard glasses network callback", error); }
    }

    /** Called after the original onRelease has removed polling and its USB listener. */
    public static void released(Object helper) {
        Pending request;
        synchronized (pending) { request = pending.get(helper); }
        if (request != null) request.complete(false, true);
    }

    private static final class Pending {
        final WeakReference<Object> helper;
        final Object callback;
        final Handler handler;
        final Method result;
        boolean completed;
        Pending(Object helper, Object callback, Handler handler, Method result) {
            this.helper = new WeakReference<>(helper); this.callback = callback; this.handler = handler; this.result = result;
        }
        void complete(boolean success, boolean cancelled) {
            synchronized (pending) {
                if (completed) return;
                completed = true;
                if (pending.get(helper.get()) == this) pending.remove(helper.get());
            }
            Runnable deliver = () -> {
                try {
                    if (cancelled && helper.get() != null) {
                        // A ping already running at detach may have queued another poll
                        // after onRelease removed it. Drain that race before allowing retry.
                        handler.removeCallbacks((Runnable) GlassesConnectionCompat.get(helper.get(), "mRunnablePing"));
                        Log.i("UnboundConnect", "Cancelled glasses network check; completing pending startup");
                    }
                    result.invoke(callback, success);
                } catch (Exception error) { Log.e("UnboundConnect", "Cannot complete glasses network check", error); }
            };
            if (cancelled) handler.post(deliver); else deliver.run();
        }
    }

    private PingCallbackCompat() { }
}
