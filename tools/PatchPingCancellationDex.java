import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Guard the original ping callback and complete cancellation after original USB cleanup. */
public final class PatchPingCancellationDex {
    static final String HELPER="Lcom/xreal/glasses/servicesdk/helper/PingGSeriesHelper;";
    static final String CALLBACK="Lcom/xreal/glasses/servicesdk/helper/PingGSeriesHelper$PingCallback;";
    static final String COMPAT="Ldev/beampro/port/PingCallbackCompat;";
    static boolean target(Method m) { return m.getDefiningClass().equals(HELPER) && m.getReturnType().equals("V")
        && ((m.getName().equals("waitPingGlasses")&&m.getParameterTypes().equals(List.of(CALLBACK)))
            || (m.getName().equals("onRelease")&&m.getParameterTypes().isEmpty())); }
    static byte[] bytes(ClassDef cls)throws Exception { var store=new MemoryDataStore();
        DexPool.writeTo(store,new ImmutableDexFile(Opcodes.forApi(33),List.of(cls)));return store.getData(); }
    static byte[] body(Method m)throws Exception { return bytes(new ImmutableClassDef(m.getDefiningClass(),1,
        "Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),List.of(m))); }
    static Method patch(Method m) {
        var original=m.getImplementation();
        int registers=original.getRegisterCount();
        boolean release=m.getName().equals("onRelease");
        var impl=new MutableMethodImplementation(new ImmutableMethodImplementation(registers+(release?1:0),
            original.getInstructions(),original.getTryBlocks(),original.getDebugItems()));
        if(m.getName().equals("waitPingGlasses")) {
            if(registers<2||registers>255)throw new IllegalStateException("Unexpected ping parameter registers");
            impl.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,registers-2,2,
                new ImmutableMethodReference(COMPAT,"wrap",List.of("Ljava/lang/Object;","Ljava/lang/Object;"),"Ljava/lang/Object;")));
            impl.addInstruction(1,new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,registers-1));
            impl.addInstruction(2,new BuilderInstruction21c(Opcode.CHECK_CAST,registers-1,new ImmutableTypeReference(CALLBACK)));
        } else {
            if(registers<1||registers>255)throw new IllegalStateException("Unexpected ping cleanup registers");
            // D8 reuses the original incoming register for LogControl's Object[]
            // before returning. Keep the receiver in the new, untouched incoming
            // register, copying it to the old slot for the original instructions.
            impl.addInstruction(0,new BuilderInstruction22x(Opcode.MOVE_OBJECT_FROM16,registers-1,registers));
            int returns=0;
            for(int i=impl.getInstructions().size()-1;i>=0;i--)if(impl.getInstructions().get(i).getOpcode()==Opcode.RETURN_VOID) {
                impl.addInstruction(i,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,registers,1,
                    new ImmutableMethodReference(COMPAT,"released",List.of("Ljava/lang/Object;"),"V")));
                returns++;
            }
            if(returns!=1)throw new IllegalStateException("Unexpected ping cleanup returns");
        }
        return new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),
            m.getAnnotations(),m.getHiddenApiRestrictions(),impl);
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!Set.of("patch","restore").contains(args[0]))throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore=args[0].equals("restore");var base=DexFileFactory.loadDexFile(args[3],Opcodes.forApi(33));
        var input=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));Map<String,Method> originals=new HashMap<>();
        for(ClassDef cls:base.getClasses())for(Method m:cls.getMethods())if(target(m))originals.put(m.getName(),m);
        if(originals.size()!=2)throw new IllegalStateException("Missing SDK ping lifecycle methods");
        List<ClassDef> output=new ArrayList<>();int edits=0;
        for(ClassDef cls:input.getClasses()) {
            List<Method> methods=new ArrayList<>();boolean changed=false;
            for(Method m:cls.getMethods()) {
                if(!target(m)){methods.add(m);continue;}
                Method original=originals.get(m.getName()),patched=patch(original);
                if(!Arrays.equals(body(m),body(restore?patched:original)))throw new IllegalStateException("Unexpected ping method body: "+m.getName());
                methods.add(restore?original:patched);edits++;changed=true;
            }
            output.add(changed?new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),
                cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods):cls);
        }
        if(edits!=2)throw new IllegalStateException("Missing ping cancellation edits");
        if(restore) {
            Map<String,ClassDef> classes=new HashMap<>();for(ClassDef cls:base.getClasses())classes.put(cls.getType(),cls);
            for(ClassDef cls:output) {ClassDef before=classes.remove(cls.getType());
                if(before==null||!Arrays.equals(bytes(cls),bytes(before)))throw new IllegalStateException("Unrelated ping DEX change: "+cls.getType());}
            if(!classes.isEmpty())throw new IllegalStateException("Missing unrelated classes");
            Files.write(Path.of(args[2]),Files.readAllBytes(Path.of(args[3])));
        } else DexPool.writeTo(args[2],new ImmutableDexFile(input.getOpcodes(),output));
        System.out.println(args[0]+": verified two ping lifecycle guards; original command, timeout and native startup preserved");
    }
}
