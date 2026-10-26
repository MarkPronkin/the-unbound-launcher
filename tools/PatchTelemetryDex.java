import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.rewriter.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.util.*;

/** Remove XREAL's uploader SDK; retain inert Java/JNI and Binder compatibility signatures. */
public final class PatchTelemetryDex {
    static boolean facade(String type) {
        return type.equals("Lcom/xreal/analytics/AnalyticsManager;")
                || type.equals("Lcom/xreal/analytics/AnalyticsService;")
                || type.startsWith("Lcom/xreal/analytics/service/");
    }

    static boolean removed(String type) {
        return type.startsWith("Lcom/sensorsdata/")
                || (type.startsWith("Lcom/xreal/analytics/") && !facade(type));
    }

    static boolean stubbed(String type) {
        return facade(type) || type.equals("Lai/nreal/nebula/features/bias_upload_json/BiasUploadHelper;")
                || type.startsWith("Lai/nreal/nebula/features/bias_upload_json/BiasUploadHelper$");
    }

    static Method inert(Method method, String superclass) {
        if (method.getImplementation() == null) return method;
        int parameters = (method.getAccessFlags() & 8) == 0 ? 1 : 0;
        for (var type : method.getParameterTypes()) parameters += type.equals("J") || type.equals("D") ? 2 : 1;
        int registers = Math.max(parameters, 2);
        List<com.android.tools.smali.dexlib2.iface.instruction.Instruction> code = new ArrayList<>();
        if (method.getName().equals("<init>")) {
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, registers - parameters, 0, 0, 0, 0,
                    new ImmutableMethodReference(superclass, "<init>", List.of(), "V")));
        }
        String result = method.getReturnType();
        if (result.equals("V")) {
            code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        } else if (result.equals("J") || result.equals("D")) {
            code.add(new ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 0));
            code.add(new ImmutableInstruction11x(Opcode.RETURN_WIDE, 0));
        } else {
            code.add(new ImmutableInstruction11n(Opcode.CONST_4, 0, 0));
            code.add(new ImmutableInstruction11x(result.startsWith("L") || result.startsWith("[")
                    ? Opcode.RETURN_OBJECT : Opcode.RETURN, 0));
        }
        return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                result, method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
                new ImmutableMethodImplementation(registers, code, List.of(), List.of()));
    }

    static String instructionKey(com.android.tools.smali.dexlib2.iface.instruction.Instruction ins) {
        String key = ins.getOpcode().name();
        if (ins instanceof com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)
            key += ":a=" + ((com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction) ins).getRegisterA();
        if (ins instanceof com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction)
            key += ":literal=" + ((com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction) ins).getNarrowLiteral();
        if (ins instanceof com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c) {
            var call = (com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c) ins;
            key += ":count=" + call.getRegisterCount() + ":c=" + call.getRegisterC();
        }
        if (ins instanceof ReferenceInstruction) key += ":ref=" + ((ReferenceInstruction) ins).getReference();
        return key;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !Set.of("patch", "verify").contains(args[0])) {
            throw new IllegalArgumentException("patch|verify INPUT OUTPUT");
        }
        var original = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        List<ClassDef> classes = new ArrayList<>();
        Set<String> edits = new TreeSet<>();
        int removed = 0, stubs = 0;
        for (ClassDef cls : original.getClasses()) {
            if (removed(cls.getType())) {
                if (args[0].equals("verify")) throw new IllegalStateException("Uploader class remains: " + cls.getType());
                removed++;
                continue;
            }
            final String superclass = cls.getSuperclass();
            if (stubbed(cls.getType())) {
                List<Method> methods = new ArrayList<>();
                for (Method method : cls.getMethods()) {
                    Method stub = inert(method, superclass);
                    if (method.getImplementation() != null) {
                        stubs++;
                        if (args[0].equals("verify")) {
                            List<String> actual = new ArrayList<>(), expected = new ArrayList<>();
                            for (var instruction : method.getImplementation().getInstructions()) actual.add(instructionKey(instruction));
                            for (var instruction : stub.getImplementation().getInstructions()) expected.add(instructionKey(instruction));
                            if (!actual.equals(expected) || !method.getImplementation().getTryBlocks().isEmpty()) {
                                throw new IllegalStateException("Active analytics method remains: " + method);
                            }
                        }
                    }
                    methods.add(stub);
                }
                cls = new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), superclass, cls.getInterfaces(),
                        cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods);
            } else if (args[0].equals("patch")) {
                DexRewriter rewrite = new DexRewriter(new RewriterModule() {
                    @Override public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                        return method -> {
                            if (method.getDefiningClass().equals("Lai/nreal/nebula/NebulaApplication;")
                                    && method.getName().equals("initAnalytics")) {
                                edits.add("disabled application analytics startup");
                                return inert(method, superclass);
                            }
                            if (method.getDefiningClass().equals("Lai/nreal/nebula/space/LaunchSpaceAcrivity;")
                                    && method.getName().equals("onCreate")) {
                                var mutable = new MutableMethodImplementation(method.getImplementation());
                                int begin = -1, end = -1;
                                boolean service = false;
                                for (int i = 0; i < mutable.getInstructions().size(); i++) {
                                    var ins = mutable.getInstructions().get(i);
                                    if (!(ins instanceof ReferenceInstruction)) continue;
                                    var ref = ((ReferenceInstruction) ins).getReference();
                                    if (ins.getOpcode() == Opcode.NEW_INSTANCE && ref instanceof TypeReference
                                            && ((TypeReference) ref).getType().equals("Lai/nreal/nebula/space/LaunchSpaceAcrivity$4;")) begin = i;
                                    if (begin >= 0 && ref instanceof TypeReference
                                            && ((TypeReference) ref).getType().equals("Lcom/xreal/analytics/AnalyticsService;")) service = true;
                                    if (service && ref instanceof MethodReference && ((MethodReference) ref).getName().equals("bindService")) {
                                        end = i;
                                        break;
                                    }
                                }
                                if (begin < 0 || end < begin || end - begin != 7) {
                                    throw new IllegalStateException("Unexpected spatial analytics binding block");
                                }
                                for (int i = end; i >= begin; i--) mutable.removeInstruction(i);
                                edits.add("removed spatial analytics binding; connection remains null for safe teardown");
                                return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                                        method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                                        method.getHiddenApiRestrictions(), mutable);
                            }
                            return method;
                        };
                    }
                });
                cls = rewrite.getClassDefRewriter().rewrite(cls);
            }
            // Force lazy rewrites before reporting their assertions.
            cls = ImmutableClassDef.of(cls);
            if (args[0].equals("verify")) {
                for (var method : cls.getMethods()) {
                    if (method.getImplementation() == null) continue;
                    if (cls.getType().equals("Lai/nreal/nebula/NebulaApplication;") && method.getName().equals("initAnalytics")) {
                        var iterator = method.getImplementation().getInstructions().iterator();
                        if (!iterator.hasNext() || iterator.next().getOpcode() != Opcode.RETURN_VOID || iterator.hasNext())
                            throw new IllegalStateException("Application analytics startup remains active");
                    }
                    for (var instruction : method.getImplementation().getInstructions()) {
                        if (!(instruction instanceof ReferenceInstruction)) continue;
                        var reference = ((ReferenceInstruction) instruction).getReference();
                        String type = reference instanceof TypeReference ? ((TypeReference) reference).getType()
                                : reference instanceof MethodReference ? ((MethodReference) reference).getDefiningClass() : "";
                        if (removed(type)) throw new IllegalStateException("Dangling uploader reference: " + method + " -> " + type);
                        if (cls.getType().equals("Lai/nreal/nebula/space/LaunchSpaceAcrivity;")
                                && method.getName().equals("onCreate") && type.equals("Lai/nreal/nebula/space/LaunchSpaceAcrivity$4;"))
                            throw new IllegalStateException("Spatial analytics binding remains active");
                    }
                }
            }
            classes.add(cls);
        }
        if (args[0].equals("patch")) DexPool.writeTo(args[2], new ImmutableDexFile(original.getOpcodes(), classes));
        System.out.println(args[0] + ": removed " + removed + " uploader classes, " + stubs + " inert compatibility methods");
        for (String edit : edits) System.out.println(edit);
    }
}
