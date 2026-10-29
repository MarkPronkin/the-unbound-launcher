package dev.beampro.bridge;

import dev.beampro.compat.GlassesIdentity;
import java.util.ArrayList;
import java.util.List;

/** Synchronous service snapshots: shell processes can have display callbacks deferred indefinitely. */
public final class DisplaySnapshotReader {
    private DisplaySnapshotReader() {}

    public static List<ManagedDisplayPolicy.Screen> read(Object service,Class<?> api,boolean manageModes) throws Exception {
        int[] ids;
        try {
            ids=(int[])api.getMethod("getDisplayIds",boolean.class).invoke(service,true);
        } catch(NoSuchMethodException olderApi) {
            ids=(int[])api.getMethod("getDisplayIds").invoke(service);
        }
        List<ManagedDisplayPolicy.Screen> result=new ArrayList<>();
        for(int id:ids) {
            if(id<=0)continue;
            // Read through IDisplayManager, never DisplayManagerGlobal's callback-fed
            // ID cache or a previously constructed Display/DisplayInfo instance.
            Object info=api.getMethod("getDisplayInfo",int.class).invoke(service,id);
            if(info==null)continue; // Unplugged between the two synchronous reads.
            Class<?> type=info.getClass();
            if(type.getField("type").getInt(info)!=2)continue;
            String unique=(String)type.getField("uniqueId").get(info);
            boolean xreal=false;
            try {
                Object product=type.getField("deviceProductInfo").get(info);
                if(product!=null) {
                    Class<?> identity=product.getClass();
                    String manufacturer=(String)identity.getMethod("getManufacturerPnpId").invoke(product);
                    xreal="MRG".equals(manufacturer);
                    if(!xreal && "AUS".equals(manufacturer))
                        xreal=GlassesIdentity.rogR1Display((String)identity.getMethod("getProductId").invoke(product),manufacturer);
                }
            } catch(NoSuchFieldException|NoSuchMethodException olderIdentityApi) { /* Preserve the legacy path. */ }
            List<ManagedDisplayPolicy.Mode> modes=new ArrayList<>();
            if(manageModes)for(Object supported:(Object[])type.getField("supportedModes").get(info))modes.add(mode(supported));
            ManagedDisplayPolicy.Mode active=mode(type.getMethod("getMode").invoke(info));
            result.add(new ManagedDisplayPolicy.Screen(id,unique,xreal,active,modes));
        }
        return result;
    }

    private static ManagedDisplayPolicy.Mode mode(Object value) throws Exception {
        if(value==null)return null;
        Class<?> type=value.getClass();
        return new ManagedDisplayPolicy.Mode((Integer)type.getMethod("getPhysicalWidth").invoke(value),
                (Integer)type.getMethod("getPhysicalHeight").invoke(value),
                (Float)type.getMethod("getRefreshRate").invoke(value));
    }
}
