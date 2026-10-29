import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Keep notification delivery independent of privileged PendingIntent inspection. */
public final class PatchNotificationsDex {
    static final String TARGET="Lcom/xreal/evapro/NotifyHelper;";
    static boolean target(Method method) {
        return method.getDefiningClass().equals(TARGET) && method.getName().equals("getIntent")
            && method.getParameterTypes().equals(List.of("Lcom/xreal/evapro/NotifyInfo;"))
            && method.getReturnType().equals("Landroid/content/Intent;");
    }
    static byte[] bytes(ClassDef cls) throws Exception {
        var store=new MemoryDataStore();
        DexPool.writeTo(store,new ImmutableDexFile(Opcodes.forApi(33),List.of(cls)));return store.getData();
    }
    static byte[] body(Method method) throws Exception {
        return bytes(new ImmutableClassDef(method.getDefiningClass(),1,"Ljava/lang/Object;",List.of(),null,
            Set.of(),List.of(),List.of(method)));
    }
    static Method patch(Method method) {
        Set<String> privileged=new HashSet<>();
        for(Instruction instruction:method.getImplementation().getInstructions()) {
            if(instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference call
                    && call.getDefiningClass().equals("Lcom/xreal/evapro/ActivityManagerProxy;"))privileged.add(call.getName());
        }
        if(!privileged.equals(Set.of("getInstance","getTarget","isIntentSenderAnActivity","getIntentForIntentSender")))
            throw new IllegalStateException("Unexpected stock notification intent inspection");
        // Null is the stock Unity renderer's supported Event_Native_App_Open path.
        // It uses getCreatorPackage(), preserving the sending app as the tap target.
        // The original extraction needs system privileges and throws before Enqueue
        // on normal phones. Displaying a notification must never depend on it.
        return new ImmutableMethod(method.getDefiningClass(),method.getName(),method.getParameters(),method.getReturnType(),
            method.getAccessFlags(),method.getAnnotations(),method.getHiddenApiRestrictions(),
            new ImmutableMethodImplementation(3,List.of(new ImmutableInstruction11n(Opcode.CONST_4,0,0),
                new ImmutableInstruction11x(Opcode.RETURN_OBJECT,0)),List.of(),List.of()));
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4 || !Set.of("patch","restore").contains(args[0]))
            throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore=args[0].equals("restore");
        var input=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));
        var baseline=DexFileFactory.loadDexFile(args[3],Opcodes.forApi(33));
        Method original=null;
        for(ClassDef cls:baseline.getClasses())for(Method method:cls.getMethods())if(target(method)) {
            if(original!=null)throw new IllegalStateException("Duplicate notification adapter");original=method;
        }
        if(original==null)throw new IllegalStateException("Missing notification adapter");
        Method patched=patch(original);List<ClassDef> output=new ArrayList<>();int edits=0;
        for(ClassDef cls:input.getClasses()) {
            List<Method> methods=new ArrayList<>();boolean changed=false;
            for(Method method:cls.getMethods()) {
                if(!target(method)){methods.add(method);continue;}
                if(!Arrays.equals(body(method),body(restore?patched:original)))
                    throw new IllegalStateException("Unexpected notification adapter body");
                methods.add(restore?original:patched);edits++;changed=true;
            }
            output.add(changed?new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),
                cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods):cls);
        }
        if(edits!=1)throw new IllegalStateException("Missing notification adapter edit");
        if(restore) {
            Map<String,ClassDef> originals=new HashMap<>();for(ClassDef cls:baseline.getClasses())originals.put(cls.getType(),cls);
            for(ClassDef cls:output) {
                ClassDef before=originals.remove(cls.getType());
                if(before==null||!Arrays.equals(bytes(cls),bytes(before)))
                    throw new IllegalStateException("Unrelated notification DEX change: "+cls.getType());
            }
            if(!originals.isEmpty())throw new IllegalStateException("Missing unrelated classes");
            Files.write(Path.of(args[2]),Files.readAllBytes(Path.of(args[3])));
        } else DexPool.writeTo(args[2],new ImmutableDexFile(input.getOpcodes(),output));
        System.out.println(args[0]+": notification delivery uses native-app tap fallback; callbacks, access and unrelated DEX preserved");
    }
}
