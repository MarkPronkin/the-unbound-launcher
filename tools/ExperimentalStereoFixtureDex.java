import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.util.*;

/** Run the actual SDK selector, observing its hook argument before forwarding to production policy. */
public final class ExperimentalStereoFixtureDex {
    static final String MANAGER = "Lai/nreal/nebula/manager/XREALManager;";
    static final String POLICY = "Ldev/beampro/settings/ExperimentalStereo;";
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("SDK_DEX FIXTURE_DEX OUTPUT");
        var sdk = DexFileFactory.loadDexFile(args[0], Opcodes.forApi(33));
        var fixture = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        Method selector = null;
        for (ClassDef cls : sdk.getClasses()) if (cls.getType().equals(MANAGER))
            for (Method method : cls.getMethods()) if (method.getName().equals("getFinalDisplayConfig")) {
                if (selector != null) throw new IllegalStateException("Duplicate SDK selector");
                selector = method;
            }
        if (selector == null) throw new IllegalStateException("Missing SDK selector");
        var code = new MutableMethodImplementation(selector.getImplementation());
        int hooks = 0;
        for (int i = 0; i < code.getInstructions().size(); i++) {
            var instruction = code.getInstructions().get(i);
            if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference call
                    && call.getDefiningClass().equals(POLICY) && call.getName().equals("selectConfig")) {
                if (!(instruction instanceof Instruction35c invoke) || instruction.getOpcode() != Opcode.INVOKE_STATIC
                        || invoke.getRegisterCount() != 2 || !call.getParameterTypes().equals(List.of("I", "Ljava/lang/Object;"))
                        || !call.getReturnType().equals("I")) throw new IllegalStateException("Unexpected stereo hook");
                code.replaceInstruction(i, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2,
                    invoke.getRegisterC(), invoke.getRegisterD(), 0, 0, 0, new ImmutableMethodReference(
                        "Ldev/beampro/stereotest/SelectorProbe;", "selectConfig", List.of("I", "Ljava/lang/Object;"), "I")));
                hooks++;
            }
        }
        if (hooks != 1) throw new IllegalStateException("Expected one observable SDK hook");
        List<ClassDef> classes = new ArrayList<>(); int edits = 0;
        for (ClassDef cls : fixture.getClasses()) {
            List<Method> methods = new ArrayList<>();
            for (Method method : cls.getMethods()) {
                if (cls.getType().equals(MANAGER) && method.getName().equals("getFinalDisplayConfig")) {
                    if (!method.getParameterTypes().equals(selector.getParameterTypes())
                            || !method.getReturnType().equals(selector.getReturnType()))
                        throw new IllegalStateException("Fixture selector signature differs");
                    methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                        method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), code));
                    edits++;
                } else methods.add(method);
            }
            classes.add(new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods));
        }
        if (edits != 1) throw new IllegalStateException("Missing fixture selector");
        DexPool.writeTo(args[2], new ImmutableDexFile(fixture.getOpcodes(), classes));
        System.out.println("Fixture retains the SDK selector instructions and hook registers; observing probe forwards to production policy");
    }
}
