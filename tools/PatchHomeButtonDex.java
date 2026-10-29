import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Add cancellation to Home input and fragment lifecycle without replacing stock input. */
public final class PatchHomeButtonDex {
    private static final String PREFIX = "Lai/nreal/extension/virtualcontroller/";
    private static final String COMPAT = "Ldev/beampro/port/HomeButtonCompat;";
    private static final Set<String> FRAGMENTS = Set.of(PREFIX + "AirMouseFragment;",
            PREFIX + "TouchPadPortraitFragment;", PREFIX + "TouchPadLandscapeFragment;");
    private static final Set<String> LIFECYCLE = Set.of("onPause", "onDestroyView");

    private static byte[] bytes(ClassDef cls) throws Exception {
        var store = new MemoryDataStore();
        DexPool.writeTo(store, new ImmutableDexFile(Opcodes.forApi(33), List.of(cls)));
        return store.getData();
    }

    private static ClassDef patched(ClassDef cls) {
        boolean fragment = FRAGMENTS.contains(cls.getType());
        String owner = cls.getType().replace("$OnButtonTouchListener;", ";");
        if (!fragment && (owner.equals(cls.getType()) || !FRAGMENTS.contains(owner))) return cls;
        List<Method> methods = new ArrayList<>();
        int edits = 0;
        for (Method method : cls.getMethods()) {
            if (fragment) {
                if (LIFECYCLE.contains(method.getName())) throw new IllegalStateException("Unexpected existing lifecycle hook");
                methods.add(method);
                continue;
            }
            if (!method.getName().equals("onTouch")) { methods.add(method); continue; }
            if (!method.getParameterTypes().equals(List.of("Landroid/view/View;", "Landroid/view/MotionEvent;"))
                    || !method.getReturnType().equals("Z")) throw new IllegalStateException("Unexpected touch signature");
            var body = new MutableMethodImplementation(method.getImplementation());
            int index = -1, position = 0;
            boolean home = false;
            for (Instruction instruction : method.getImplementation().getInstructions()) {
                if (instruction instanceof ReferenceInstruction ref) {
                    if (ref.getReference() instanceof FieldReference field && field.getName().equals("home_button")
                            && field.getDefiningClass().equals(PREFIX + "R$id;")) home = true;
                    if (ref.getReference() instanceof MethodReference call
                            && call.getDefiningClass().equals(COMPAT)) throw new IllegalStateException("Home hook already present");
                    if (ref.getReference() instanceof MethodReference call && call.getName().equals("getActionMasked")
                            && call.getDefiningClass().equals("Landroid/view/MotionEvent;") && index == -1) {
                        if (!home) throw new IllegalStateException("Home is not the first input branch");
                        index = position;
                    }
                }
                position++;
            }
            if (index < 0) throw new IllegalStateException("Missing Home input");
            body.addInstruction(index, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                    body.getRegisterCount() - 3, 3, new ImmutableMethodReference(COMPAT, "onTouch",
                    List.of("Ljava/lang/Object;", "Landroid/view/View;", "Landroid/view/MotionEvent;"), "V")));
            methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                    method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body));
            edits++;
        }
        if (fragment) {
            for (String name : new TreeSet<>(LIFECYCLE)) {
                methods.add(new ImmutableMethod(cls.getType(), name, List.of(), "V", AccessFlags.PUBLIC.getValue(),
                        Set.of(), Set.of(), new ImmutableMethodImplementation(1, List.of(
                        new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                                new ImmutableMethodReference(COMPAT, "cancel", List.of("Ljava/lang/Object;"), "V")),
                        new ImmutableInstruction35c(Opcode.INVOKE_SUPER, 1, 0, 0, 0, 0, 0,
                                new ImmutableMethodReference(cls.getSuperclass(), name, List.of(), "V")),
                        new ImmutableInstruction10x(Opcode.RETURN_VOID)), List.of(), List.of())));
            }
        } else if (edits != 1) throw new IllegalStateException("Expected one Home input hook");
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
        if (changed.size() != 6) throw new IllegalStateException("Expected all three controllers and listeners");
        List<ClassDef> output = new ArrayList<>();
        for (ClassDef cls : input.getClasses()) {
            ClassDef reference = expected.remove(cls.getType());
            if (reference == null || !Arrays.equals(bytes(cls), bytes(reference)))
                throw new IllegalStateException("Unexpected Home input DEX change: " + cls.getType());
            output.add(restore ? cls : patched(cls));
        }
        if (!expected.isEmpty()) throw new IllegalStateException("Missing DEX classes");
        if (restore) Files.write(Path.of(args[2]), Files.readAllBytes(Path.of(args[3])));
        else DexPool.writeTo(args[2], new ImmutableDexFile(input.getOpcodes(), output));
        System.out.println(args[0] + ": Home cancellation in three controllers; stock input and remaining DEX preserved");
    }
}
