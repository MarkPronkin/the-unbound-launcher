import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Keep the controller's Beam-only pointer flag in private state instead of Settings.Global. */
public final class PatchControllerModeDex {
    private static final String SERVICE = "Lcom/xreal/evapro/JobService;";
    private static final List<String> PARAMETERS = List.of("Landroid/content/ContentResolver;", "Ljava/lang/String;", "I");

    private static boolean target(Method method) {
        return method.getDefiningClass().equals(SERVICE) && method.getName().equals("onStartCommand")
                && method.getParameterTypes().equals(List.of("Landroid/content/Intent;", "I", "I"))
                && method.getReturnType().equals("I");
    }

    private static byte[] bytes(ClassDef cls) throws Exception {
        var store = new MemoryDataStore();
        DexPool.writeTo(store, new ImmutableDexFile(Opcodes.forApi(33), List.of(cls)));
        return store.getData();
    }

    private static byte[] body(Method method) throws Exception {
        return bytes(new ImmutableClassDef(SERVICE, 1, "Ljava/lang/Object;", List.of(), null,
                Set.of(), List.of(), List.of(method)));
    }

    private static Method patch(Method method) {
        var implementation = method.getImplementation();
        List<Instruction> code = new ArrayList<>();
        Set<String> markers = new HashSet<>();
        int edits = 0;
        for (Instruction instruction : implementation.getInstructions()) {
            if (instruction instanceof ReferenceInstruction ref) {
                if (ref.getReference() instanceof StringReference text) markers.add(text.getString());
                if (ref.getReference() instanceof MethodReference call
                        && call.getDefiningClass().equals("Landroid/provider/Settings$Global;")
                        && call.getName().equals("putInt")) {
                    if (!call.getParameterTypes().equals(PARAMETERS) || !call.getReturnType().equals("Z"))
                        throw new IllegalStateException("Unexpected pointer flag write signature");
                    var replacement = new ImmutableMethodReference("Ldev/beampro/port/SettingsCompat;",
                            "putGlobalInt", PARAMETERS, "Z");
                    if (instruction.getOpcode() == Opcode.INVOKE_STATIC && instruction instanceof Instruction35c invoke
                            && invoke.getRegisterCount() == 3) {
                        instruction = new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, invoke.getRegisterC(),
                                invoke.getRegisterD(), invoke.getRegisterE(), invoke.getRegisterF(), invoke.getRegisterG(), replacement);
                    } else if (instruction.getOpcode() == Opcode.INVOKE_STATIC_RANGE && instruction instanceof Instruction3rc invoke
                            && invoke.getRegisterCount() == 3) {
                        instruction = new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, invoke.getStartRegister(), 3, replacement);
                    } else throw new IllegalStateException("Unexpected pointer flag write opcode");
                    edits++;
                }
            }
            code.add(instruction);
        }
        if (edits != 1 || !markers.containsAll(Set.of("evapro.job.ACTION_MOUSE_CTRL", "xreal_hide_pointicon")))
            throw new IllegalStateException("Expected exactly one Beam controller pointer flag write");
        return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
                new ImmutableMethodImplementation(implementation.getRegisterCount(), code,
                        implementation.getTryBlocks(), implementation.getDebugItems()));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("patch", "restore").contains(args[0]))
            throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore = args[0].equals("restore");
        var baseline = DexFileFactory.loadDexFile(args[3], Opcodes.forApi(33));
        var input = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        Method original = null;
        for (ClassDef cls : baseline.getClasses()) for (Method method : cls.getMethods()) if (target(method)) {
            if (original != null) throw new IllegalStateException("Duplicate controller service entry point");
            original = method;
        }
        if (original == null) throw new IllegalStateException("Missing controller service entry point");
        Method patched = patch(original);
        List<ClassDef> output = new ArrayList<>();
        int edits = 0;
        for (ClassDef cls : input.getClasses()) {
            List<Method> methods = new ArrayList<>();
            boolean changed = false;
            for (Method method : cls.getMethods()) {
                if (!target(method)) { methods.add(method); continue; }
                if (!Arrays.equals(body(method), body(restore ? patched : original)))
                    throw new IllegalStateException("Unexpected controller service body");
                methods.add(restore ? original : patched);
                edits++;
                changed = true;
            }
            output.add(changed ? new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(),
                    cls.getInterfaces(), cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods) : cls);
        }
        if (edits != 1) throw new IllegalStateException("Expected one controller service edit");
        if (restore) {
            Map<String, ClassDef> originals = new HashMap<>();
            for (ClassDef cls : baseline.getClasses()) originals.put(cls.getType(), cls);
            for (ClassDef cls : output) {
                ClassDef before = originals.remove(cls.getType());
                if (before == null || !Arrays.equals(bytes(cls), bytes(before)))
                    throw new IllegalStateException("Unrelated controller DEX change: " + cls.getType());
            }
            if (!originals.isEmpty()) throw new IllegalStateException("Missing unrelated controller DEX classes");
            Files.write(Path.of(args[2]), Files.readAllBytes(Path.of(args[3])));
        } else DexPool.writeTo(args[2], new ImmutableDexFile(input.getOpcodes(), output));
        System.out.println(args[0] + ": one Beam pointer flag routed to private state; service behavior and remaining DEX preserved");
    }
}
