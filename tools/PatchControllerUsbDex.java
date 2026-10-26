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

/** Cover the lower-level SDK, retry timer, and dormant OTA USB requests. */
public final class PatchControllerUsbDex {
    static final Map<String,Integer> TARGETS=Map.of(
        "Lai/nreal/glasses/control/api;->b(Landroid/content/Context;I)V",1,
        "Lai/nreal/glasses/control/api$c;->run()V",1,
        "Lai/nreal/glasses/control/OtaManagerWrap;->a(IILjava/lang/String;)I",2);
    static String key(Method method){StringBuilder s=new StringBuilder(method.getDefiningClass()+"->"+method.getName()+"(");
        for(CharSequence p:method.getParameterTypes())s.append(p);return s.append(")").append(method.getReturnType()).toString();}
    static byte[] bytes(ClassDef cls)throws Exception{var store=new MemoryDataStore();
        DexPool.writeTo(store,new ImmutableDexFile(Opcodes.forApi(33),List.of(cls)));return store.getData();}
    static byte[] body(Method m)throws Exception{return bytes(new ImmutableClassDef(m.getDefiningClass(),1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),List.of(m)));}
    static Method patch(Method method){
        var impl=method.getImplementation();List<Instruction> code=new ArrayList<>();int count=0;
        for(Instruction instruction:impl.getInstructions()){
            if(instruction instanceof ReferenceInstruction ref&&ref.getReference() instanceof MethodReference call
                    &&call.getDefiningClass().equals("Landroid/hardware/usb/UsbManager;")&&call.getName().equals("requestPermission")){
                if(!call.getParameterTypes().equals(List.of("Landroid/hardware/usb/UsbDevice;","Landroid/app/PendingIntent;"))||!call.getReturnType().equals("V"))
                    throw new IllegalStateException("Unexpected controller USB signature");
                var target=new ImmutableMethodReference("Ldev/beampro/port/UsbPermissionCompat;","requestPermission",
                        List.of("Landroid/hardware/usb/UsbManager;","Landroid/hardware/usb/UsbDevice;","Landroid/app/PendingIntent;"),"V");
                if(instruction.getOpcode()==Opcode.INVOKE_VIRTUAL&&instruction instanceof Instruction35c invoke&&invoke.getRegisterCount()==3)
                    instruction=new ImmutableInstruction35c(Opcode.INVOKE_STATIC,3,invoke.getRegisterC(),invoke.getRegisterD(),invoke.getRegisterE(),0,0,target);
                else if(instruction.getOpcode()==Opcode.INVOKE_VIRTUAL_RANGE&&instruction instanceof Instruction3rc invoke&&invoke.getRegisterCount()==3)
                    instruction=new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE,invoke.getStartRegister(),3,target);
                else throw new IllegalStateException("Unexpected controller USB invocation");
                count++;
            }
            code.add(instruction);
        }
        if(count!=TARGETS.get(key(method)))throw new IllegalStateException("Missing controller USB calls: "+key(method));
        return new ImmutableMethod(method.getDefiningClass(),method.getName(),method.getParameters(),method.getReturnType(),method.getAccessFlags(),
                method.getAnnotations(),method.getHiddenApiRestrictions(),new ImmutableMethodImplementation(impl.getRegisterCount(),code,impl.getTryBlocks(),impl.getDebugItems()));
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=4||!Set.of("patch","restore").contains(args[0]))throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore=args[0].equals("restore");var base=DexFileFactory.loadDexFile(args[3],Opcodes.forApi(33));
        var input=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));Map<String,Method> originals=new HashMap<>();
        for(ClassDef cls:base.getClasses())for(Method method:cls.getMethods())if(TARGETS.containsKey(key(method)))originals.put(key(method),method);
        if(!originals.keySet().equals(TARGETS.keySet()))throw new IllegalStateException("Missing controller baseline methods");
        List<ClassDef> output=new ArrayList<>();Set<String> edits=new HashSet<>();
        for(ClassDef cls:input.getClasses()){
            List<Method> methods=new ArrayList<>();boolean changed=false;
            for(Method method:cls.getMethods()){
                Method before=originals.get(key(method));if(before==null){methods.add(method);continue;}
                Method after=patch(before);
                if(!Arrays.equals(body(method),body(restore?after:before)))throw new IllegalStateException("Unexpected controller method: "+key(method));
                methods.add(restore?before:after);edits.add(key(method));changed=true;
            }
            output.add(changed?new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods):cls);
        }
        if(!edits.equals(TARGETS.keySet()))throw new IllegalStateException("Missing controller edits");
        if(restore){
            Map<String,ClassDef> classes=new HashMap<>();for(ClassDef cls:base.getClasses())classes.put(cls.getType(),cls);
            for(ClassDef cls:output){ClassDef original=classes.remove(cls.getType());if(original==null||!Arrays.equals(bytes(cls),bytes(original)))throw new IllegalStateException("Unrelated controller DEX change: "+cls.getType());}
            if(!classes.isEmpty())throw new IllegalStateException("Missing unrelated controller classes");
            Files.write(Path.of(args[2]),Files.readAllBytes(Path.of(args[3])));
        }else DexPool.writeTo(args[2],new ImmutableDexFile(input.getOpcodes(),output));
        System.out.println(args[0]+": verified four controller USB calls; original callbacks and OTA blocks retained");
    }
}
