import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Scoped startup/SDK-view hooks; audit every restored definition against the pinned baseline. */
public final class PatchDisplayStartupDex {
    private static final String XR = "Lai/nreal/activitylife/NRXRApp;";
    private static final String COMPAT = "Ldev/beampro/port/DisplayStartupCompat;";
    private static final String GLASSES = "Lcom/xreal/sdk/display/GlassesDisplay;";
    private static final Set<String> XR_TARGETS = Set.of("tryStartFakeActivity", "startActivityOnDisplay");

    private static byte[] body(Method method) throws Exception {
        var cls = new ImmutableClassDef(method.getDefiningClass(), 1, "Ljava/lang/Object;", List.of(), null,
                Set.of(), List.of(), List.of(method));
        return classBytes(cls);
    }

    private static byte[] classBytes(ClassDef cls) throws Exception {
        var store = new MemoryDataStore();
        DexPool.writeTo(store, new ImmutableDexFile(Opcodes.forApi(33), List.of(cls)));
        return store.getData();
    }

    private static Method patched(Method method) {
        var original = method.getImplementation();
        List<Instruction> code = new ArrayList<>();
        int registers = original.getRegisterCount();
        if (method.getDefiningClass().equals(GLASSES)) {
            if (!method.getName().equals("<init>") || registers != 5
                    || !method.getParameterTypes().equals(List.of("Landroid/app/Activity;", "J"))
                    || !method.getReturnType().equals("V") || !original.getTryBlocks().isEmpty())
                throw new IllegalStateException("Unexpected SDK display constructor");
            int returns = 0;
            for (Instruction instruction : original.getInstructions()) {
                if (instruction instanceof OffsetInstruction) throw new IllegalStateException("Unexpected constructor control flow");
                if (instruction.getOpcode() == Opcode.RETURN_VOID) {
                    for (String[] field : List.of(new String[]{"frameLayout", "Landroid/widget/FrameLayout;"},
                            new String[]{"mMainSurfaceView", "Landroid/view/SurfaceView;"})) {
                        code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 1,
                                new ImmutableFieldReference(GLASSES, field[0], field[1])));
                        code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                                new ImmutableMethodReference("Ldev/beampro/port/StereoPresentationCompat;", "watchView",
                                        List.of("Landroid/view/View;"), "V")));
                    }
                    returns++;
                }
                code.add(instruction);
            }
            if (returns != 1) throw new IllegalStateException("Expected one constructor return");
            return new ImmutableMethod(GLASSES, method.getName(), method.getParameters(), method.getReturnType(),
                    method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
                    new ImmutableMethodImplementation(registers, code, original.getTryBlocks(), original.getDebugItems()));
        }
        if (method.getName().equals("tryStartFakeActivity")) {
            if (!method.getParameterTypes().isEmpty() || !method.getReturnType().equals("V"))
                throw new IllegalStateException("Unexpected renderer startup signature");
            registers = 1;
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                    new ImmutableMethodReference(COMPAT, "startRenderer", List.of("Ljava/lang/Object;"), "V")));
            code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
            return new ImmutableMethod(XR, method.getName(), method.getParameters(), method.getReturnType(),
                    method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
                    new ImmutableMethodImplementation(registers, code, List.of(), List.of()));
        }
        if (!method.getParameterTypes().equals(List.of("Landroid/view/Display;", "Landroid/content/Intent;"))
                || !method.getReturnType().equals("Z")) throw new IllegalStateException("Unexpected display launch signature");
        int hits = 0;
        for (Instruction instruction : original.getInstructions()) {
            if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference call
                    && call.getDefiningClass().equals("Landroid/app/Activity;") && call.getName().equals("startActivity")) {
                if (!(instruction instanceof Instruction35c invoke) || invoke.getRegisterCount() != 3
                        || instruction.getOpcode() != Opcode.INVOKE_VIRTUAL
                        || !call.getParameterTypes().equals(List.of("Landroid/content/Intent;", "Landroid/os/Bundle;"))
                        || !call.getReturnType().equals("V")) throw new IllegalStateException("Unexpected Activity launch instruction");
                instruction = new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, invoke.getRegisterC(),
                        invoke.getRegisterD(), invoke.getRegisterE(), 0, 0,
                        new ImmutableMethodReference(COMPAT, "startActivity",
                                List.of("Landroid/app/Activity;", "Landroid/content/Intent;", "Landroid/os/Bundle;"), "V"));
                hits++;
            }
            code.add(instruction);
        }
        if (hits != 1) throw new IllegalStateException("Expected one external Activity launch");
        return new ImmutableMethod(XR, method.getName(), method.getParameters(), method.getReturnType(),
                method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
                new ImmutableMethodImplementation(registers, code, original.getTryBlocks(), original.getDebugItems()));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("patch", "restore").contains(args[0]))
            throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE_DEX");
        var base = DexFileFactory.loadDexFile(args[3], Opcodes.forApi(33));
        boolean xr = false, glasses = false;
        for (ClassDef cls : base.getClasses()) {
            xr |= cls.getType().equals(XR);
            glasses |= cls.getType().equals(GLASSES);
        }
        if (xr == glasses) throw new IllegalStateException("Expected exactly one startup or SDK display class");
        String targetClass = xr ? XR : GLASSES;
        Set<String> targets = xr ? XR_TARGETS : Set.of("<init>");
        Map<String, Method> methods = new HashMap<>();
        for (ClassDef cls : base.getClasses()) if (cls.getType().equals(targetClass))
            for (Method method : cls.getMethods()) if (targets.contains(method.getName())) {
                if (methods.put(method.getName(), method) != null) throw new IllegalStateException("Duplicate target");
            }
        if (!methods.keySet().equals(targets)) throw new IllegalStateException("Missing baseline methods");
        var input = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        List<ClassDef> classes = new ArrayList<>();
        Set<String> edits = new HashSet<>();
        boolean restore = args[0].equals("restore");
        for (ClassDef cls : input.getClasses()) {
            if (!cls.getType().equals(targetClass)) { classes.add(cls); continue; }
            List<Method> output = new ArrayList<>();
            for (Method method : cls.getMethods()) {
                Method before = methods.get(method.getName());
                if (before == null) { output.add(method); continue; }
                Method after = patched(before);
                if (!Arrays.equals(body(method), body(restore ? after : before)))
                    throw new IllegalStateException("Unexpected method body: " + method.getName());
                output.add(restore ? before : after);
                edits.add(method.getName());
            }
            classes.add(new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                    cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), output));
        }
        if (!edits.equals(targets)) throw new IllegalStateException("Missing display startup hooks");
        DexPool.writeTo(args[2], new ImmutableDexFile(input.getOpcodes(), classes));
        if (restore) {
            // Original classes3.dex was emitted by D8; DexPool uses a different pool layout.
            // Check the complete restored definitions before returning the original bytes.
            // D8 and DexPool can intern shared annotation/pool entries in different orders.
            var expected = new MemoryDataStore();
            DexPool.writeTo(expected, base);
            if (!Arrays.equals(Files.readAllBytes(Path.of(args[2])), expected.getData())) {
                Map<String, ClassDef> unchanged = new HashMap<>();
                for (ClassDef cls : base.getClasses()) unchanged.put(cls.getType(), cls);
                for (ClassDef cls : classes) {
                    ClassDef original = unchanged.remove(cls.getType());
                    if (original == null || !Arrays.equals(classBytes(original), classBytes(cls)))
                        throw new IllegalStateException("Restored DEX contains unrelated changes: " + cls.getType());
                }
                if (!unchanged.isEmpty()) throw new IllegalStateException("Restored DEX lost original classes");
            }
            Files.write(Path.of(args[2]), Files.readAllBytes(Path.of(args[3])));
        }
        System.out.println(args[0] + ": verified " + edits.size() + " hook(s) in " + targetClass);
    }
}
