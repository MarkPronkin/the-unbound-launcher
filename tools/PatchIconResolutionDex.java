import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Keep Unity's raw-pixel/layer contract; replace only icon source loading and rasterization. */
public final class PatchIconResolutionDex {
    static final String OWNER="Lcom/xreal/evaprolib/IconLoadingHelper;",COMPAT="Ldev/beampro/port/HeadsetIconCompat;";
    static final String DRAWABLE="Landroid/graphics/drawable/Drawable;",BITMAP="Landroid/graphics/Bitmap;";
    static final Map<String,String> RASTER=Map.of("getAdaptiveIconDrawable","adaptive","getBitmapIconDrawable","legacy",
            "getAdaptiveIconDrawableFG","foreground","getAdaptiveIconDrawableBG","background");
    static final Set<String> LOAD=Set.of("getAppIcon","getAppIconRaw","getAppIconRawV2");
    static byte[] bytes(ClassDef cls)throws Exception {
        var store=new MemoryDataStore();DexPool.writeTo(store,new ImmutableDexFile(Opcodes.forApi(33),List.of(cls)));return store.getData();
    }
    static byte[] body(Method m)throws Exception {
        return bytes(new ImmutableClassDef(m.getDefiningClass(),1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),List.of(m)));
    }
    static Method patch(Method method) {
        var original=method.getImplementation();List<Instruction> code=new ArrayList<>();int registers=original.getRegisterCount();
        if(!AccessFlags.STATIC.isSet(method.getAccessFlags()))throw new IllegalStateException("Expected static icon helper");
        if(RASTER.containsKey(method.getName())) {
            boolean layers=method.getName().endsWith("FG")||method.getName().endsWith("BG");
            List<String> params=layers?List.of(DRAWABLE,"Landroid/content/pm/PackageInfo;"):List.of(DRAWABLE);
            if(!method.getParameterTypes().equals(params)||!method.getReturnType().equals(BITMAP))throw new IllegalStateException("Raster signature changed");
            registers=params.size();
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC,registers,0,layers?1:0,0,0,0,
                    new ImmutableMethodReference(COMPAT,RASTER.get(method.getName()),params,BITMAP)));
            code.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));code.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT,0));
            return new ImmutableMethod(OWNER,method.getName(),method.getParameters(),method.getReturnType(),method.getAccessFlags(),
                    method.getAnnotations(),method.getHiddenApiRestrictions(),new ImmutableMethodImplementation(registers,code,List.of(),List.of()));
        }
        if(!method.getParameterTypes().equals(List.of("Landroid/content/pm/PackageManager;","Landroid/content/pm/PackageInfo;")))
            throw new IllegalStateException("Icon loader signature changed");
        int hits=0;
        for(Instruction instruction:original.getInstructions()) {
            if(instruction instanceof ReferenceInstruction ref&&ref.getReference() instanceof MethodReference call
                    &&call.getDefiningClass().equals("Landroid/content/pm/PackageManager;")&&call.getName().equals("getApplicationIcon")) {
                if(!(instruction instanceof Instruction35c invoke)||instruction.getOpcode()!=Opcode.INVOKE_VIRTUAL||invoke.getRegisterCount()!=2
                        ||!call.getParameterTypes().equals(List.of("Landroid/content/pm/ApplicationInfo;"))||!call.getReturnType().equals(DRAWABLE))
                    throw new IllegalStateException("Unexpected icon-source call");
                instruction=new ImmutableInstruction35c(Opcode.INVOKE_STATIC,2,invoke.getRegisterC(),invoke.getRegisterD(),0,0,0,
                        new ImmutableMethodReference(COMPAT,"load",List.of("Landroid/content/pm/PackageManager;","Landroid/content/pm/ApplicationInfo;"),DRAWABLE));
                hits++;
            }
            code.add(instruction);
        }
        if(hits!=1)throw new IllegalStateException("Expected one icon-source call");
        return new ImmutableMethod(OWNER,method.getName(),method.getParameters(),method.getReturnType(),method.getAccessFlags(),
                method.getAnnotations(),method.getHiddenApiRestrictions(),new ImmutableMethodImplementation(registers,code,original.getTryBlocks(),original.getDebugItems()));
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!Set.of("patch","restore").contains(args[0]))throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore=args[0].equals("restore");var base=DexFileFactory.loadDexFile(args[3],Opcodes.forApi(33));
        var input=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));Map<String,Method> originals=new HashMap<>();
        Set<String> targets=new HashSet<>(RASTER.keySet());targets.addAll(LOAD);
        for(ClassDef cls:base.getClasses())if(cls.getType().equals(OWNER))for(Method method:cls.getMethods())if(targets.contains(method.getName()))
            if(originals.put(method.getName(),method)!=null)throw new IllegalStateException("Duplicate icon method");
        if(!originals.keySet().equals(targets))throw new IllegalStateException("Missing icon baseline methods");
        List<ClassDef> output=new ArrayList<>();Set<String> edits=new HashSet<>();
        for(ClassDef cls:input.getClasses()) {
            if(!cls.getType().equals(OWNER)){output.add(cls);continue;}
            List<Method> methods=new ArrayList<>();
            for(Method method:cls.getMethods()) {
                Method before=originals.get(method.getName());
                if(before==null){methods.add(method);continue;}
                Method after=patch(before);
                if(!Arrays.equals(body(method),body(restore?after:before)))throw new IllegalStateException("Unexpected icon method body: "+method.getName());
                methods.add(restore?before:after);edits.add(method.getName());
            }
            output.add(new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods));
        }
        if(!edits.equals(targets))throw new IllegalStateException("Missing icon edits");
        if(restore) {
            var expected=new MemoryDataStore();var actual=new MemoryDataStore();
            DexPool.writeTo(expected,base);DexPool.writeTo(actual,new ImmutableDexFile(input.getOpcodes(),output));
            if(!Arrays.equals(expected.getData(),actual.getData())) {
                Map<String,ClassDef> unchanged=new HashMap<>();for(ClassDef cls:base.getClasses())unchanged.put(cls.getType(),cls);
                for(ClassDef cls:output){ClassDef original=unchanged.remove(cls.getType());
                    if(original==null||!Arrays.equals(bytes(cls),bytes(original)))throw new IllegalStateException("Unrelated icon DEX changes: "+cls.getType());}
                if(!unchanged.isEmpty())throw new IllegalStateException("Missing unrelated classes");
            }
            Files.write(Path.of(args[2]),Files.readAllBytes(Path.of(args[3])));
        } else DexPool.writeTo(args[2],new ImmutableDexFile(input.getOpcodes(),output));
        System.out.println(args[0]+": verified 7 scoped icon-source/raster methods; pixel transport and layer metadata preserved");
    }
}
