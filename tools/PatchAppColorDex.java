import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** One texture factory and one JNI getter; all other class definitions audited. */
public final class PatchAppColorDex {
    static final String OWNER="Lcom/xreal/evapro/AndroidSurfaceTexture;",COMPAT="Ldev/beampro/port/AppColorCompat;";
    static final String FACTORY="generateExternalTexture",GETTER="unboundNeedsSoftwareDecode";
    static byte[] bytes(ClassDef cls)throws Exception {
        var store=new MemoryDataStore();DexPool.writeTo(store,new ImmutableDexFile(Opcodes.forApi(33),List.of(cls)));return store.getData();
    }
    static byte[] body(Method m)throws Exception {
        return bytes(new ImmutableClassDef(m.getDefiningClass(),1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),List.of(m)));
    }
    static Method hook(String name,String delegate,String result,Method original) {
        var implementation=new ImmutableMethodImplementation(1,List.of(
            new ImmutableInstruction35c(Opcode.INVOKE_STATIC,1,0,0,0,0,0,
                new ImmutableMethodReference(COMPAT,delegate,List.of("Ljava/lang/Object;"),result)),
            new ImmutableInstruction11x(Opcode.MOVE_RESULT,0),new ImmutableInstruction11x(Opcode.RETURN,0)),List.of(),List.of());
        return new ImmutableMethod(OWNER,name,List.of(),result,original==null?1:original.getAccessFlags(),
            original==null?Set.of():original.getAnnotations(),original==null?Set.of():original.getHiddenApiRestrictions(),implementation);
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!Set.of("patch","restore").contains(args[0]))throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore=args[0].equals("restore");var base=DexFileFactory.loadDexFile(args[3],Opcodes.forApi(33));
        var input=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));Method before=null;
        for(ClassDef cls:base.getClasses())if(cls.getType().equals(OWNER))for(Method m:cls.getMethods()) {
            if(m.getName().equals(GETTER))throw new IllegalStateException("Baseline already has color getter");
            if(m.getName().equals(FACTORY)) {
                if(before!=null||!m.getParameterTypes().isEmpty()||!m.getReturnType().equals("I")||AccessFlags.STATIC.isSet(m.getAccessFlags()))throw new IllegalStateException("Unexpected texture factory");
                before=m;
            }
        }
        if(before==null)throw new IllegalStateException("Missing texture factory");
        Method factory=hook(FACTORY,FACTORY,"I",before),getter=hook(GETTER,"needsSoftwareDecode","Z",null);
        List<ClassDef> output=new ArrayList<>();int factories=0,getters=0;
        for(ClassDef cls:input.getClasses()) {
            if(!cls.getType().equals(OWNER)){output.add(cls);continue;}
            List<Method> methods=new ArrayList<>();
            for(Method m:cls.getMethods()) {
                if(m.getName().equals(FACTORY)) {
                    if(!Arrays.equals(body(m),body(restore?factory:before)))throw new IllegalStateException("Unexpected factory body");
                    methods.add(restore?before:factory);factories++;
                } else if(m.getName().equals(GETTER)) {
                    if(!restore||!Arrays.equals(body(m),body(getter)))throw new IllegalStateException("Unexpected color getter");
                    getters++;
                } else methods.add(m);
            }
            if(!restore)methods.add(getter);
            output.add(new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods));
        }
        if(factories!=1||getters!=(restore?1:0))throw new IllegalStateException("Missing color hooks");
        if(restore) {
            Map<String,ClassDef> originals=new HashMap<>();for(ClassDef cls:base.getClasses())originals.put(cls.getType(),cls);
            for(ClassDef cls:output){ClassDef old=originals.remove(cls.getType());
                if(old==null||!Arrays.equals(bytes(old),bytes(cls)))throw new IllegalStateException("Unrelated color DEX changes: "+cls.getType());}
            if(!originals.isEmpty())throw new IllegalStateException("Lost classes");
            Files.write(Path.of(args[2]),Files.readAllBytes(Path.of(args[3])));
        } else DexPool.writeTo(args[2],new ImmutableDexFile(input.getOpcodes(),output));
        System.out.println(args[0]+": verified texture factory and color getter; other methods preserved");
    }
}
