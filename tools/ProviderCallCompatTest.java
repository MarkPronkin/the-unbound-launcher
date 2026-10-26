package dev.beampro.bridge;

import android.os.Bundle;
import java.lang.reflect.*;

/** Run once with each real provider signature, including no AttributionSource class. */
public final class ProviderCallCompatTest {
    public static void main(String[] args) throws Exception {
        String scenario=args[0];
        Class<?> api=Class.forName("android.content.IContentProvider");
        Object source=ProviderCallCompat.shellAttribution(2000);
        if((source==null)!=scenario.equals("android11"))throw new AssertionError("Attribution availability");
        Bundle payload=new Bundle(),result=new Bundle();int[] calls={0};
        Object provider=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,method,values)->{
            calls[0]++;
            boolean modern=values.length==5;
            if(modern!=(scenario.equals("modern")||scenario.equals("denied")))throw new AssertionError("Wrong provider contract");
            if(modern) {
                if(values[0]!=source)throw new AssertionError("Wrong attribution object");
            } else if(!"com.android.shell".equals(values[0])||values[1]!=null)throw new AssertionError("Wrong shell attribution");
            int offset=modern?1:2;
            if(!"test.bridge".equals(values[offset])||!"publish".equals(values[offset+1])
                    ||values[offset+2]!=null||values[offset+3]!=payload)throw new AssertionError("Publication payload changed");
            if(scenario.equals("denied"))throw new SecurityException("Rejected by provider");
            return result;
        });
        boolean denied=false;
        try {
            if(ProviderCallCompat.publish(provider,api,source,"test.bridge",payload)!=result)throw new AssertionError("Result changed");
        } catch(InvocationTargetException failure) {
            if(!(failure.getCause() instanceof SecurityException))throw failure;
            denied=true;
        }
        if(denied!=scenario.equals("denied")||calls[0]!=1)throw new AssertionError("Publication retried or rejection swallowed");
        System.out.println("PASS provider contract: "+scenario);
    }
}
