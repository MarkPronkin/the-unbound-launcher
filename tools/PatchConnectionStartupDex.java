import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Audited recovery around the original SDK, preserving all native/model display defaults. */
public final class PatchConnectionStartupDex {
    static final String PRES="Lai/nreal/nebula/activity/splash/ota/OtaPresenterImpl;";
    static final String OTA="Lai/nreal/nebula/activity/splash/ota/OTAActivity;";
    static final String MANAGER="Lai/nreal/nebula/manager/XREALManager;";
    static final String COMPAT="Ldev/beampro/port/GlassesConnectionCompat;";
    static final Map<String,String> WRAPPERS=Map.of(
        PRES+"->checkUsb()V","begin", PRES+"->checkCloudFw()V","prepare",
        PRES+"->_goToSdk()V","startSdk", PRES+"->clickConfirm()V","confirm",
        PRES+"->onNewIntent()V","newIntent",
        OTA+"->onBackPressed()V","cancel",
        PRES.substring(0,PRES.length()-1)+"$2;->onDisplayRemove(III)V","usbDisplayRemoved");
    static boolean launchTarget(Method m) {
        return (m.getDefiningClass().equals(MANAGER) && m.getName().startsWith("lambda$startSdk$12$"))
            || (m.getDefiningClass().equals(MANAGER.substring(0,MANAGER.length()-1)+"$4;")
                && Set.of("onDisplayAdd","onDisplayRemove").contains(m.getName()));
    }
    static boolean screenTarget(Method m) {
        return m.getDefiningClass().equals(OTA) && (key(m).equals(OTA+"->isHalfWindow()Z")
            || key(m).equals(OTA+"->showUsbStatus(Lai/nreal/nebula/activity/splash/ota/status/OtaStatus;I)V"));
    }
    static String key(Method m) {
        StringBuilder b=new StringBuilder(m.getDefiningClass()+"->"+m.getName()+"(");
        for(CharSequence p:m.getParameterTypes())b.append(p);
        return b.append(")").append(m.getReturnType()).toString();
    }
    static byte[] bytes(ClassDef cls)throws Exception {
        var store=new MemoryDataStore();DexPool.writeTo(store,new ImmutableDexFile(Opcodes.forApi(33),List.of(cls)));return store.getData();
    }
    static byte[] body(Method m)throws Exception {
        return bytes(new ImmutableClassDef(m.getDefiningClass(),1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),List.of(m)));
    }
    static Method patch(Method m) {
        var impl=m.getImplementation();List<Instruction> code=new ArrayList<>();
        String wrapper=WRAPPERS.get(key(m));
        int registers=impl.getRegisterCount();
        if(screenTarget(m)) {
            if(m.getName().equals("isHalfWindow")) {
                registers=1;
                code.add(new ImmutableInstruction11n(Opcode.CONST_4,0,0));
                code.add(new ImmutableInstruction11x(Opcode.RETURN,0));
            } else {
                registers=3;
                code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC,2,0,1,0,0,0,
                    new ImmutableMethodReference(COMPAT,"status",List.of("Landroid/app/Activity;","Ljava/lang/Object;"),"V")));
                code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
            }
        } else if(wrapper!=null) {
            registers=wrapper.equals("usbDisplayRemoved")?4:1;
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC,1,0,0,0,0,0,
                new ImmutableMethodReference(COMPAT,wrapper,List.of(wrapper.equals("cancel")?"Landroid/app/Activity;":"Ljava/lang/Object;"),"V")));
            code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        } else {
            int count=0;
            for(Instruction instruction:impl.getInstructions()) {
                if(instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference call
                    && call.getDefiningClass().equals(MANAGER) && call.getName().equals("goLauncher")) {
                    if(instruction.getOpcode()!=Opcode.INVOKE_VIRTUAL || !(instruction instanceof Instruction35c invoke)
                        ||invoke.getRegisterCount()!=1||!call.getParameterTypes().isEmpty()||!call.getReturnType().equals("V"))
                        throw new IllegalStateException("Unexpected asynchronous launch signature");
                    instruction=new ImmutableInstruction35c(Opcode.INVOKE_STATIC,1,invoke.getRegisterC(),0,0,0,0,
                        new ImmutableMethodReference(COMPAT,"launch",List.of("Ljava/lang/Object;"),"V"));
                    count++;
                }
                code.add(instruction);
            }
            if(count!=1)throw new IllegalStateException("Expected one asynchronous launch in "+key(m));
        }
        MethodImplementation result=new ImmutableMethodImplementation(registers,code,
            wrapper==null&&!screenTarget(m)?impl.getTryBlocks():List.of(),wrapper==null&&!screenTarget(m)?impl.getDebugItems():List.of());
        if(m.getDefiningClass().equals(MANAGER)&&m.getName().startsWith("lambda$startSdk$12$")) {
            if(!m.getParameterTypes().equals(List.of("Lai/nreal/nebula/manager/callback/ISdkStartResult;","Z"))||registers<4)
                throw new IllegalStateException("Unexpected SDK result callback parameters");
            var mutable=new MutableMethodImplementation(result);
            var proceed=mutable.newLabelForIndex(0);
            mutable.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,registers-2,1,
                new ImmutableMethodReference(COMPAT,"acceptSdkCallback",List.of("Ljava/lang/Object;"),"Z")));
            mutable.addInstruction(1,new BuilderInstruction11x(Opcode.MOVE_RESULT,0));
            mutable.addInstruction(2,new BuilderInstruction21t(Opcode.IF_NEZ,0,proceed));
            mutable.addInstruction(3,new BuilderInstruction10x(Opcode.RETURN_VOID));
            if(((com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction)mutable.getInstructions().get(2)).getCodeOffset()!=3)
                throw new IllegalStateException("SDK callback guard does not branch to the original body");
            result=mutable;
        }
        return new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),
            m.getAnnotations(),m.getHiddenApiRestrictions(),result);
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!Set.of("patch","restore").contains(args[0]))throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore=args[0].equals("restore");var baseline=DexFileFactory.loadDexFile(args[3],Opcodes.forApi(33));
        var input=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));Map<String,Method> originals=new HashMap<>();
        int launches=0;
        for(ClassDef cls:baseline.getClasses())for(Method m:cls.getMethods())if(WRAPPERS.containsKey(key(m))||launchTarget(m)||screenTarget(m)) {
            if(launchTarget(m))launches++;
            originals.put(key(m),m);
        }
        if(originals.size()!=12||launches!=3)throw new IllegalStateException("Missing connection baseline methods: "+originals.keySet());
        List<ClassDef> classes=new ArrayList<>();Set<String> edits=new HashSet<>();
        for(ClassDef cls:input.getClasses()) {
            List<Method> methods=new ArrayList<>();boolean changed=false;
            for(Method m:cls.getMethods()) {
                Method before=originals.get(key(m));
                if(before==null){methods.add(m);continue;}
                Method after=patch(before);
                if(!Arrays.equals(body(m),body(restore?after:before)))throw new IllegalStateException("Unexpected connection body: "+key(m));
                methods.add(restore?before:after);changed=true;edits.add(key(m));
            }
            classes.add(changed?new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods):cls);
        }
        if(!edits.equals(originals.keySet()))throw new IllegalStateException("Missing connection edits");
        if(restore) {
            Map<String,ClassDef> originalClasses=new HashMap<>();for(ClassDef cls:baseline.getClasses())originalClasses.put(cls.getType(),cls);
            for(ClassDef cls:classes){ClassDef before=originalClasses.remove(cls.getType());
                if(before==null||!Arrays.equals(bytes(cls),bytes(before)))throw new IllegalStateException("Unrelated connection DEX change: "+cls.getType());}
            if(!originalClasses.isEmpty())throw new IllegalStateException("Missing unrelated classes");
            Files.write(Path.of(args[2]),Files.readAllBytes(Path.of(args[3])));
        } else DexPool.writeTo(args[2],new ImmutableDexFile(input.getOpcodes(),classes));
        System.out.println(args[0]+": verified twelve connection and full-screen UI hooks; native SDK and model selector preserved");
    }
}
