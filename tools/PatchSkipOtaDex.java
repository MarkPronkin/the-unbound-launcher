import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Force the original developer skip-OTA decision and bypass forced update UI. */
public final class PatchSkipOtaDex {
    static final String MANAGER="Lai/nreal/nebula/manager/XREALManager;";
    static final String PRES="Lai/nreal/nebula/activity/splash/ota/OtaPresenterImpl;";
    static final String SP="Lai/nreal/nebula/util/sp/SPUtils;";
    static final String CONTROL="Lai/nreal/glasses/control/OtaManager;";
    static final String QUEUED="lambda$_goToOta$3$ai-nreal-nebula-activity-splash-ota-OtaPresenterImpl";
    static String key(Method m) {
        StringBuilder value=new StringBuilder(m.getDefiningClass()+"->"+m.getName()+"(");
        for(CharSequence p:m.getParameterTypes())value.append(p);
        return value.append(")").append(m.getReturnType()).toString();
    }
    static final Set<String> TARGETS=Set.of(MANAGER+"->getOtaNum(Ljava/lang/String;)I",
        PRES+"->_goToOta(Ljava/lang/String;I)V",PRES+"->"+QUEUED+"(Ljava/lang/String;I)V");
    static byte[] bytes(ClassDef cls)throws Exception {
        var store=new MemoryDataStore();
        DexPool.writeTo(store,new ImmutableDexFile(Opcodes.forApi(33),List.of(cls)));return store.getData();
    }
    static byte[] body(Method m)throws Exception {
        return bytes(new ImmutableClassDef(m.getDefiningClass(),1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),List.of(m)));
    }
    static Method patch(Method m) {
        var impl=m.getImplementation();List<Instruction> code=new ArrayList<>();
        int registers=impl.getRegisterCount();
        boolean decision=m.getDefiningClass().equals(MANAGER);
        if(decision) {
            // Retain model/SKU initialization and all instruction addresses. The stock
            // toggle still probes native firmware before returning zero; skip that probe too.
            Integer result=null;int preferences=0,probes=0;
            for(Instruction instruction:impl.getInstructions()) {
                if(result!=null) {
                    if(instruction.getOpcode()!=Opcode.MOVE_RESULT||!(instruction instanceof OneRegisterInstruction move)
                        ||move.getRegisterA()>15)throw new IllegalStateException("Unexpected OTA result register");
                    code.add(new ImmutableInstruction11n(Opcode.CONST_4,move.getRegisterA(),result));
                    result=null;continue;
                }
                if(instruction instanceof ReferenceInstruction ref&&ref.getReference() instanceof MethodReference call) {
                    boolean preference=call.getDefiningClass().equals(SP)&&call.getName().equals("getBoolean");
                    boolean probe=call.getDefiningClass().equals(CONTROL)&&call.getName().equals("getOtaNum");
                    if(preference||probe) {
                        if(instruction.getOpcode()!=Opcode.INVOKE_VIRTUAL||!call.getParameterTypes().equals(List.of("Ljava/lang/String;"))
                            ||!call.getReturnType().equals(preference?"Z":"I"))throw new IllegalStateException("Unexpected skip-OTA call");
                        for(int i=0;i<instruction.getCodeUnits();i++)code.add(new ImmutableInstruction10x(Opcode.NOP));
                        result=preference?1:0;
                        if(preference)preferences++;else probes++;
                        continue;
                    }
                }
                code.add(instruction);
            }
            if(result!=null||preferences!=1||probes!=1)throw new IllegalStateException("Missing stock skip-OTA decision/probe");
        } else {
            // checkCloudFw is already replaced by the connection adapter's prepare
            // entry. It queues SDK work on the presenter handler even when a forced
            // update callback arrives on the UI thread, and ignores duplicate requests.
            registers=3;
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_DIRECT,1,0,0,0,0,0,
                new ImmutableMethodReference(PRES,"checkCloudFw",List.of(),"V")));
            code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        }
        return new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),
            m.getAnnotations(),m.getHiddenApiRestrictions(),new ImmutableMethodImplementation(registers,code,
                decision?impl.getTryBlocks():List.of(),decision?impl.getDebugItems():List.of()));
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!Set.of("patch","restore").contains(args[0]))throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore=args[0].equals("restore");var baseline=DexFileFactory.loadDexFile(args[3],Opcodes.forApi(33));
        var input=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));Map<String,Method> originals=new HashMap<>();
        int preparations=0;
        for(ClassDef cls:baseline.getClasses())for(Method m:cls.getMethods()) {
            if(TARGETS.contains(key(m)))originals.put(key(m),m);
            if(key(m).equals(PRES+"->checkCloudFw()V")) {
                List<Instruction> code=new ArrayList<>();m.getImplementation().getInstructions().forEach(code::add);
                if(code.size()!=2||code.get(0).getOpcode()!=Opcode.INVOKE_STATIC||code.get(1).getOpcode()!=Opcode.RETURN_VOID
                    ||!(code.get(0) instanceof ReferenceInstruction ref)||!(ref.getReference() instanceof MethodReference call)
                    ||!call.getDefiningClass().equals("Ldev/beampro/port/GlassesConnectionCompat;")||!call.getName().equals("prepare"))
                    throw new IllegalStateException("Skip OTA requires the connection worker preparation hook first");
                preparations++;
            }
        }
        if(preparations!=1)throw new IllegalStateException("Missing connection worker preparation hook");
        if(!originals.keySet().equals(TARGETS))throw new IllegalStateException("Missing skip-OTA methods");
        List<ClassDef> classes=new ArrayList<>();Set<String> edits=new HashSet<>();
        for(ClassDef cls:input.getClasses()) {
            List<Method> methods=new ArrayList<>();boolean changed=false;
            for(Method m:cls.getMethods()) {
                Method before=originals.get(key(m));
                if(before==null){methods.add(m);continue;}
                Method after=patch(before);
                if(!Arrays.equals(body(m),body(restore?after:before)))throw new IllegalStateException("Unexpected skip-OTA body: "+key(m));
                methods.add(restore?before:after);changed=true;edits.add(key(m));
            }
            classes.add(changed?new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),
                cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods):cls);
        }
        if(!edits.equals(TARGETS))throw new IllegalStateException("Missing skip-OTA edits");
        if(restore) {
            Map<String,ClassDef> originalClasses=new HashMap<>();for(ClassDef cls:baseline.getClasses())originalClasses.put(cls.getType(),cls);
            for(ClassDef cls:classes) {
                ClassDef before=originalClasses.remove(cls.getType());
                if(before==null||!Arrays.equals(bytes(cls),bytes(before)))throw new IllegalStateException("Unrelated skip-OTA DEX change: "+cls.getType());
            }
            if(!originalClasses.isEmpty())throw new IllegalStateException("Missing unrelated classes");
            Files.write(Path.of(args[2]),Files.readAllBytes(Path.of(args[3])));
        } else DexPool.writeTo(args[2],new ImmutableDexFile(input.getOpcodes(),classes));
        System.out.println(args[0]+": skip OTA always enabled; firmware probe removed, SKU retained, normal/forced update UI bypassed");
    }
}
