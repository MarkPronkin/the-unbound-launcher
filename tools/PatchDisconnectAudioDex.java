import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Remove only the Beam Pro system-only audio broadcast that crashes an ordinary app on unplug. */
public final class PatchDisconnectAudioDex {
    static final String TARGET="Lcom/xreal/evapro/JobService$1;";
    static boolean target(Method m) { return m.getDefiningClass().equals(TARGET)&&m.getName().equals("run")
        &&m.getParameterTypes().isEmpty()&&m.getReturnType().equals("V"); }
    static byte[] bytes(ClassDef cls)throws Exception {var store=new MemoryDataStore();
        DexPool.writeTo(store,new ImmutableDexFile(Opcodes.forApi(33),List.of(cls)));return store.getData();}
    static byte[] body(Method m)throws Exception {return bytes(new ImmutableClassDef(m.getDefiningClass(),1,
        "Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),List.of(m)));}
    static Method patch(Method m) {
        var impl=m.getImplementation();List<Instruction> code=new ArrayList<>();int broadcasts=0,actions=0;
        for(Instruction instruction:impl.getInstructions()) {
            if(instruction instanceof ReferenceInstruction ref) {
                if(ref.getReference() instanceof StringReference text&&text.getString().equals("android.media.AUDIO_BECOMING_NOISY"))actions++;
                if(ref.getReference() instanceof MethodReference call&&call.getName().equals("sendBroadcast")) {
                    if(!call.getDefiningClass().equals("Lcom/xreal/evapro/JobService;")
                        ||!call.getParameterTypes().equals(List.of("Landroid/content/Intent;"))||!call.getReturnType().equals("V")
                        ||instruction.getOpcode()!=Opcode.INVOKE_VIRTUAL
                        ||!(instruction instanceof Instruction35c invoke)||invoke.getRegisterCount()!=2)
                        throw new IllegalStateException("Unexpected disconnect broadcast signature");
                    // Keep all code addresses, registers, debug and exception ranges unchanged.
                    for(int i=0;i<instruction.getCodeUnits();i++)code.add(new ImmutableInstruction10x(Opcode.NOP));
                    broadcasts++;continue;
                }
            }
            code.add(instruction);
        }
        if(broadcasts!=1||actions!=1)throw new IllegalStateException("Expected exactly one protected disconnect audio hint");
        return new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),
            m.getAnnotations(),m.getHiddenApiRestrictions(),new ImmutableMethodImplementation(impl.getRegisterCount(),code,impl.getTryBlocks(),impl.getDebugItems()));
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!Set.of("patch","restore").contains(args[0]))throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore=args[0].equals("restore");var base=DexFileFactory.loadDexFile(args[3],Opcodes.forApi(33));
        var input=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));Method original=null;
        for(ClassDef cls:base.getClasses())for(Method m:cls.getMethods())if(target(m)) {
            if(original!=null)throw new IllegalStateException("Duplicate disconnect task");original=m;
        }
        if(original==null)throw new IllegalStateException("Missing disconnect task");
        Method patched=patch(original);List<ClassDef> output=new ArrayList<>();int edits=0;
        for(ClassDef cls:input.getClasses()) {
            List<Method> methods=new ArrayList<>();boolean changed=false;
            for(Method m:cls.getMethods()) {
                if(!target(m)){methods.add(m);continue;}
                if(!Arrays.equals(body(m),body(restore?patched:original)))throw new IllegalStateException("Unexpected disconnect task body");
                methods.add(restore?original:patched);edits++;changed=true;
            }
            output.add(changed?new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),
                cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods):cls);
        }
        if(edits!=1)throw new IllegalStateException("Missing disconnect edit");
        if(restore) {
            Map<String,ClassDef> originals=new HashMap<>();for(ClassDef cls:base.getClasses())originals.put(cls.getType(),cls);
            for(ClassDef cls:output) {ClassDef before=originals.remove(cls.getType());
                if(before==null||!Arrays.equals(bytes(cls),bytes(before)))throw new IllegalStateException("Unrelated disconnect DEX change: "+cls.getType());}
            if(!originals.isEmpty())throw new IllegalStateException("Missing unrelated classes");
            Files.write(Path.of(args[2]),Files.readAllBytes(Path.of(args[3])));
        } else DexPool.writeTo(args[2],new ImmutableDexFile(input.getOpcodes(),output));
        System.out.println(args[0]+": verified one protected disconnect audio broadcast removed; remaining service and DEX preserved");
    }
}
