import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Replace one USB request call; retain the SDK map, receiver, callback and control flow. */
public final class PatchUsbPermissionDex {
    static final String OWNER="Lcom/xreal/nebula/controlmanager/control/helper/UsbPermissionHelper;";
    static final String DEVICE="Landroid/hardware/usb/UsbDevice;",PENDING="Landroid/app/PendingIntent;";
    static boolean target(Method method) {
        return method.getDefiningClass().equals(OWNER) && method.getName().equals("requestPermission")
                && method.getParameterTypes().equals(List.of(DEVICE,OWNER.substring(0,OWNER.length()-1)+"$OnGrantedResult;"))
                && method.getReturnType().equals("V");
    }
    static byte[] bytes(ClassDef cls)throws Exception {
        var store=new MemoryDataStore();DexPool.writeTo(store,new ImmutableDexFile(Opcodes.forApi(33),List.of(cls)));return store.getData();
    }
    static byte[] body(Method m)throws Exception {
        return bytes(new ImmutableClassDef(m.getDefiningClass(),1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),List.of(m)));
    }
    static Method patch(Method method) {
        var impl=method.getImplementation();
        if(impl==null || impl.getRegisterCount()!=11)throw new IllegalStateException("USB helper registers changed");
        List<Instruction> code=new ArrayList<>();int hits=0;boolean context=false;
        for(Instruction instruction:impl.getInstructions()) {
            if(instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof FieldReference field
                    &&field.getDefiningClass().equals(OWNER)&&field.getName().equals("mContext")
                    &&instruction.getOpcode()==Opcode.IGET_OBJECT&&((Instruction22c)instruction).getRegisterA()==0)
                context=true;
            if(instruction instanceof ReferenceInstruction ref&&ref.getReference() instanceof MethodReference call
                    &&call.getDefiningClass().equals("Landroid/hardware/usb/UsbManager;")&&call.getName().equals("requestPermission")) {
                if(!context||instruction.getOpcode()!=Opcode.INVOKE_VIRTUAL||!(instruction instanceof Instruction35c invoke)
                        ||invoke.getRegisterCount()!=3||invoke.getRegisterC()!=8||invoke.getRegisterD()!=9||invoke.getRegisterE()!=10
                        ||!call.getParameterTypes().equals(List.of(DEVICE,PENDING))||!call.getReturnType().equals("V"))
                    throw new IllegalStateException("Unexpected USB request call/context");
                instruction=new ImmutableInstruction35c(Opcode.INVOKE_STATIC,4,0,8,9,10,0,
                        new ImmutableMethodReference("Ldev/beampro/port/UsbPermissionCompat;","requestPermission",
                                List.of("Landroid/content/Context;","Landroid/hardware/usb/UsbManager;",DEVICE,PENDING),"V"));
                hits++;
            }
            code.add(instruction);
        }
        if(hits!=1)throw new IllegalStateException("Expected one USB request call");
        return new ImmutableMethod(OWNER,method.getName(),method.getParameters(),method.getReturnType(),method.getAccessFlags(),
                method.getAnnotations(),method.getHiddenApiRestrictions(),
                new ImmutableMethodImplementation(impl.getRegisterCount(),code,impl.getTryBlocks(),impl.getDebugItems()));
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!Set.of("patch","restore").contains(args[0]))throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore=args[0].equals("restore");var base=DexFileFactory.loadDexFile(args[3],Opcodes.forApi(33));
        var input=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));Method before=null;
        for(ClassDef cls:base.getClasses())for(Method method:cls.getMethods())if(target(method)) {
            if(before!=null)throw new IllegalStateException("Duplicate USB method");before=method;
        }
        if(before==null)throw new IllegalStateException("Missing USB baseline method");
        Method after=patch(before);List<ClassDef> output=new ArrayList<>();int edits=0;
        for(ClassDef cls:input.getClasses()) {
            if(!cls.getType().equals(OWNER)){output.add(cls);continue;}
            List<Method> methods=new ArrayList<>();
            for(Method method:cls.getMethods()) {
                if(!target(method)){methods.add(method);continue;}
                if(!Arrays.equals(body(method),body(restore?after:before)))throw new IllegalStateException("Unexpected USB method body");
                methods.add(restore?before:after);edits++;
            }
            output.add(new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods));
        }
        if(edits!=1)throw new IllegalStateException("Missing USB hook");
        if(restore) {
            Map<String,ClassDef> originals=new HashMap<>();for(ClassDef cls:base.getClasses())originals.put(cls.getType(),cls);
            for(ClassDef cls:output){ClassDef original=originals.remove(cls.getType());
                if(original==null||!Arrays.equals(bytes(cls),bytes(original)))throw new IllegalStateException("Unrelated USB DEX changes: "+cls.getType());}
            if(!originals.isEmpty())throw new IllegalStateException("Missing unrelated classes");
            Files.write(Path.of(args[2]),Files.readAllBytes(Path.of(args[3])));
        } else DexPool.writeTo(args[2],new ImmutableDexFile(input.getOpcodes(),output));
        System.out.println(args[0]+": verified one USB request hook; original callback and permission flow preserved");
    }
}
