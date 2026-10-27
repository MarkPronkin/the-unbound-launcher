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

/** Replace only the fixed ping process block; SDK polling, callback and native startup stay intact. */
public final class PatchEthernetPingDex {
    static final String TARGET="Lcom/xreal/glasses/servicesdk/helper/PingGSeriesHelper$2;";
    static boolean target(Method m) { return m.getDefiningClass().equals(TARGET)&&m.getName().equals("run")
        &&m.getParameterTypes().isEmpty()&&m.getReturnType().equals("V"); }
    static byte[] bytes(ClassDef cls)throws Exception {var store=new MemoryDataStore();
        DexPool.writeTo(store,new ImmutableDexFile(Opcodes.forApi(33),List.of(cls)));return store.getData();}
    static byte[] body(Method m)throws Exception {return bytes(new ImmutableClassDef(m.getDefiningClass(),1,
        "Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),List.of(m)));}
    static Method patch(Method m) {
        var impl=m.getImplementation();List<Instruction> before=new ArrayList<>();impl.getInstructions().forEach(before::add);
        Opcode[] expected={Opcode.INVOKE_STATIC,Opcode.MOVE_RESULT_OBJECT,Opcode.CONST_STRING,Opcode.INVOKE_VIRTUAL,
            Opcode.MOVE_RESULT_OBJECT,Opcode.CONST_WIDE_16,Opcode.SGET_OBJECT,Opcode.INVOKE_VIRTUAL,
            Opcode.MOVE_RESULT,Opcode.IF_EQZ,Opcode.INVOKE_VIRTUAL,Opcode.MOVE_RESULT,Opcode.IF_NEZ,Opcode.MOVE,Opcode.GOTO,Opcode.MOVE};
        if(impl.getRegisterCount()!=8||before.size()!=108)throw new IllegalStateException("Unexpected SDK ping worker shape");
        int start=6,end=22,units=0;
        for(int i=start;i<end;i++) {if(before.get(i).getOpcode()!=expected[i-start])throw new IllegalStateException("Unexpected SDK ping instruction "+i);units+=before.get(i).getCodeUnits();}
        if(units!=29 || !(before.get(8) instanceof ReferenceInstruction r)
                || !(r.getReference() instanceof StringReference s) || !s.getString().equals("ping -I eth0 -c 1 -W 1 169.254.2.1"))
            throw new IllegalStateException("SDK readiness probe changed");
        String[] refs={"Ljava/lang/Runtime;->getRuntime()Ljava/lang/Runtime;","Ljava/lang/Runtime;->exec(Ljava/lang/String;)Ljava/lang/Process;",
            "Ljava/lang/Process;->waitFor(JLjava/util/concurrent/TimeUnit;)Z","Ljava/lang/Process;->exitValue()I"};
        int[] indices={6,9,13,16};
        for(int i=0;i<indices.length;i++)if(!(before.get(indices[i]) instanceof ReferenceInstruction apiRef)
                || !apiRef.getReference().toString().equals(refs[i]))throw new IllegalStateException("Unexpected ping process API");
        int at=0;
        for(int i=0;i<before.size();i++) {Instruction n=before.get(i);
            if((i<start||i>=end)&&n instanceof OffsetInstruction jump&&at+jump.getCodeOffset()>=11&&at+jump.getCodeOffset()<40)
                throw new IllegalStateException("External branch enters ping process block");at+=n.getCodeUnits();}
        List<Instruction> code=new ArrayList<>(before.subList(0,start));
        code.add(new com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE,7,1,
            new com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference("Ldev/beampro/port/EthernetReadinessCompat;","ping",List.of("Ljava/lang/Object;"),"Z")));
        code.add(new com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x(Opcode.MOVE_RESULT,2));
        for(int i=4;i<units;i++)code.add(new ImmutableInstruction10x(Opcode.NOP));
        code.addAll(before.subList(end,before.size()));
        return new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),
            m.getAnnotations(),m.getHiddenApiRestrictions(),new ImmutableMethodImplementation(impl.getRegisterCount(),code,impl.getTryBlocks(),impl.getDebugItems()));
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!Set.of("patch","restore").contains(args[0]))throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore=args[0].equals("restore");var base=DexFileFactory.loadDexFile(args[3],Opcodes.forApi(33));
        var input=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));Method original=null;
        for(ClassDef cls:base.getClasses())for(Method m:cls.getMethods())if(target(m)) {
            if(original!=null)throw new IllegalStateException("Duplicate Ethernet ping worker");original=m;
        }
        if(original==null)throw new IllegalStateException("Missing Ethernet ping worker");
        Method patched=patch(original);List<ClassDef> output=new ArrayList<>();int edits=0;
        for(ClassDef cls:input.getClasses()) {
            List<Method> methods=new ArrayList<>();boolean changed=false;
            for(Method m:cls.getMethods()) {
                if(!target(m)){methods.add(m);continue;}
                if(!Arrays.equals(body(m),body(restore?patched:original)))throw new IllegalStateException("Unexpected Ethernet ping worker body");
                methods.add(restore?original:patched);edits++;changed=true;
            }
            output.add(changed?new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),
                cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods):cls);
        }
        if(edits!=1)throw new IllegalStateException("Missing Ethernet ping edit");
        if(restore) {
            Map<String,ClassDef> originals=new HashMap<>();for(ClassDef cls:base.getClasses())originals.put(cls.getType(),cls);
            for(ClassDef cls:output) {ClassDef before=originals.remove(cls.getType());
                if(before==null||!Arrays.equals(bytes(cls),bytes(before)))throw new IllegalStateException("Unrelated Ethernet ping DEX change: "+cls.getType());}
            if(!originals.isEmpty())throw new IllegalStateException("Missing unrelated classes");
            Files.write(Path.of(args[2]),Files.readAllBytes(Path.of(args[3])));
        } else DexPool.writeTo(args[2],new ImmutableDexFile(input.getOpcodes(),output));
        System.out.println(args[0]+": verified fixed Ethernet ping delegated; SDK polling, timeout, callbacks and unrelated DEX preserved");
    }
}
