package dev.beampro.port;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/** Observe the SDK's individual mode writes without changing their order, arguments or results. */
public final class GlassesModeDiagnostics {
    private static final AtomicLong SEQUENCE=new AtomicLong();
    private GlassesModeDiagnostics() {}

    private static Method debug(String name,Class<?>... types) throws Exception {
        Method method=Class.forName("dev.beampro.settings.DebugData").getDeclaredMethod(name,types);
        method.setAccessible(true);return method;
    }
    private static boolean enabled() {
        try{return Boolean.TRUE.equals(debug("modeDiagnosticsEnabled").invoke(null));}
        catch(Exception|LinkageError unavailable){return false;}
    }
    private static void record(String kind,String text) {
        try{debug("modeEvent",String.class,String.class).invoke(null,kind,text);}
        catch(Exception|LinkageError unavailable){} // Diagnostics cannot fail a native request.
    }
    private static String read(Object control,String method) {
        long start=System.nanoTime();
        try{return String.valueOf(control.getClass().getMethod(method).invoke(control))+" ("+(System.nanoTime()-start)/1_000_000+" ms)";}
        catch(Exception|LinkageError unavailable){return "unavailable:"+unavailable.getClass().getSimpleName();}
    }
    /** Explicit guided-test snapshot; callers own the SDK worker and network lease. */
    public static String state(Object control,boolean ethernet,boolean capabilities) {
        boolean trace=enabled();long sequence=SEQUENCE.incrementAndGet();
        if(trace)record("mode_read_start","snapshot="+sequence+"; ethernet="+ethernet+"; capabilities="+capabilities);
        String value=!ethernet?"legacy="+read(control,"nativeGet2D3DMode"):"edid="+read(control,"nativeGetDpCurrentEdid")+"; input="+read(control,"nativeGetDpInputMode")
                +"; working="+read(control,"nativeGetDpWorkingState")
                +(capabilities?"; supportedModeMask="+read(control,"nativeGetSupportedDisplayModeMask"):"");
        if(trace)record("mode_readback","snapshot="+sequence+"; "+value);
        return value;
    }
    private static boolean write(Object control,String method,int... values) throws Throwable {
        boolean trace=enabled();long sequence=SEQUENCE.incrementAndGet();
        String detail="command="+sequence+"; api="+method+"; args="+java.util.Arrays.toString(values)
                +"; thread="+Thread.currentThread().getName();
        if(trace){
            record("mode_request",detail+"; reading pre-command state");
            // Read before the write only. Reading after a failed write could replace the
            // native error before the SDK's own checkError sees it.
            String before=method.equals("nativeSet2D3DMode")?"legacy="+read(control,"nativeGet2D3DMode")
                    :"edid="+read(control,"nativeGetDpCurrentEdid")+"; input="+read(control,"nativeGetDpInputMode");
            record("mode_before",detail+"; before "+before);
        }
        Class<?>[] types=new Class<?>[values.length];Object[] args=new Object[values.length];
        for(int i=0;i<values.length;i++){types[i]=int.class;args[i]=values[i];}
        long start=System.nanoTime();
        try {
            boolean result=Boolean.TRUE.equals(control.getClass().getMethod(method,types).invoke(control,args));
            if(trace)record("mode_result",detail+"; returned="+result+"; durationMs="+(System.nanoTime()-start)/1_000_000);
            return result;
        } catch(InvocationTargetException failure){
            if(trace)record("mode_result",detail+"; threw="+failure.getCause().getClass().getSimpleName()
                    +"; durationMs="+(System.nanoTime()-start)/1_000_000);
            throw failure.getCause();
        }
    }
    public static boolean setEdid(Object control,int mode) throws Throwable {return write(control,"nativeSetDpCurrentEdid",mode);}
    public static boolean setInputMode(Object control,int mode,int flags) throws Throwable {return write(control,"nativeSetDpInputModeEx",mode,flags);}
    public static boolean setLegacy(Object control,int mode) throws Throwable {return write(control,"nativeSet2D3DMode",mode);}
}
