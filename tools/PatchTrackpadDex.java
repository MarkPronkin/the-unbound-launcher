import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.util.*;

/** Replace only the two trackpad callbacks; air mouse and all other input stay intact. */
public final class PatchTrackpadDex {
    private static final String RECEIVER = "Lai/nreal/extension/virtualcontroller/ISystemButtonDataReceiver;";
    private static final String COMPAT = "Ldev/beampro/port/TrackpadCompat;";
    private static final Set<String> CLASSES = Set.of(
            "Lai/nreal/extension/virtualcontroller/TouchPadPortraitFragment$OnButtonTouchListener;",
            "Lai/nreal/extension/virtualcontroller/TouchPadLandscapeFragment$OnButtonTouchListener;");
    private static final List<String> ORIGINAL = List.of("I", "I", "[I", "[F", "[F");
    private static final List<String> FORWARD = List.of("Ljava/lang/Object;", "I", "I", "[I", "[F", "[F");

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !Set.of("patch", "restore").contains(args[0]))
            throw new IllegalArgumentException("patch|restore INPUT OUTPUT");
        boolean restore = args[0].equals("restore");
        var source = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        List<ClassDef> classes = new ArrayList<>();
        Set<String> changed = new HashSet<>();
        for (ClassDef cls : source.getClasses()) {
            if (!CLASSES.contains(cls.getType())) { classes.add(cls); continue; }
            List<Method> methods = new ArrayList<>();
            for (Method method : cls.getMethods()) {
                if (!method.getName().equals("onTouch")) { methods.add(method); continue; }
                if (!method.getParameterTypes().equals(List.of("Landroid/view/View;", "Landroid/view/MotionEvent;"))
                        || !method.getReturnType().equals("Z")) throw new IllegalStateException("Unexpected listener signature");
                List<Instruction> code = new ArrayList<>();
                int hits = 0;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference call
                            && call.getDefiningClass().equals(restore ? COMPAT : RECEIVER)
                            && call.getName().equals(restore ? "forward" : "FillTouchpadInput")) {
                        if (!(instruction instanceof Instruction3rc invoke) || invoke.getRegisterCount() != 6
                                || invoke.getStartRegister() != 10
                                || instruction.getOpcode() != (restore ? Opcode.INVOKE_STATIC_RANGE : Opcode.INVOKE_INTERFACE_RANGE)
                                || !call.getParameterTypes().equals(restore ? FORWARD : ORIGINAL)
                                || !call.getReturnType().equals("V")) throw new IllegalStateException("Unexpected touchpad dispatch");
                        instruction = new ImmutableInstruction3rc(restore ? Opcode.INVOKE_INTERFACE_RANGE : Opcode.INVOKE_STATIC_RANGE,
                                10, 6, new ImmutableMethodReference(restore ? RECEIVER : COMPAT,
                                        restore ? "FillTouchpadInput" : "forward", restore ? ORIGINAL : FORWARD, "V"));
                        hits++;
                    }
                    code.add(instruction);
                }
                if (hits != 1 || !changed.add(cls.getType())) throw new IllegalStateException("Expected one dispatch per orientation");
                var body = method.getImplementation();
                methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                        method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
                        new ImmutableMethodImplementation(body.getRegisterCount(), code, body.getTryBlocks(), body.getDebugItems())));
            }
            classes.add(new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                    cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods));
        }
        if (!changed.equals(CLASSES)) throw new IllegalStateException("Missing trackpad orientation");
        DexPool.writeTo(args[2], new ImmutableDexFile(source.getOpcodes(), classes));
        System.out.println(args[0] + ": checked both trackpad dispatches; air mouse untouched");
    }
}
