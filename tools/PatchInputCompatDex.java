import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Preserve stock input callbacks; replace only IME classification and private flag access. */
public final class PatchInputCompatDex {
    static final String LAYOUT = "Lai/nreal/nebula/service/InputDetectService$1$1;";
    static final String INPUT = "Lcom/xreal/evapro/InputHelper;";
    static final String RESOLVER = "Landroid/content/ContentResolver;";
    static int target(Method m) {
        if (m.getDefiningClass().equals(LAYOUT) && m.getName().equals("onGlobalLayout")
                && m.getParameterTypes().isEmpty() && m.getReturnType().equals("V")) return 1;
        if (m.getDefiningClass().equals(INPUT)) {
            if (m.getName().equals("hidePointerIcon") && m.getParameterTypes().equals(List.of("Z", "Z"))
                    && m.getReturnType().equals("V")) return 2;
            if (m.getName().equals("isPointerIconHidden") && m.getParameterTypes().isEmpty()
                    && m.getReturnType().equals("Z")) return 3;
        }
        return 0;
    }
    static byte[] bytes(ClassDef cls) throws Exception {
        var store = new MemoryDataStore();
        DexPool.writeTo(store, new ImmutableDexFile(Opcodes.forApi(33), List.of(cls)));
        return store.getData();
    }
    static byte[] snippet(List<? extends Instruction> code) throws Exception {
        return bytes(new ImmutableClassDef(LAYOUT, 1, "Ljava/lang/Object;", List.of(), null, Set.of(), List.of(),
                List.of(new ImmutableMethod(LAYOUT, "predicate", List.of(), "V", 1, Set.of(), Set.of(),
                        new ImmutableMethodImplementation(8, code, List.of(), List.of())))));
    }
    static Method patch(Method m) throws Exception {
        int kind = target(m), edits = 0;
        var impl = m.getImplementation();
        List<Instruction> code = new ArrayList<>();
        for (Instruction instruction : impl.getInstructions()) code.add(instruction);
        if (kind == 1) {
            // Exactly 17 code units. Keep every subsequent branch, callback,
            // try range, debug address and register unchanged.
            List<Instruction> expected = List.of(
                    new ImmutableInstruction22c(Opcode.IGET, 2, 0, new ImmutableFieldReference("Landroid/graphics/Rect;", "top", "I")),
                    new ImmutableInstruction11n(Opcode.CONST_4, 4, 1),
                    new ImmutableInstruction11n(Opcode.CONST_4, 5, 0),
                    new ImmutableInstruction21t(Opcode.IF_NEZ, 2, 12),
                    new ImmutableInstruction22c(Opcode.IGET, 0, 0, new ImmutableFieldReference("Landroid/graphics/Rect;", "bottom", "I")),
                    new ImmutableInstruction23x(Opcode.SUB_INT, 0, 1, 0),
                    new ImmutableInstruction21s(Opcode.CONST_16, 2, 10),
                    new ImmutableInstruction22t(Opcode.IF_LE, 0, 2, 4),
                    new ImmutableInstruction12x(Opcode.MOVE, 0, 4),
                    new ImmutableInstruction10t(Opcode.GOTO, 2),
                    new ImmutableInstruction12x(Opcode.MOVE, 0, 5));
            if (impl.getRegisterCount() != 8 || code.size() < 38
                    || !Arrays.equals(snippet(code.subList(27, 38)), snippet(expected)))
                throw new IllegalStateException("Unexpected keyboard geometry predicate");
            List<Instruction> replacement = new ArrayList<>(List.of(
                    new ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 7, new ImmutableFieldReference(LAYOUT, "val$rootView", "Landroid/view/View;")),
                    new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                            new ImmutableMethodReference("Ldev/beampro/port/KeyboardCompat;", "isVisible", List.of("Landroid/view/View;"), "Z")),
                    new ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                    new ImmutableInstruction11n(Opcode.CONST_4, 4, 1),
                    new ImmutableInstruction11n(Opcode.CONST_4, 5, 0)));
            for (int i = 0; i < 9; i++) replacement.add(new ImmutableInstruction10x(Opcode.NOP));
            if (code.subList(0, 27).stream().mapToInt(Instruction::getCodeUnits).sum() != 58
                    || replacement.stream().mapToInt(Instruction::getCodeUnits).sum() != 17)
                throw new IllegalStateException("Unexpected keyboard predicate address");
            code.subList(27, 38).clear();
            code.addAll(27, replacement);
        } else {
            boolean marker = false;
            for (int i = 0; i < code.size(); i++) {
                Instruction instruction = code.get(i);
                if (!(instruction instanceof ReferenceInstruction ref)) continue;
                if (ref.getReference() instanceof StringReference text && text.getString().equals("xreal_hide_pointicon")) marker = true;
                if (!(ref.getReference() instanceof MethodReference call)
                        || !call.getDefiningClass().equals("Landroid/provider/Settings$Global;")) continue;
                List<String> params = kind == 2 ? List.of(RESOLVER, "Ljava/lang/String;", "I") : List.of(RESOLVER, "Ljava/lang/String;");
                if (!call.getName().equals(kind == 2 ? "putInt" : "getInt") || !call.getParameterTypes().equals(params)
                        || !call.getReturnType().equals(kind == 2 ? "Z" : "I")
                        || instruction.getOpcode() != Opcode.INVOKE_STATIC || !(instruction instanceof Instruction35c invoke)
                        || invoke.getRegisterCount() != params.size())
                    throw new IllegalStateException("Unexpected pointer flag access");
                code.set(i, new ImmutableInstruction35c(Opcode.INVOKE_STATIC, invoke.getRegisterCount(),
                        invoke.getRegisterC(), invoke.getRegisterD(), invoke.getRegisterE(), invoke.getRegisterF(), invoke.getRegisterG(),
                        new ImmutableMethodReference("Ldev/beampro/port/SettingsCompat;", kind == 2 ? "putGlobalInt" : "getGlobalInt",
                                params, call.getReturnType())));
                edits++;
            }
            if (edits != 1 || !marker) throw new IllegalStateException("Expected one pointer flag access");
        }
        return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(), m.getAccessFlags(),
                m.getAnnotations(), m.getHiddenApiRestrictions(), new ImmutableMethodImplementation(impl.getRegisterCount(),
                code, impl.getTryBlocks(), impl.getDebugItems()));
    }
    static ClassDef patch(ClassDef cls) throws Exception {
        List<Method> methods = new ArrayList<>();
        boolean changed = false;
        for (Method m : cls.getMethods()) {
            methods.add(target(m) == 0 ? m : patch(m));
            changed |= target(m) != 0;
        }
        return changed ? new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods) : cls;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("patch", "restore").contains(args[0]))
            throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore = args[0].equals("restore");
        var base = DexFileFactory.loadDexFile(args[3], Opcodes.forApi(33));
        var input = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        Map<String, ClassDef> expected = new HashMap<>();
        Map<String, ClassDef> patched = new HashMap<>();
        Set<Integer> targets = new HashSet<>();
        for (ClassDef cls : base.getClasses()) {
            for (Method m : cls.getMethods()) if (target(m) != 0) targets.add(target(m));
            ClassDef after = patch(cls);
            expected.put(cls.getType(), restore ? after : cls);
            patched.put(cls.getType(), after);
        }
        if (!targets.equals(Set.of(1)) && !targets.equals(Set.of(2, 3)))
            throw new IllegalStateException("Expected keyboard detection or both pointer flag accesses");
        List<ClassDef> output = new ArrayList<>();
        for (ClassDef cls : input.getClasses()) {
            ClassDef reference = expected.remove(cls.getType());
            if (reference == null || !Arrays.equals(bytes(cls), bytes(reference)))
                throw new IllegalStateException("Unexpected phone input DEX change: " + cls.getType());
            output.add(patched.get(cls.getType()));
        }
        if (!expected.isEmpty()) throw new IllegalStateException("Missing DEX classes");
        if (restore) Files.write(Path.of(args[2]), Files.readAllBytes(Path.of(args[3])));
        else DexPool.writeTo(args[2], new ImmutableDexFile(input.getOpcodes(), output));
        System.out.println(args[0] + ": phone input hooks " + targets + "; remaining DEX and input callbacks preserved");
    }
}
