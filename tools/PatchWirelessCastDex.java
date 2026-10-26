import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pinned optional Miracast capability guards; AirPlay and the cast switch remain intact. */
public final class PatchWirelessCastDex {
    private static final String DEBUG = "Lai/nreal/cast/DebugSetting;";
    private static final String CAST = "Lai/nreal/cast/CastUtil;";
    private static final String RECEIVER = "Lai/nreal/cast/CastUtil$3;";
    private static final String COMPAT = "Ldev/beampro/port/WirelessCastCompat;";
    private static final Map<String, String> SOURCE = Map.of(
            DEBUG + "->isMiracastEnable", "6a305cca660db08fc222d16c5a04c9060a03c961e5c03ac0ee163a3a4cbb4f97",
            CAST + "->startCastService", "5f9cfffe156e9085160213f33df213687fd2e1853cee6d0eabd9a5b00640a315",
            CAST + "->startWifiDisplayService", "abfdc890c4add351b3e6abbd3c905eba4bd0a6888ac25a9a77849c5b3a773eb2",
            CAST + "->stopWifiDisplayService", "503f8771d557a38f747f56b3f5472f600cd165c640e84f9654ae74f7985fc7d4",
            CAST + "->quitWfdSession", "6b26a31efe58c86a6b56c2e18dfda5b4e0c26d04f2f69abfa34f744889f02dc2",
            CAST + "->updateDiscoverableInternal", "9f1a329e0c7e32029873187f827b7ed50078024176a2278acabf0815eb757130",
            RECEIVER + "->lambda$onReceive$0$ai-nreal-cast-CastUtil$3", "ffe2c81a2f4cb05459c18c7121e0fce4042fa2e38f37c2d381f7827890d91bed");

    private static String key(Method method) { return method.getDefiningClass() + "->" + method.getName(); }

    private static List<Instruction> instructions(Method method) {
        List<Instruction> result = new ArrayList<>();
        for (Instruction instruction : method.getImplementation().getInstructions()) result.add(instruction);
        return result;
    }

    private static String fingerprint(Method method) throws Exception {
        MethodImplementation body = method.getImplementation();
        StringBuilder value = new StringBuilder(method.getDefiningClass()).append("->")
                .append(method.getName()).append(method.getParameterTypes()).append(method.getReturnType())
                .append(":flags=").append(method.getAccessFlags()).append(":registers=").append(body.getRegisterCount());
        for (Instruction instruction : body.getInstructions()) {
            value.append('\n').append(instruction.getOpcode()).append(':').append(instruction.getCodeUnits());
            if (instruction instanceof OneRegisterInstruction i) value.append(":a=").append(i.getRegisterA());
            if (instruction instanceof TwoRegisterInstruction i) value.append(":b=").append(i.getRegisterB());
            if (instruction instanceof ThreeRegisterInstruction i) value.append(":c=").append(i.getRegisterC());
            if (instruction instanceof FiveRegisterInstruction i) value.append(":c=").append(i.getRegisterC())
                    .append(":d=").append(i.getRegisterD()).append(":e=").append(i.getRegisterE())
                    .append(":f=").append(i.getRegisterF()).append(":g=").append(i.getRegisterG());
            if (instruction instanceof VariableRegisterInstruction i) value.append(":count=").append(i.getRegisterCount());
            if (instruction instanceof RegisterRangeInstruction i) value.append(":start=").append(i.getStartRegister());
            if (instruction instanceof WideLiteralInstruction i) value.append(":literal=").append(i.getWideLiteral());
            if (instruction instanceof OffsetInstruction i) value.append(":offset=").append(i.getCodeOffset());
            if (instruction instanceof ReferenceInstruction i) value.append(":reference=").append(i.getReference());
        }
        for (var block : body.getTryBlocks()) {
            value.append("\ntry:").append(block.getStartCodeAddress()).append(':').append(block.getCodeUnitCount());
            for (var handler : block.getExceptionHandlers()) value.append(':').append(handler.getExceptionType())
                    .append('@').append(handler.getHandlerCodeAddress());
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static boolean call(Instruction instruction, String type, String name, List<String> parameters, String result) {
        if (!(instruction instanceof ReferenceInstruction reference) || !(reference.getReference() instanceof MethodReference method)) return false;
        return method.getDefiningClass().equals(type) && method.getName().equals(name)
                && method.getParameterTypes().equals(parameters) && method.getReturnType().equals(result);
    }

    private static Instruction originalGate() {
        return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                new ImmutableMethodReference(DEBUG, "isMiracastEnable", List.of(), "Z"));
    }

    private static Instruction readyGate(int receiver) {
        return new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, receiver, 1,
                new ImmutableMethodReference(COMPAT, "isMiracastReady", List.of("Ljava/lang/Object;"), "Z"));
    }

    private static Method rewrite(Method method, boolean restore) {
        List<Instruction> code = instructions(method);
        int parameters = method.getParameterTypes().size() + ((method.getAccessFlags() & 8) == 0 ? 1 : 0);
        int receiver = method.getImplementation().getRegisterCount() - parameters;
        if (method.getDefiningClass().equals(DEBUG)) {
            if (restore) {
                if (code.size() != 3 || !call(code.get(0), COMPAT, "isMiracastEnabled", List.of(), "Z")
                        || code.get(1).getOpcode() != Opcode.MOVE_RESULT || code.get(2).getOpcode() != Opcode.RETURN) {
                    throw new IllegalStateException("Miracast capability gate is missing");
                }
                code = List.of(new ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                        new ImmutableInstruction11x(Opcode.RETURN, 0));
            } else {
                code = List.of(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                                new ImmutableMethodReference(COMPAT, "isMiracastEnabled", List.of(), "Z")),
                        new ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), new ImmutableInstruction11x(Opcode.RETURN, 0));
            }
        } else if (method.getName().equals("startCastService")) {
            var mutable = new MutableMethodImplementation(method.getImplementation());
            if (restore) {
                if (code.isEmpty() || !call(code.get(0), COMPAT, "onCastServiceStart", List.of("Ljava/lang/Object;"), "V")) {
                    throw new IllegalStateException("Miracast availability notice is missing");
                }
                mutable.removeInstruction(0);
            } else {
                mutable.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, receiver, 1,
                        new ImmutableMethodReference(COMPAT, "onCastServiceStart", List.of("Ljava/lang/Object;"), "V")));
            }
            return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(), method.getReturnType(),
                    method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), mutable);
        } else if (method.getDefiningClass().equals(RECEIVER)) {
            int changed = 0;
            for (int i = 0; i < code.size(); i++) {
                Instruction instruction = code.get(i);
                if (call(instruction, restore ? COMPAT : "Lcom/qualcomm/wfd/client/XrealWfdManager;", "startSearch",
                        restore ? List.of("Ljava/lang/Object;") : List.of(), "V")) {
                    if (!(instruction instanceof com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c invoke)
                            || invoke.getRegisterCount() != 1) throw new IllegalStateException("Unexpected discovery restart call");
                    code.set(i, new ImmutableInstruction35c(restore ? Opcode.INVOKE_VIRTUAL : Opcode.INVOKE_STATIC,
                            1, invoke.getRegisterC(), 0, 0, 0, 0,
                            new ImmutableMethodReference(restore ? "Lcom/qualcomm/wfd/client/XrealWfdManager;" : COMPAT,
                                    "startSearch", restore ? List.of() : List.of("Ljava/lang/Object;"), "V")));
                    changed++;
                }
            }
            if (changed != 1) throw new IllegalStateException("Expected one discovery restart in " + method);
        } else {
            int changed = 0;
            for (int i = 0; i < code.size(); i++) {
                if (call(code.get(i), restore ? COMPAT : DEBUG, restore ? "isMiracastReady" : "isMiracastEnable",
                        restore ? List.of("Ljava/lang/Object;") : List.of(), "Z")) {
                    code.set(i, restore ? originalGate() : readyGate(receiver));
                    changed++;
                }
            }
            if (changed != 1) throw new IllegalStateException("Expected one WFD gate in " + method + ": " + changed);
        }
        // Gate replacements preserve code-unit counts and original debug positions.
        return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(), method.getReturnType(),
                method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
                new ImmutableMethodImplementation(method.getImplementation().getRegisterCount(), code,
                        method.getImplementation().getTryBlocks(), method.getImplementation().getDebugItems()));
    }

    private static void verifyClient(Iterable<? extends ClassDef> classes) {
        Set<String> found = new TreeSet<>();
        int hooks = 0;
        for (ClassDef cls : classes) {
            for (Method method : cls.getMethods()) {
                if (cls.getType().equals(COMPAT)) {
                    String name = method.getName();
                    List<String> parameters = method.getParameterTypes().stream().map(Object::toString).toList();
                    String result = method.getReturnType();
                    if ((name.equals("install") && parameters.equals(List.of("Landroid/content/Context;")) && result.equals("V"))
                            || (name.equals("isMiracastEnabled") && parameters.isEmpty() && result.equals("Z"))
                            || (name.equals("isMiracastReady") && parameters.equals(List.of("Ljava/lang/Object;")) && result.equals("Z"))
                            || ((name.equals("onCastServiceStart") || name.equals("startSearch"))
                                    && parameters.equals(List.of("Ljava/lang/Object;")) && result.equals("V"))) {
                        if ((method.getAccessFlags() & 9) != 9 || method.getImplementation() == null) {
                            throw new IllegalStateException("Invalid wireless cast client method: " + method);
                        }
                        found.add(name);
                    }
                }
                if (!cls.getType().equals("Ldev/beampro/port/PortStatus;") || !method.getName().equals("install")
                        || method.getImplementation() == null) continue;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (call(instruction, COMPAT, "install", List.of("Landroid/content/Context;"), "V")) {
                        if (instruction.getOpcode() != Opcode.INVOKE_STATIC && instruction.getOpcode() != Opcode.INVOKE_STATIC_RANGE) {
                            throw new IllegalStateException("Invalid wireless cast initialization invocation");
                        }
                        hooks++;
                    }
                }
            }
        }
        if (!found.equals(Set.of("install", "isMiracastEnabled", "isMiracastReady", "onCastServiceStart", "startSearch")) || hooks != 1) {
            throw new IllegalStateException("Missing or stale wireless cast client: methods=" + found + ", initialization hooks=" + hooks);
        }
        System.out.println("verify-client: Miracast capability and readiness helper is initialized in PortStatus.install");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !Set.of("patch", "restore", "verify", "fingerprint", "verify-client").contains(args[0])) {
            throw new IllegalArgumentException("patch|restore|verify|fingerprint|verify-client INPUT OUTPUT");
        }
        String mode = args[0];
        var input = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        if (mode.equals("verify-client")) {
            verifyClient(input.getClasses());
            return;
        }
        List<ClassDef> output = new ArrayList<>();
        Set<String> found = new TreeSet<>();
        for (ClassDef cls : input.getClasses()) {
            List<Method> methods = new ArrayList<>();
            boolean changed = false;
            for (Method method : cls.getMethods()) {
                String name = key(method);
                if (SOURCE.containsKey(name)) {
                    if (!found.add(name) || method.getImplementation() == null) throw new IllegalStateException("Invalid source method: " + name);
                    if (mode.equals("fingerprint")) {
                        System.out.println(name + " " + fingerprint(method));
                    } else {
                        Method baseline = mode.equals("patch") ? method : rewrite(method, true);
                        String digest = fingerprint(baseline);
                        if (!SOURCE.get(name).equals(digest)) throw new IllegalStateException("Unexpected source method: " + name + " " + digest);
                        if (!mode.equals("patch") && !fingerprint(method).equals(fingerprint(rewrite(baseline, false)))) {
                            throw new IllegalStateException("Unexpected wireless cast patch payload: " + name);
                        }
                        method = mode.equals("patch") ? rewrite(method, false) : baseline;
                        changed = true;
                    }
                }
                methods.add(method);
            }
            output.add(changed ? new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                    cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods) : cls);
        }
        if (!found.equals(SOURCE.keySet())) throw new IllegalStateException("Missing wireless cast methods: " + found);
        if (mode.equals("patch") || mode.equals("restore")) DexPool.writeTo(args[2], new ImmutableDexFile(input.getOpcodes(), output));
        System.out.println(mode + ": checked " + found.size() + " scoped Miracast methods; AirPlay startup is preserved");
    }
}
