import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.*;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.rewriter.*;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.util.*;

/** Restore the two intentional service edits and compare every extracted class. */
public final class VerifyAppWindowGeometryDex {
    static byte[] bytes(ClassDef cls) throws Exception {
        var store = new MemoryDataStore();
        DexPool.writeTo(store, new ImmutableDexFile(Opcodes.forApi(33), List.of(cls)));
        return store.getData();
    }
    public static void main(String[] args) throws Exception {
        var baseline = DexFileFactory.loadDexFile(args[0], Opcodes.forApi(33));
        var candidate = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        Map<String,ClassDef> originals = new HashMap<>();
        for (ClassDef cls : baseline.getClasses())
            if (cls.getType().startsWith("Lai/nreal/nebula/service/")) originals.put(cls.getType(),cls);
        Set<String> edits = new HashSet<>();
        var restore = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                return method -> {
                    List<Instruction> code = new ArrayList<>();
                    var body = method.getImplementation();
                    if (body == null) return method;
                    for (Instruction instruction : body.getInstructions()) {
                        if (instruction instanceof ReferenceInstruction ref
                                && ref.getReference() instanceof MethodReference call
                                && call.getDefiningClass().equals("Ldev/beampro/bridge/AppWindowGeometry;")) {
                            if (!method.getDefiningClass().equals("Lai/nreal/nebula/service/ActivityContent;")
                                    || !method.getName().equals("getRotation") || !call.getName().equals("rotation")
                                    || !call.getParameterTypes().equals(List.of("Landroid/view/Display;"))
                                    || !call.getReturnType().equals("I")
                                    || instruction.getOpcode() != Opcode.INVOKE_STATIC
                                    || !(instruction instanceof Instruction35c invoke) || invoke.getRegisterCount() != 1)
                                throw new AssertionError("Unexpected geometry hook");
                            instruction = new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1,
                                    invoke.getRegisterC(), 0, 0, 0, 0,
                                    new ImmutableMethodReference("Landroid/view/Display;", "getRotation", List.of(), "I"));
                            edits.add("geometry");
                        } else if (method.getDefiningClass().equals("Lai/nreal/nebula/service/LaunchManager;")
                                && instruction.getOpcode() == Opcode.CONST_16
                                && ((Instruction21s) instruction).getNarrowLiteral() == 1481) {
                            instruction = new ImmutableInstruction21s(Opcode.CONST_16,
                                    ((Instruction21s) instruction).getRegisterA(), 1485);
                            edits.add("non-secure displays");
                        }
                        code.add(instruction);
                    }
                    return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                            method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
                            new ImmutableMethodImplementation(body.getRegisterCount(),code,body.getTryBlocks(),body.getDebugItems()));
                };
            }
        });
        int classes = 0;
        for (ClassDef cls : candidate.getClasses()) {
            ClassDef original = originals.remove(cls.getType());
            if (original == null || !Arrays.equals(bytes(original),bytes(restore.getClassDefRewriter().rewrite(cls))))
                throw new AssertionError("Unrelated service edit: " + cls.getType());
            classes++;
        }
        if (!originals.isEmpty() || !edits.equals(Set.of("geometry", "non-secure displays")))
            throw new AssertionError("Missing extracted classes or hooks: " + edits);
        System.out.println("PASS app window DEX scope: " + classes + " classes");
    }
}
