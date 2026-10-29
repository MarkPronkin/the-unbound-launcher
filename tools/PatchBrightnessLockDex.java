import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Route the stock brightness callback through the lock-state filter; keep the observer and setting read. */
public final class PatchBrightnessLockDex {
    private static final String OBSERVER = "Lcom/xreal/evapro/ScreenBrightnessMonitor$1;";
    private static final String CALLBACK = "Lcom/xreal/evapro/ScreenBrightnessMonitor$Callback;";
    private static final String COMPAT = "Ldev/beampro/port/BrightnessLockCompat;";

    private static byte[] bytes(ClassDef cls) throws Exception {
        var store = new MemoryDataStore();
        DexPool.writeTo(store, new ImmutableDexFile(Opcodes.forApi(33), List.of(cls)));
        return store.getData();
    }

    private static ClassDef patched(ClassDef cls) {
        if (!cls.getType().equals(OBSERVER)) return cls;
        List<Method> methods = new ArrayList<>();
        int edits = 0;
        for (Method method : cls.getMethods()) {
            if (!method.getName().equals("onChange")) { methods.add(method); continue; }
            if (!method.getParameterTypes().equals(List.of("Z", "Landroid/net/Uri;"))
                    || !method.getReturnType().equals("V")) throw new IllegalStateException("Unexpected observer signature");
            var body = new MutableMethodImplementation(method.getImplementation());
            int index = -1, position = 0;
            FiveRegisterInstruction call = null;
            for (Instruction instruction : method.getImplementation().getInstructions()) {
                if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference target) {
                    if (target.getDefiningClass().equals(COMPAT)) throw new IllegalStateException("Brightness filter already present");
                    if (target.getDefiningClass().equals(CALLBACK) && target.getName().equals("onBrightnessChanged")) {
                        if (index >= 0 || instruction.getOpcode() != Opcode.INVOKE_INTERFACE
                                || !target.getParameterTypes().equals(List.of("I")) || !target.getReturnType().equals("V"))
                            throw new IllegalStateException("Unexpected brightness callback");
                        index = position;
                        call = (FiveRegisterInstruction) instruction;
                    }
                }
                position++;
            }
            if (index < 0) throw new IllegalStateException("Missing brightness callback");
            if (call.getRegisterCount() != 2) throw new IllegalStateException("Unexpected callback registers");
            // Same receiver and value registers; the static filter decides whether to forward.
            body.replaceInstruction(index, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2,
                    call.getRegisterC(), call.getRegisterD(), 0, 0, 0, new ImmutableMethodReference(COMPAT,
                    "onBrightnessChanged", List.of("Ljava/lang/Object;", "I"), "V")));
            methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                    method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body));
            edits++;
        }
        if (edits != 1) throw new IllegalStateException("Expected one brightness observer");
        return new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("patch", "restore").contains(args[0]))
            throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore = args[0].equals("restore");
        var input = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        var baseline = DexFileFactory.loadDexFile(args[3], Opcodes.forApi(33));
        Map<String, ClassDef> expected = new HashMap<>();
        Set<String> changed = new HashSet<>();
        for (ClassDef cls : baseline.getClasses()) {
            ClassDef replacement = patched(cls);
            if (replacement != cls) changed.add(cls.getType());
            expected.put(cls.getType(), restore ? replacement : cls);
        }
        if (!changed.equals(Set.of(OBSERVER))) throw new IllegalStateException("Expected only the brightness observer");
        List<ClassDef> output = new ArrayList<>();
        for (ClassDef cls : input.getClasses()) {
            ClassDef reference = expected.remove(cls.getType());
            if (reference == null || !Arrays.equals(bytes(cls), bytes(reference)))
                throw new IllegalStateException("Unexpected brightness DEX change: " + cls.getType());
            output.add(restore ? cls : patched(cls));
        }
        if (!expected.isEmpty()) throw new IllegalStateException("Missing DEX classes");
        if (restore) Files.write(Path.of(args[2]), Files.readAllBytes(Path.of(args[3])));
        else DexPool.writeTo(args[2], new ImmutableDexFile(input.getOpcodes(), output));
        System.out.println(args[0] + ": brightness lock filter on the stock observer; remaining DEX preserved");
    }
}
