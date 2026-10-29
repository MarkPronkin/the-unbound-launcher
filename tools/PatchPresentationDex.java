import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Guards around SDK entry/lifecycle methods route AR through the phone-owned Presentation path;
 *  each original body stays intact behind its guard, and the exact baseline is restorable. */
public final class PatchPresentationDex {
    static final String XR="Lai/nreal/activitylife/NRXRApp;", COMPAT="Ldev/beampro/port/PresentationOnlyCompat;";
    static final String PHONE="Lai/nreal/nebula/space/LaunchSpaceAcrivity;";
    static final Set<String> PHONE_TARGETS=Set.of("onWindowFocusChanged","onPause","onStop");
    static final Set<String> TARGETS=Set.of("startUnityActivityInternal","moveToVirtualDisplay",
            "readyForDynamicSwitchDPImpl","readyForRestartSessionImpl","getFakeActivity");
    static byte[] bytes(ClassDef cls)throws Exception {
        var store=new MemoryDataStore();DexPool.writeTo(store,new ImmutableDexFile(Opcodes.forApi(33),List.of(cls)));return store.getData();
    }
    static byte[] body(Method m)throws Exception {
        return bytes(new ImmutableClassDef(m.getDefiningClass(),1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),List.of(m)));
    }
    static Method patched(Method m) {
        if(m.getDefiningClass().equals(PHONE))return phoneLifecycle(m);
        String name=m.getName();boolean getter=name.equals("getFakeActivity"),start=name.equals("startUnityActivityInternal");
        List<String> expected=start?List.of("Landroid/content/Intent;"):List.of();
        String result=getter?"Landroid/app/Activity;":name.startsWith("readyFor")?"Z":"V";
        if(!m.getParameterTypes().equals(expected)||!m.getReturnType().equals(result)
                ||(((m.getAccessFlags()&8)!=0)!=getter))throw new IllegalStateException("Unexpected SDK signature: "+m);
        int parameters=getter?0:start?2:1,registers=m.getImplementation().getRegisterCount();
        if(registers<=parameters)throw new IllegalStateException("Guard needs an unused entry scratch register");
        String method=getter?"renderingActivity":start?"startUnity":name.equals("moveToVirtualDisplay")?"prepareSwitch":
                name.equals("readyForDynamicSwitchDPImpl")?"dynamicReady":"restartReady";
        List<String> arguments=getter?List.of():start?List.of("Ljava/lang/Object;","Landroid/content/Intent;"):List.of("Ljava/lang/Object;");
        String returned=getter?result:result.equals("Z")?"I":"Z";
        var code=new MutableMethodImplementation(m.getImplementation());
        code.addInstruction(0,new BuilderInstruction10x(Opcode.NOP));
        code.addInstruction(0,new BuilderInstruction10x(Opcode.NOP));
        code.addInstruction(0,new BuilderInstruction10x(Opcode.NOP));
        code.addInstruction(0,new BuilderInstruction10x(Opcode.NOP));
        var original=code.newLabelForIndex(4);
        code.replaceInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,getter?0:registers-parameters,
                parameters,new ImmutableMethodReference(COMPAT,method,arguments,returned)));
        code.replaceInstruction(1,new BuilderInstruction11x(getter?Opcode.MOVE_RESULT_OBJECT:Opcode.MOVE_RESULT,0));
        code.replaceInstruction(2,new BuilderInstruction21t(result.equals("Z")?Opcode.IF_LTZ:Opcode.IF_EQZ,0,original));
        code.replaceInstruction(3,result.equals("V")?new BuilderInstruction10x(Opcode.RETURN_VOID):
                new BuilderInstruction11x(getter?Opcode.RETURN_OBJECT:Opcode.RETURN,0));
        return new ImmutableMethod(m.getDefiningClass(),name,m.getParameters(),m.getReturnType(),m.getAccessFlags(),
                m.getAnnotations(),m.getHiddenApiRestrictions(),code);
    }
    static Method phoneLifecycle(Method m) {
        boolean focus=m.getName().equals("onWindowFocusChanged"),stop=m.getName().equals("onStop");
        if(!PHONE_TARGETS.contains(m.getName())||!m.getParameterTypes().equals(focus?List.of("Z"):List.of())||!m.getReturnType().equals("V"))
            throw new IllegalStateException("Unexpected phone lifecycle signature");
        // The SDK overwrites p0 with mUnityPlayer before this call, so only the call's own registers are reliable.
        var original=m.getImplementation();int focuses=0,pauses=0;
        List<Instruction> code=new ArrayList<>();
        for(Instruction instruction:original.getInstructions()) {
            if(instruction instanceof ReferenceInstruction ref&&ref.getReference() instanceof MethodReference call
                    &&call.getDefiningClass().equals("Lcom/unity3d/player/UnityPlayer;")
                    &&Set.of("windowFocusChanged","pause").contains(call.getName())) {
                boolean pause=call.getName().equals("pause");
                if(!(instruction instanceof Instruction35c invoke)||invoke.getRegisterCount()!=(pause?1:2)
                        ||instruction.getOpcode()!=Opcode.INVOKE_VIRTUAL||!call.getParameterTypes().equals(pause?List.of():List.of("Z"))
                        ||!call.getReturnType().equals("V"))throw new IllegalStateException("Unexpected Unity lifecycle invocation");
                instruction=new ImmutableInstruction35c(Opcode.INVOKE_STATIC,pause?1:2,invoke.getRegisterC(),pause?0:invoke.getRegisterD(),0,0,0,
                        new ImmutableMethodReference(COMPAT,pause?"pause":"windowFocus",pause?List.of("Ljava/lang/Object;"):List.of("Ljava/lang/Object;","Z"),"V"));
                if(pause)pauses++;else focuses++;
            }
            code.add(instruction);
        }
        if(focuses!=(stop?0:1)||pauses!=(focus?0:stop?2:1))throw new IllegalStateException("Unexpected Unity lifecycle call counts");
        return new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),m.getAnnotations(),
                m.getHiddenApiRestrictions(),new ImmutableMethodImplementation(original.getRegisterCount(),code,original.getTryBlocks(),original.getDebugItems()));
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!Set.of("patch","restore").contains(args[0]))throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore=args[0].equals("restore");
        var baseline=DexFileFactory.loadDexFile(args[3],Opcodes.forApi(33));
        Map<String,Method> targets=new HashMap<>();Map<String,ClassDef> reference=new HashMap<>();
        for(ClassDef cls:baseline.getClasses()) {
            reference.put(cls.getType(),cls);
            for(Method method:cls.getMethods())if((cls.getType().equals(XR)&&TARGETS.contains(method.getName()))
                    ||(cls.getType().equals(PHONE)&&PHONE_TARGETS.contains(method.getName())))
                if(targets.put(cls.getType()+"->"+method.getName(),method)!=null)throw new IllegalStateException("Duplicate method");
        }
        if(targets.size()!=8)throw new IllegalStateException("Missing presentation lifecycle methods");
        var input=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));List<ClassDef> output=new ArrayList<>();Set<String> edits=new HashSet<>();
        for(ClassDef cls:input.getClasses()) {
            if(!cls.getType().equals(XR)&&!cls.getType().equals(PHONE)){output.add(cls);continue;}
            List<Method> methods=new ArrayList<>();
            for(Method method:cls.getMethods()) {
                String key=cls.getType()+"->"+method.getName();Method before=targets.get(key);
                if(before==null){methods.add(method);continue;}
                Method after=patched(before);
                if(!Arrays.equals(body(method),body(restore?after:before)))throw new IllegalStateException("Unexpected lifecycle method: "+method.getName());
                methods.add(restore?before:after);edits.add(key);
            }
            output.add(new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),
                    cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods));
        }
        if(!edits.equals(targets.keySet()))throw new IllegalStateException("Missing guards");
        if(restore) {
            for(ClassDef cls:output) {
                ClassDef original=reference.remove(cls.getType());
                if(original==null||!Arrays.equals(bytes(cls),bytes(original)))throw new IllegalStateException("Unrelated DEX change: "+cls.getType());
            }
            if(!reference.isEmpty())throw new IllegalStateException("Missing original class");
            Files.write(Path.of(args[2]),Files.readAllBytes(Path.of(args[3])));
        } else DexPool.writeTo(args[2],new ImmutableDexFile(input.getOpcodes(),output));
        System.out.println(args[0]+": verified five Presentation entry guards and three phone focus/pause hooks; original bodies kept and restorable");
    }
}
