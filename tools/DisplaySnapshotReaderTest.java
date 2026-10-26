import dev.beampro.bridge.DisplaySnapshotReader;
import dev.beampro.bridge.ManagedDisplayPolicy.Screen;
import java.util.*;

/** Reproduce Android 17 deferring shell-process display events while hotplug continues. */
public final class DisplaySnapshotReaderTest {
    static int assertions;
    static void check(boolean value,String reason){assertions++;if(!value)throw new AssertionError(reason);}
    public static final class Mode {
        int w,h;float hz;
        Mode(int w,int h,float hz){this.w=w;this.h=h;this.hz=hz;}
        public int getPhysicalWidth(){return w;}public int getPhysicalHeight(){return h;}
        public float getRefreshRate(){return hz;}
    }
    public static final class Product {
        String pnp;Product(String pnp){this.pnp=pnp;}
        public String getManufacturerPnpId(){return pnp;}
    }
    public static final class Info {
        public int type;
        public String uniqueId;
        public Product deviceProductInfo;
        public Mode[] supportedModes;
        Mode active;
        Info(int type,String unique,String pnp,Mode active,Mode... modes){
            this.type=type;uniqueId=unique;deviceProductInfo=pnp==null?null:new Product(pnp);
            this.active=active;supportedModes=modes;
        }
        public Mode getMode(){return active;}
    }
    public static final class Api {
        Map<Integer,Info> infos=new LinkedHashMap<>();
        int reads;boolean includesDisabled;
        public int[] getDisplayIds(boolean includeDisabled){
            includesDisabled=includeDisabled;return infos.keySet().stream().mapToInt(Integer::intValue).toArray();
        }
        public Info getDisplayInfo(int id){if(id==0)throw new AssertionError("Must not inspect primary display");reads++;return infos.get(id);}
    }
    public static final class LegacyApi {
        public int[] getDisplayIds(){return new int[]{17};}
        public Info getDisplayInfo(int id){return glasses(VGA);}
    }
    static final Mode VGA=new Mode(640,480,59.94f),SBS=new Mode(3840,1080,60);
    static Info glasses(Mode active){return new Info(2,"local:xreal","MRG",active,SBS,VGA);}
    static List<Screen> read(Api api)throws Exception{return DisplaySnapshotReader.read(api,Api.class,true);}
    public static void main(String[] args)throws Exception {
        Api api=new Api();api.infos.put(0,new Info(1,"local:phone","GGL",VGA,VGA));
        // The framework cache is never refreshed: the real Pixel's shell callback
        // records report cached=true and pendingDisplayEvents>0 throughout hotplug.
        int[] callbackFedFrameworkIds={0};
        check(read(api).isEmpty(),"Initial no-glasses state");
        api.infos.put(17,glasses(VGA));
        List<Screen> screens=read(api);
        check(callbackFedFrameworkIds.length==1&&screens.size()==1,"Discover hotplug without any callback delivery");
        check(api.includesDisabled,"Discover the disabled display before Android consent");
        check(screens.get(0).id==17&&screens.get(0).xreal,"Identify physical XREAL using product data");
        check(screens.get(0).active.width==640&&screens.get(0).modes.get(0).width==3840,"Read active fallback and advertised stereo separately");
        api.infos.put(17,glasses(SBS));
        check(read(api).get(0).active.width==3840,"Observe hardware mode change without a display-changed callback");
        check(screens.get(0).active.width==640,"Previous snapshot cannot silently change under the policy");
        api.infos.remove(17);
        check(read(api).isEmpty(),"Observe unplug without a removal callback for policy restoration");
        api.infos.put(18,glasses(VGA));
        check(read(api).get(0).id==18,"Observe the SBS reconnect's new logical ID");
        api.infos.put(18,null);
        check(read(api).isEmpty(),"Tolerate unplug between ID and info queries");
        api.infos.put(18,new Info(2,"local:monitor","DEL",VGA,VGA));
        check(!read(api).get(0).xreal,"Do not treat an arbitrary monitor as XREAL");
        api.infos.put(18,new Info(5,"virtual:xreal","MRG",SBS,SBS));
        check(read(api).isEmpty(),"Do not manage a virtual display even with matching product data");
        api.infos.put(18,new Info(2,"local:unknown",null,VGA,VGA));
        check(!read(api).get(0).xreal,"Missing product identity does not gain XREAL privileges");
        api.infos.put(18,glasses(VGA));
        check(DisplaySnapshotReader.read(api,Api.class,false).get(0).modes.isEmpty(),"Legacy mode-management guard remains effective");
        check(DisplaySnapshotReader.read(new LegacyApi(),LegacyApi.class,true).get(0).id==17,"Support the older direct ID API without using a client cache");
        System.out.println("PASS direct display snapshot regressions: "+assertions);
    }
}
