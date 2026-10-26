import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.File;
import java.util.*;

/** Restore only the two history hooks and client initializer, then compare all DEX code. */
public final class VerifyRecentAppsDex {
    private static final String CLIENT = "Ldev/beampro/port/RecentAppsCompat;";

    private static String key(Method method) {
        return method.getDefiningClass() + "->" + method.getName() + method.getParameterTypes() + method.getReturnType();
    }

    private static byte[] canonical(Collection<? extends ClassDef> classes) throws Exception {
        List<ClassDef> stripped = new ArrayList<>();
        for (ClassDef cls : classes) {
            List<Method> methods = new ArrayList<>();
            for (Method method : cls.getMethods()) {
                MethodImplementation impl = method.getImplementation();
                if (impl != null) impl = new ImmutableMethodImplementation(impl.getRegisterCount(),
                        impl.getInstructions(), impl.getTryBlocks(), List.of());
                methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                        method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), impl));
            }
            stripped.add(new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                    cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods));
        }
        MemoryDataStore data = new MemoryDataStore();
        DexPool.writeTo(data, new ImmutableDexFile(Opcodes.forApi(33), stripped));
        return data.getData();
    }

    private static boolean clientCall(Instruction instruction, String name) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        var ref = ((ReferenceInstruction) instruction).getReference();
        return ref instanceof MethodReference && ((MethodReference) ref).getDefiningClass().equals(CLIENT)
                && ((MethodReference) ref).getName().equals(name);
    }

    public static void main(String[] args) throws Exception {
        var previous = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.forApi(33));
        var candidate = DexFileFactory.loadDexContainer(new File(args[1]), Opcodes.forApi(33));
        Set<String> changed = new TreeSet<>();
        for (String dex : List.of("classes2.dex", "classes4.dex")) {
            var before = previous.getEntry(dex).getDexFile();
            var after = candidate.getEntry(dex).getDexFile();
            Map<String, Method> originals = new HashMap<>();
            for (var cls : before.getClasses()) for (var method : cls.getMethods()) originals.put(key(method), method);
            List<ClassDef> restored = new ArrayList<>();
            for (var cls : after.getClasses()) {
                if (cls.getType().equals(CLIENT)) { changed.add("new-client"); continue; }
                List<Method> methods = new ArrayList<>();
                for (Method method : cls.getMethods()) {
                    String name = method.getName();
                    if (cls.getType().equals("Lcom/xreal/evapro/AppTaskUtil;")
                            && (name.equals("getRecentTasks") || name.equals("removeRecentTask"))) {
                        List<Instruction> code = new ArrayList<>();
                        method.getImplementation().getInstructions().forEach(code::add);
                        List<Opcode> expected = name.equals("getRecentTasks")
                                ? List.of(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.RETURN_OBJECT)
                                : List.of(Opcode.INVOKE_STATIC, Opcode.RETURN_VOID);
                        if (!code.stream().map(Instruction::getOpcode).toList().equals(expected)
                                || !clientCall(code.get(0), name)
                                || ((Instruction35c) code.get(0)).getRegisterC() != 0
                                || ((Instruction35c) code.get(0)).getRegisterCount() != 1
                                || method.getImplementation().getRegisterCount() != 1) {
                            throw new IllegalStateException("Invalid recent-apps hook: " + name);
                        }
                        method = originals.get(key(method));
                        changed.add(name);
                    } else if (cls.getType().equals("Ldev/beampro/port/PortStatus;") && name.equals("install")) {
                        MutableMethodImplementation code = new MutableMethodImplementation(method.getImplementation());
                        int removed = 0;
                        for (int i = code.getInstructions().size() - 1; i >= 0; i--) {
                            if (clientCall(code.getInstructions().get(i), "install")) { code.removeInstruction(i); removed++; }
                        }
                        if (removed != 1) throw new IllegalStateException("Missing per-process initializer");
                        method = new ImmutableMethod(method.getDefiningClass(), name, method.getParameters(), method.getReturnType(),
                                method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), code);
                        changed.add("initializer");
                    }
                    methods.add(method);
                }
                restored.add(new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                        cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods));
            }
            if (!Arrays.equals(canonical(before.getClasses()), canonical(restored))) {
                throw new IllegalStateException("Unrelated DEX code changed: " + dex);
            }
        }
        if (!changed.equals(Set.of("getRecentTasks", "removeRecentTask", "new-client", "initializer"))) {
            throw new IllegalStateException("Incomplete recent-app changes: " + changed);
        }
        System.out.println("Verified two history hooks, initialized client, and preservation of all other DEX code");
    }
}
