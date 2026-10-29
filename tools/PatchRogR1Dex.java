import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Exact R1 identity aliases; preserve all original per-model paths and USB descriptors. */
public final class PatchRogR1Dex {
    static final String USB="Landroid/hardware/usb/UsbDevice;", INPUT="Landroid/view/InputDevice;";
    static final String COMPAT="Ldev/beampro/port/RogR1Compat;";
    static final String IDENTITY="Ldev/beampro/compat/GlassesIdentity;";
    static final String HELPER="Lcom/xreal/nebula/controlmanager/control/helper/UsbPermissionHelper;";
    static String key(Method m) { return m.getDefiningClass()+"->"+m.getName()+m.getParameterTypes(); }
    static int target(Method m) {
        String cls=m.getDefiningClass(),name=m.getName();var params=m.getParameterTypes();
        if(cls.equals("Lcom/xreal/glassesdisplayplugevent/helper/GlassesTypeUtil;")
            && name.equals("isAir") && params.equals(List.of(USB)))return 1;
        if(cls.equals("Lcom/xreal/glassesdisplayplugevent/display/DisplayModel;")
            && name.equals("_equalsPidVid") && params.equals(Collections.nCopies(3,"Ljava/lang/String;")))return 2;
        if(cls.equals(HELPER) && ((name.equals("hasPermission") && params.isEmpty())
            || (name.equals("requestPermission") && params.equals(List.of(HELPER.substring(0,HELPER.length()-1)+"$OnGrantedResult;")))))return 3;
        if(cls.equals("Lcom/xreal/nebula/controlmanager/control/glasses/AirGlasses;")
            && name.equals("getTypeString") && params.equals(List.of(USB)))return 3;
        if(cls.equals("Lcom/xreal/evapro/InputHelper;") && name.equals("hasExternalMouse") && params.isEmpty())return 4;
        return 0;
    }
    static byte[] bytes(ClassDef cls)throws Exception {var store=new MemoryDataStore();
        DexPool.writeTo(store,new ImmutableDexFile(Opcodes.forApi(33),List.of(cls)));return store.getData();}
    static byte[] body(Method m)throws Exception {return bytes(new ImmutableClassDef(m.getDefiningClass(),1,
        "Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),List.of(m)));}
    static Method patch(Method m) {
        int kind=target(m),edits=0;var mutable=new MutableMethodImplementation(m.getImplementation());
        int registers=mutable.getRegisterCount();
        if(registers>16)throw new IllegalStateException("Unexpected identity register count: "+key(m));
        for(var instruction:mutable.getInstructions())
            if(instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference call
                && (call.getDefiningClass().equals(COMPAT)||call.getDefiningClass().equals(IDENTITY)))
                throw new IllegalStateException("R1 identity already patched");
        if(kind<=2) {
            // D8 reuses incoming argument registers later in both methods.
            // Inspect their identity before the original body overwrites them.
            // v0 is a local in both pinned methods, never an incoming argument.
            int parameters=kind==1?1:4;
            if(registers<=parameters)throw new IllegalStateException("Missing identity scratch register");
            var originalEntry=mutable.newLabelForIndex(0);
            var call=kind==1 ? new ImmutableMethodReference(COMPAT,"sdkUsbType",List.of("I",USB),"I")
                : new ImmutableMethodReference(IDENTITY,"sdkDisplayType",List.of("I","Ljava/lang/String;","Ljava/lang/String;"),"I");
            mutable.addInstruction(0,new BuilderInstruction11x(Opcode.RETURN,0));
            mutable.addInstruction(0,new BuilderInstruction21t(Opcode.IF_LTZ,0,originalEntry));
            mutable.addInstruction(0,new BuilderInstruction11x(Opcode.MOVE_RESULT,0));
            mutable.addInstruction(0,new BuilderInstruction35c(Opcode.INVOKE_STATIC,kind==1?2:3,
                0,kind==1?registers-1:registers-2,kind==1?0:registers-1,0,0,call));
            mutable.addInstruction(0,new BuilderInstruction11n(Opcode.CONST_4,0,-1));
            return new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),
                m.getAnnotations(),m.getHiddenApiRestrictions(),mutable);
        }
        for(int i=mutable.getInstructions().size()-1;i>=0;i--) {
            var instruction=mutable.getInstructions().get(i);
            if(instruction instanceof ReferenceInstruction ref
                && ref.getReference() instanceof MethodReference call && call.getName().equals("getVendorId")
                && call.getDefiningClass().equals(kind==4?INPUT:USB)) {
                if(instruction.getOpcode()!=Opcode.INVOKE_VIRTUAL || !(instruction instanceof Instruction35c invoke)
                    || invoke.getRegisterCount()!=1 || !call.getParameterTypes().isEmpty() || !call.getReturnType().equals("I"))
                    throw new IllegalStateException("Unexpected vendor check");
                mutable.replaceInstruction(i,new BuilderInstruction35c(Opcode.INVOKE_STATIC,1,invoke.getRegisterC(),0,0,0,0,
                    new ImmutableMethodReference(COMPAT,kind==4?"sdkInputVendor":"sdkUsbVendor",List.of(kind==4?INPUT:USB),"I")));edits++;
            }
        }
        if(edits<1 || (kind>=3 && edits!=1))throw new IllegalStateException("Unexpected R1 hook count: "+key(m));
        return new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),
            m.getAnnotations(),m.getHiddenApiRestrictions(),mutable);
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!Set.of("patch","restore").contains(args[0]))throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore=args[0].equals("restore");var base=DexFileFactory.loadDexFile(args[3],Opcodes.forApi(33));
        var input=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));Map<String,Method> originals=new HashMap<>();
        for(ClassDef cls:base.getClasses())for(Method m:cls.getMethods())if(target(m)>0)
            if(originals.put(key(m),m)!=null)throw new IllegalStateException("Duplicate identity method");
        if(originals.size()!=6)throw new IllegalStateException("Expected six SDK identity methods: "+originals.keySet());
        List<ClassDef> output=new ArrayList<>();Set<String> edits=new HashSet<>();
        for(ClassDef cls:input.getClasses()) {
            List<Method> methods=new ArrayList<>();boolean changed=false;
            for(Method m:cls.getMethods()) {
                Method original=originals.get(key(m));
                if(original==null){methods.add(m);continue;}
                Method patched=patch(original);
                if(!Arrays.equals(body(m),body(restore?patched:original)))throw new IllegalStateException("Unexpected identity body: "+key(m));
                methods.add(restore?original:patched);edits.add(key(m));changed=true;
            }
            output.add(changed?new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),
                cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods):cls);
        }
        if(!edits.equals(originals.keySet()))throw new IllegalStateException("Missing SDK identity hook");
        if(restore) {
            Map<String,ClassDef> untouched=new HashMap<>();for(ClassDef cls:base.getClasses())untouched.put(cls.getType(),cls);
            for(ClassDef cls:output) {ClassDef before=untouched.remove(cls.getType());
                if(before==null||!Arrays.equals(bytes(cls),bytes(before)))throw new IllegalStateException("Unrelated R1 DEX change: "+cls.getType());}
            if(!untouched.isEmpty())throw new IllegalStateException("Missing unrelated classes");
            Files.write(Path.of(args[2]),Files.readAllBytes(Path.of(args[3])));
        } else DexPool.writeTo(args[2],new ImmutableDexFile(input.getOpcodes(),output));
        System.out.println(args[0]+": verified six R1 identity hooks; other models and native startup preserved");
    }
}
