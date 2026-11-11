package dev.beampro.settings;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;

/** Recognize physical glasses by EDID, rather than drawing a pattern on another monitor. */
final class DisplayTestScreens {
    final Display display;
    final int id,width,height;
    final float hz;
    final boolean on;
    final String text;
    private DisplayTestScreens(Display display,String text) {
        this.display=display;this.text=text;
        if(display==null){id=-1;width=height=0;hz=0;on=false;}
        else{Display.Mode mode=display.getMode();id=display.getDisplayId();width=mode.getPhysicalWidth();height=mode.getPhysicalHeight();hz=mode.getRefreshRate();on=display.getState()==Display.STATE_ON;}
    }
    static DisplayTestScreens read(Context context) {
        try {
            Display found=null;
            for(Display display:context.getSystemService(DisplayManager.class).getDisplays())if(glasses(display)){
                if(found!=null)return new DisplayTestScreens(null,"More than one glasses display is connected. Disconnect the other display.");
                found=display;
            }
            if(found==null)return new DisplayTestScreens(null,"No physical glasses display detected");
            Display.Mode mode=found.getMode();
            return new DisplayTestScreens(found,mode.getPhysicalWidth()+" × "+mode.getPhysicalHeight()+" / "
                    +String.format(java.util.Locale.ROOT,"%.1f",mode.getRefreshRate())+" Hz"+(found.getState()==Display.STATE_ON?"":" (display off)"));
        } catch(RuntimeException failure){return new DisplayTestScreens(null,"Display information is unavailable: "+failure.getClass().getSimpleName());}
    }
    private static boolean glasses(Display display) {
        if(display==null||!display.isValid()||display.getDisplayId()==Display.DEFAULT_DISPLAY)return false;
        if(Build.VERSION.SDK_INT>=31){
            android.hardware.display.DeviceProductInfo product=display.getDeviceProductInfo();
            return product!=null&&("MRG".equals(product.getManufacturerPnpId())
                    ||("AUS".equals(product.getManufacturerPnpId())&&"43593".equals(product.getProductId())));
        }
        try{return Boolean.TRUE.equals(Class.forName("ai.nreal.activitylife.NRXRApp").getMethod("isNrealDisplay",Display.class,boolean.class).invoke(null,display,false));}
        catch(Exception absent){return false;}
    }
}
