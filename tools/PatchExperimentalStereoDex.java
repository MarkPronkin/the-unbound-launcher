import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Filter every original selector return; retain the complete per-model decision. */
public final class PatchExperimentalStereoDex {
    static final String MANAGER = "Lai/nreal/nebula/manager/XREALManager;";
    static final String POLICY = "Ldev/beampro/settings/ExperimentalStereo;";
    static boolean target(Method method) {
        return method.getDefiningClass().equals(MANAGER) && method.getName().equals("getFinalDisplayConfig");
    }
    static byte[] bytes(ClassDef cls) throws Exception {
        var store = new MemoryDataStore();
        DexPool.writeTo(store, new ImmutableDexFile(Opcodes.forApi(33), List.of(cls)));
        return store.getData();
    }
    static byte[] body(Method method) throws Exception {
        var impl = method.getImplementation();
        var normalized = new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
            method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
            new ImmutableMethodImplementation(impl.getRegisterCount(), impl.getInstructions(), impl.getTryBlocks(), List.of()));
        var store = new MemoryDataStore();
        DexPool.writeTo(store, new ImmutableDexFile(Opcodes.forApi(33), List.of(new ImmutableClassDef(
            MANAGER, 1, "Ljava/lang/Object;", List.of(), null, Set.of(), List.of(), List.of(normalized)))));
        return store.getData();
    }
    static Method patch(Method method) {
        if (!method.getParameterTypes().isEmpty() || !method.getReturnType().equals("I")
                || method.getImplementation() == null || method.getImplementation().getRegisterCount() > 16)
            throw new IllegalStateException("Unexpected display selector signature");
        var mutable = new MutableMethodImplementation(method.getImplementation());
        for (var instruction : mutable.getInstructions())
            if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference call
                    && call.getDefiningClass().equals(POLICY))
                throw new IllegalStateException("Experimental stereo already patched");
        int returns = 0, owner = mutable.getRegisterCount() - 1;
        // Insert backwards so each original RETURN (and its incoming branches)
        // becomes the filter call, with the actual return immediately after it.
        for (int index = mutable.getInstructions().size() - 1; index >= 0; index--) {
            var instruction = mutable.getInstructions().get(index);
            if (instruction.getOpcode() != Opcode.RETURN) continue;
            int value = ((OneRegisterInstruction) instruction).getRegisterA();
            mutable.replaceInstruction(index, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2,
                value, owner, 0, 0, 0, new ImmutableMethodReference(POLICY, "selectConfig",
                    List.of("I", "Ljava/lang/Object;"), "I")));
            mutable.addInstruction(index + 1, new BuilderInstruction11x(Opcode.MOVE_RESULT, value));
            mutable.addInstruction(index + 2, new BuilderInstruction11x(Opcode.RETURN, value));
            returns++;
        }
        if (returns != 1) throw new IllegalStateException("Expected one shared model-selector return: " + returns);
        return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(), method.getReturnType(),
            method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), mutable);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("patch", "restore").contains(args[0]))
            throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore = args[0].equals("restore");
        var reference = DexFileFactory.loadDexFile(args[3], Opcodes.forApi(33));
        var input = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        Method original = null;
        for (ClassDef cls : reference.getClasses()) for (Method method : cls.getMethods()) if (target(method)) {
            if (original != null) throw new IllegalStateException("Duplicate selector");
            original = method;
        }
        if (original == null) throw new IllegalStateException("Missing selector");
        Method patched = patch(original);
        List<ClassDef> classes = new ArrayList<>(); int count = 0;
        for (ClassDef cls : input.getClasses()) {
            List<Method> methods = new ArrayList<>(); boolean changed = false;
            for (Method method : cls.getMethods()) {
                if (!target(method)) { methods.add(method); continue; }
                if (!Arrays.equals(body(method), body(restore ? patched : original)))
                    throw new IllegalStateException("Unexpected display selector body");
                methods.add(restore ? original : patched); count++; changed = true;
            }
            classes.add(changed ? new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(),
                cls.getInterfaces(), cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods) : cls);
        }
        if (count != 1) throw new IllegalStateException("Expected one selector");
        if (restore) {
            // Prove every unrelated class and member is preserved.
            Map<String, ClassDef> originals = new HashMap<>();
            for (ClassDef cls : reference.getClasses()) originals.put(cls.getType(), cls);
            for (ClassDef cls : classes) {
                ClassDef before = originals.remove(cls.getType());
                if (before == null || !Arrays.equals(bytes(cls), bytes(before)))
                    throw new IllegalStateException("Unrelated stereo DEX change: " + cls.getType());
            }
            if (!originals.isEmpty()) throw new IllegalStateException("Missing unrelated classes");
            Files.write(Path.of(args[2]), Files.readAllBytes(Path.of(args[3])));
        } else DexPool.writeTo(args[2], new ImmutableDexFile(input.getOpcodes(), classes));
        System.out.println(args[0] + ": verified optional stereo filter on the original shared model-selector return");
    }
}
