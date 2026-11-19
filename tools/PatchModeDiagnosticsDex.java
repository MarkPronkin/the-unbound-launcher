import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Observe only the three native writes in set2D3D; preserve every instruction address. */
public final class PatchModeDiagnosticsDex {
    static final String SDK="Lcom/xreal/glasses/servicesdk/NRServiceControl;";
    static final String CONTROL="Lcom/xreal/glasses/api/Control;";
    static final String COMPAT="Ldev/beampro/port/GlassesModeDiagnostics;";
    static boolean target(Method m) {return m.getDefiningClass().equals(SDK)
        &&m.getName().equals("set2D3D")&&m.getParameterTypes().equals(List.of("I"))&&m.getReturnType().equals("Z");}
    static byte[] bytes(ClassDef cls)throws Exception {var store=new MemoryDataStore();
        DexPool.writeTo(store,new ImmutableDexFile(Opcodes.forApi(33),List.of(cls)));return store.getData();}
    static byte[] body(Method m)throws Exception {return bytes(new ImmutableClassDef(m.getDefiningClass(),1,
        "Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),List.of(m)));}
    static Method patch(Method m) {
        var impl=m.getImplementation();List<Instruction> code=new ArrayList<>();int edits=0;
        Map<String,String> wrappers=Map.of("nativeSetDpCurrentEdid","setEdid",
                "nativeSetDpInputModeEx","setInputMode","nativeSet2D3DMode","setLegacy");
        Set<String> found=new HashSet<>();
        for(Instruction instruction:impl.getInstructions()) {
            if(instruction instanceof ReferenceInstruction ref&&ref.getReference() instanceof MethodReference call
                    &&call.getDefiningClass().equals(CONTROL)&&wrappers.containsKey(call.getName())) {
                boolean input=call.getName().equals("nativeSetDpInputModeEx");
                if(!call.getParameterTypes().equals(input?List.of("I","I"):List.of("I"))||!call.getReturnType().equals("Z")
                    ||instruction.getOpcode()!=Opcode.INVOKE_VIRTUAL||!(instruction instanceof Instruction35c invoke)
                    ||invoke.getRegisterCount()!=(input?3:2)||!found.add(call.getName()))
                    throw new IllegalStateException("Unexpected mode command signature");
                code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC,invoke.getRegisterCount(),
                    invoke.getRegisterC(),invoke.getRegisterD(),input?invoke.getRegisterE():0,0,0,
                    new ImmutableMethodReference(COMPAT,wrappers.get(call.getName()),
                        input?List.of("Ljava/lang/Object;","I","I"):List.of("Ljava/lang/Object;","I"),"Z")));
                edits++;
            } else code.add(instruction);
        }
        if(edits!=3||!found.equals(wrappers.keySet()))throw new IllegalStateException("Expected three distinct native mode writes");
        return new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),
            m.getAnnotations(),m.getHiddenApiRestrictions(),new ImmutableMethodImplementation(impl.getRegisterCount(),
                code,impl.getTryBlocks(),impl.getDebugItems()));
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!Set.of("patch","restore").contains(args[0]))throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore=args[0].equals("restore");var base=DexFileFactory.loadDexFile(args[3],Opcodes.forApi(33));
        var input=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));Method original=null;
        for(ClassDef cls:base.getClasses())for(Method m:cls.getMethods())if(target(m)) {
            if(original!=null)throw new IllegalStateException("Duplicate SDK mode setter");original=m;
        }
        if(original==null)throw new IllegalStateException("Missing SDK mode setter");
        Method patched=patch(original);List<ClassDef> output=new ArrayList<>();int edits=0;
        for(ClassDef cls:input.getClasses()) {
            List<Method> methods=new ArrayList<>();boolean changed=false;
            for(Method m:cls.getMethods()) {
                if(!target(m)){methods.add(m);continue;}
                if(!Arrays.equals(body(m),body(restore?patched:original)))throw new IllegalStateException("Unexpected SDK mode setter body");
                methods.add(restore?original:patched);edits++;changed=true;
            }
            output.add(changed?new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),
                cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods):cls);
        }
        if(edits!=1)throw new IllegalStateException("Missing SDK mode setter edit");
        if(restore) {
            Map<String,ClassDef> originals=new HashMap<>();for(ClassDef cls:base.getClasses())originals.put(cls.getType(),cls);
            for(ClassDef cls:output) {ClassDef before=originals.remove(cls.getType());
                if(before==null||!Arrays.equals(bytes(cls),bytes(before)))throw new IllegalStateException("Unrelated mode diagnostics DEX change: "+cls.getType());}
            if(!originals.isEmpty())throw new IllegalStateException("Missing unrelated classes");
            Files.write(Path.of(args[2]),Files.readAllBytes(Path.of(args[3])));
        } else DexPool.writeTo(args[2],new ImmutableDexFile(input.getOpcodes(),output));
        System.out.println(args[0]+": verified three native mode-write observers; arguments, results and error reporting preserved");
    }
}
