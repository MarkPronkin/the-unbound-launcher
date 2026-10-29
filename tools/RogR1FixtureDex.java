import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.util.*;

/** Execute shipped identity classes under ART with logging and hardware discovery isolated. */
public final class RogR1FixtureDex {
    public static void main(String[] args)throws Exception {
        var sdk=DexFileFactory.loadDexFile(args[0],Opcodes.forApi(33));
        var client=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));
        var fixture=DexFileFactory.loadDexFile(args[2],Opcodes.forApi(33));
        Set<String> wanted=new HashSet<>(Set.of(
            "Lcom/xreal/glassesdisplayplugevent/helper/GlassesTypeUtil;",
            "Lcom/xreal/glassesdisplayplugevent/display/DisplayModel;",
            "Lcom/xreal/glassesdisplayplugevent/util/USBVendorProductID;",
            "Ldev/beampro/compat/GlassesIdentity;","Ldev/beampro/port/RogR1Compat;"));
        List<ClassDef> classes=new ArrayList<>();Method ethernet=null;
        for(var dex:List.of(sdk,client))for(ClassDef cls:dex.getClasses()) {
            if(wanted.remove(cls.getType()))classes.add(cls);
            if(cls.getType().equals("Lcom/xreal/glassesdisplayplugevent/GlassesInitSetting;"))
                for(Method m:cls.getMethods())if(m.getName().equals("isEthernetSeriesGlasses"))ethernet=m;
        }
        if(!wanted.isEmpty()||ethernet==null)throw new IllegalStateException("Missing shipped identity classes");
        int hooks=0;
        for(ClassDef cls:fixture.getClasses()) {
            List<Method> methods=new ArrayList<>();
            for(Method m:cls.getMethods()) {
                if(m.getDefiningClass().equals(ethernet.getDefiningClass())&&m.getName().equals(ethernet.getName())){methods.add(ethernet);hooks++;}
                else methods.add(m);
            }
            classes.add(new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),
                cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods));
        }
        if(hooks!=1)throw new IllegalStateException("Missing Ethernet selector fixture");
        DexPool.writeTo(args[3],new ImmutableDexFile(fixture.getOpcodes(),classes));
    }
}
