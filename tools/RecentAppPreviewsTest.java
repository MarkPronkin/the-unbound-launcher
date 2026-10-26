package dev.beampro.bridge;

import android.content.*;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.hardware.display.IDisplayManager;
import android.os.ServiceManager;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/** Real cache/capture code with only Android service and image endpoints simulated. */
public final class RecentAppPreviewsTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static final class Info {
        public int type=5,logicalWidth=1000,logicalHeight=600;
        public String name="com.xreal.virtualdisplay-example",uniqueId="virtual:owned:1";
    }
    public static final class Displays implements IDisplayManager {
        Info info=new Info();int reads;boolean attached=true;
        public Object getDisplayInfo(int id){reads++;return id==17&&attached?info:null;}
    }
    public static final class Content {int id=17;private int getDisplayId(){return id;}}
    public static final class Manager {
        Content content=new Content();
        private Content findActivityContent(ComponentName name){return content;}
    }

    public static void main(String[] args)throws Exception {
        File dir=new File(args[0]);Displays displays=new Displays();TestWindows windows=new TestWindows();Manager manager=new Manager();
        ServiceManager.services.put("display",displays);ServiceManager.services.put("window",windows);
        RecentAppsStore history=new RecentAppsStore(new File(dir,"history.bin"));
        history.record("com.example.app",1);int id=history.list().get(0).id;
        RecentAppPreviews previews=new RecentAppPreviews(new Context(),manager,history,dir);
        byte[] first=previews.get(id);
        check(first!=null&&first.length>0,"capture saved and returned");
        check(windows.calls==1&&Math.abs(windows.scale-.4f)<.001,"capture scaled to 400-pixel maximum");
        check(HardwareBuffer.closed==1&&Bitmap.hardwareRecycled==1&&Bitmap.softwareRecycled==1,"all capture resources released");
        check(Bitmap.hardwareCompressed==0,"encode only the portable software bitmap");
        check(Context.factoryCalls==0,"no app-bound display or window factories");
        check(displays.reads>=2,"fresh before/after display identity checks");
        check(previews.get(12345)==null&&windows.calls==1,"unknown history ID cannot capture");

        manager.content=null;
        check(Arrays.equals(first,previews.get(id))&&windows.calls==1,"closed window retains last preview");
        manager.content=new Content();windows.noResult=true;
        check(Arrays.equals(first,previews.get(id)),"empty platform result preserves cached preview");windows.noResult=false;
        windows.fail=true;
        check(Arrays.equals(first,previews.get(id)),"permission/driver exception preserves cached preview");windows.fail=false;

        for(int type:new int[]{1,2,4}) {
            displays.info.type=type;int before=windows.calls;
            check(Arrays.equals(first,previews.get(id))&&windows.calls==before,"physical/unowned display rejected");
        }
        displays.info.type=5;displays.info.name="Another app display";int before=windows.calls;
        previews.get(id);check(windows.calls==before,"virtual display name must match owned app windows");
        displays.info.name="com.xreal.virtualdisplay-example";displays.info.logicalWidth=0;
        previews.get(id);check(windows.calls==before,"invalid geometry rejected");displays.info.logicalWidth=1000;
        manager.content.id=0;previews.get(id);check(windows.calls==before,"phone ID rejected before capture");manager.content.id=17;
        displays.attached=false;previews.get(id);check(windows.calls==before,"missing display rejected");displays.attached=true;
        check(previews.get(id)!=null&&windows.calls==before+1,"later display appears without callback cache");

        File cached=new File(dir,"beampro-recent-preview-"+id+".jpg");Files.delete(cached.toPath());
        windows.during=()->displays.info.uniqueId="virtual:reused:2";
        check(previews.get(id)==null&&!cached.exists(),"display ID reuse during capture never persists another window");windows.during=null;
        windows.during=()->manager.content=null;
        check(previews.get(id)==null&&!cached.exists(),"closed app during capture does not save");windows.during=null;manager.content=new Content();
        windows.during=()->manager.content.id=18;
        check(previews.get(id)==null&&!cached.exists(),"moved app during capture does not save");windows.during=null;manager.content.id=17;

        int closed=HardwareBuffer.closed,recycled=Bitmap.hardwareRecycled;
        windows.secure=true;
        check(previews.get(id)==null,"secure capture result never cached");
        check(HardwareBuffer.closed==closed+1&&Bitmap.hardwareRecycled==recycled,"secure result buffer closed without bitmap conversion");windows.secure=false;
        windows.noBitmap=true;closed=HardwareBuffer.closed;
        check(previews.get(id)==null&&HardwareBuffer.closed==closed+1,"null bitmap closes its buffer");windows.noBitmap=false;
        Bitmap.failCopy=true;closed=HardwareBuffer.closed;recycled=Bitmap.hardwareRecycled;
        check(previews.get(id)==null&&HardwareBuffer.closed==closed+1&&Bitmap.hardwareRecycled==recycled+1,"readback failure releases handles");Bitmap.failCopy=false;
        Bitmap.large=true;check(previews.get(id)==null&&!cached.exists(),"oversized encoded preview never cached");Bitmap.large=false;
        check(previews.get(id)!=null,"capture recovers after failure");
        windows.during=()->{try{history.remove(id);}catch(Exception e){throw new RuntimeException(e);}};
        previews.get(id);windows.during=null;
        check(Arrays.equals(first,Files.readAllBytes(cached.toPath())),"dismissal during capture does not replace last saved bytes");
        check(previews.get(id)==null,"dismissed entry cannot retrieve cached image");
        previews.prune();check(!cached.exists(),"dismissed preview file pruned");
        System.out.println("PASS preview regressions: "+checks+" ("+args[1]+")");
    }
}
