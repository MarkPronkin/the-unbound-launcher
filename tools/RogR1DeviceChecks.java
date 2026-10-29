package dev.beampro.r1checks;

import android.app.Instrumentation;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.view.Display;
import java.lang.reflect.*;

/** No physical glasses, network commands, or native glasses SDK calls are made. */
public final class RogR1DeviceChecks extends Instrumentation {
    int checks;
    void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static UsbDevice usb(int vendor,int product) {
        Parcel p=Parcel.obtain();
        try {
            p.writeString("/dev/bus/usb/001/002");p.writeInt(vendor);p.writeInt(product);
            p.writeInt(0);p.writeInt(0);p.writeInt(0);p.writeString("Fixture");p.writeString("R1");p.writeString("1.0");
            p.writeStrongBinder(new Binder());p.writeParcelableArray(new UsbConfiguration[0],0);
            for(int i=0;i<5;i++)p.writeInt(0);p.setDataPosition(0);return UsbDevice.CREATOR.createFromParcel(p);
        } finally {p.recycle();}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Class<?> types=Class.forName("com.xreal.glassesdisplayplugevent.helper.GlassesTypeUtil");
            Method type=types.getMethod("checkGlassesType",UsbDevice.class);
            Class<?> receiver=Class.forName("dev.beampro.adb.AutoGlassesReceiver");
            Method auto=receiver.getDeclaredMethod("glasses",UsbDevice.class);auto.setAccessible(true);
            for(int product:new int[]{0x1d9c,0x1d9d}) {
                UsbDevice device=usb(0x0b05,product);
                check((Integer)type.invoke(null,device)==6,"Shipped SDK maps both ASUS USB configurations to type 6");
                check((Boolean)auto.invoke(null,device),"Production automatic entry accepts R1");
                check(device.getVendorId()==0x0b05&&device.getProductId()==product,"Actual USB identity stays intact");
            }
            for(int[] known:new int[][]{{1078,6},{1080,7},{1060,2},{1086,9},{1090,11}})
                check((Integer)type.invoke(null,usb(13080,known[0]))==known[1],"Existing USB model retained");
            check((Integer)type.invoke(null,usb(0x0b05,0x1d9e))==-1,"Other ASUS devices stay unknown");
            check(!(Boolean)auto.invoke(null,usb(0x0b05,0x1d9e)),"Other ASUS devices cannot auto-launch");
            check(!(Boolean)auto.invoke(null,usb(13080,0x1d9c)),"ASUS PID cannot be used under XREAL VID");
            Class<?> model=Class.forName("com.xreal.glassesdisplayplugevent.display.DisplayModel");
            Display display=getTargetContext().getSystemService(DisplayManager.class).getDisplay(0);
            Object instance=model.getConstructor(Display.class).newInstance(display);
            Method edid=model.getDeclaredMethod("_equalsPidVid",String.class,String.class,String.class);edid.setAccessible(true);
            check((Integer)edid.invoke(instance,"R1","43593","AUS")==6,"Captured R1 EDID maps to One Pro profile in shipped SDK");
            check((Integer)edid.invoke(instance,"R1","43594","AUS")==-1,"Name alone cannot identify R1");
            check((Integer)edid.invoke(instance,"Monitor","43593","DEL")==-1,"EDID VID must match");
            check((Integer)edid.invoke(instance,"One Pro","16640","MRG")==6,"Original One Pro EDID stays intact");
            check((Integer)edid.invoke(instance,"One","16641","MRG")==7,"Original One EDID stays intact");
            Class<?> settings=Class.forName("com.xreal.glassesdisplayplugevent.GlassesInitSetting");
            Object init=settings.getMethod("getInstance").invoke(null);
            settings.getField("type").setInt(null,6);
            check((Boolean)settings.getMethod("isEthernetSeriesGlasses").invoke(init),"Original SDK selects Ethernet for R1 profile");
            settings.getField("type").setInt(null,11);
            check(!(Boolean)settings.getMethod("isEthernetSeriesGlasses").invoke(init),"H-series transport unchanged");
            result.putString("stream","PASS shipped R1 SDK DEX assertions: "+checks+"\n");finish(-1,result);
        } catch(Throwable error) {
            result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);
        }
    }
}
