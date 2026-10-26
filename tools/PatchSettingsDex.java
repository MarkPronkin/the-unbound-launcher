import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction32x;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction32x;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Adds/removes phone-settings presentation hooks without replacing any original behavior. */
public final class PatchSettingsDex {
    static final String UI = "Ldev/beampro/settings/SettingsUi;";
    static final Map<String, Set<String>> TARGETS = Map.of(
        "Lai/nreal/nebula/activity/setting/SettingActivity;", Set.of(
            "onCreate", "onResume", "onDisconnect", "onSdkSuccess", "onScreenColor",
            "onSleepTime", "onUVCView", "updateViewFromDb", "check3DBtn"),
        "Lai/nreal/nebula/activity/setting/permission_manager/PermissionManagerActivity;", Set.of("onCreate"));

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("patch") || args[0].equals("restore"))) {
            throw new IllegalArgumentException("patch|restore INPUT OUTPUT");
        }
        boolean restore = args[0].equals("restore");
        var dex = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        List<ClassDef> classes = new ArrayList<>();
        Map<String, Integer> edits = new TreeMap<>();
        for (ClassDef cls : dex.getClasses()) {
            Set<String> names = TARGETS.get(cls.getType());
            if (names == null) { classes.add(cls); continue; }
            List<Method> methods = new ArrayList<>();
            for (Method method : cls.getMethods()) {
                if (!names.contains(method.getName())) { methods.add(method); continue; }
                if (method.getImplementation() == null || !method.getReturnType().equals("V")) {
                    throw new IllegalStateException("Unexpected target signature");
                }
                var implementation = new MutableMethodImplementation(method.getImplementation());
                int parameterWords = 1;
                for (var parameter : method.getParameterTypes()) {
                    parameterWords += parameter.equals("J") || parameter.equals("D") ? 2 : 1;
                }
                // D8 reuses p0 near the end of several original methods. Retain this in
                // an extra register and copy incoming parameters to their original slots.
                int originalRegisters = implementation.getRegisterCount() - (restore ? parameterWords + 1 : 0);
                int receiver = originalRegisters;
                boolean connected = method.getName().equals("onSdkSuccess");
                if (connected && !method.getParameterTypes().equals(List.of("I", "Ljava/lang/String;", "Ljava/lang/String;"))) {
                    throw new IllegalStateException("Unexpected glasses callback signature");
                }
                String name = method.getName().equals("onCreate") ? "install"
                    : connected ? "glassesConnected" : method.getName().equals("onDisconnect") ? "glassesDisconnected" : "refresh";
                int arguments = connected ? 2 : 1;
                var parameters = connected ? List.of("Landroid/app/Activity;", "I") : List.of("Landroid/app/Activity;");
                var target = new ImmutableMethodReference(UI, name, parameters, "V");
                int count = 0;
                for (int i = implementation.getInstructions().size() - 1; i >= 0; i--) {
                    var instruction = implementation.getInstructions().get(i);
                    if (restore) {
                        if (instruction instanceof ReferenceInstruction
                                && ((ReferenceInstruction) instruction).getReference() instanceof MethodReference) {
                            var reference = (MethodReference) ((ReferenceInstruction) instruction).getReference();
                            if (reference.getDefiningClass().equals(UI)) {
                                if (!reference.getName().equals(name) || i + 1 >= implementation.getInstructions().size()
                                        || implementation.getInstructions().get(i + 1).getOpcode() != Opcode.RETURN_VOID
                                        || instruction.getOpcode() != Opcode.INVOKE_STATIC_RANGE
                                        || ((Instruction3rc)instruction).getStartRegister() != receiver
                                        || ((Instruction3rc)instruction).getRegisterCount() != arguments) {
                                    throw new IllegalStateException("Unexpected settings hook");
                                }
                                implementation.removeInstruction(i); count++;
                            }
                        }
                    } else if (instruction.getOpcode() == Opcode.RETURN_VOID) {
                        implementation.addInstruction(i, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, receiver, arguments, target));
                        count++;
                    }
                }
                if (count == 0) throw new IllegalStateException("No hook: " + method);
                int prefixSize = method.getParameterTypes().size() + 2 + (connected ? 1 : 0);
                if (restore) {
                    var saved = (Instruction32x) implementation.getInstructions().get(0);
                    if (saved.getRegisterA() != receiver || saved.getRegisterB() != originalRegisters + 1) {
                        throw new IllegalStateException("Receiver was not preserved");
                    }
                    if (connected) {
                        var model = (Instruction32x) implementation.getInstructions().get(prefixSize - 1);
                        if (model.getOpcode() != Opcode.MOVE_16 || model.getRegisterA() != receiver + 1 || model.getRegisterB() != receiver + 2) {
                            throw new IllegalStateException("Connected model argument was not preserved");
                        }
                    }
                    for (int i = 0; i < prefixSize; i++) {
                        var opcode = implementation.getInstructions().get(0).getOpcode();
                        if (opcode != Opcode.MOVE_OBJECT_16 && opcode != Opcode.MOVE_16 && opcode != Opcode.MOVE_WIDE_16) {
                            throw new IllegalStateException("Unexpected receiver-preservation prefix");
                        }
                        implementation.removeInstruction(0);
                    }
                } else {
                    List<BuilderInstruction32x> prefix = new ArrayList<>();
                    int incoming = originalRegisters + 1;
                    prefix.add(new BuilderInstruction32x(Opcode.MOVE_OBJECT_16, receiver, incoming));
                    int destination = originalRegisters - parameterWords;
                    prefix.add(new BuilderInstruction32x(Opcode.MOVE_OBJECT_16, destination++, incoming++));
                    for (var type : method.getParameterTypes()) {
                        boolean wide = type.equals("J") || type.equals("D");
                        var opcode = wide ? Opcode.MOVE_WIDE_16
                            : type.toString().startsWith("L") || type.toString().startsWith("[") ? Opcode.MOVE_OBJECT_16 : Opcode.MOVE_16;
                        prefix.add(new BuilderInstruction32x(opcode, destination, incoming));
                        destination += wide ? 2 : 1; incoming += wide ? 2 : 1;
                    }
                    // After copying incoming parameters, reuse their now-unused p0 slot
                    // to retain the model beside the saved Activity for invoke-static/range.
                    // Original D8 code can overwrite its parameter registers before return.
                    if (connected) prefix.add(new BuilderInstruction32x(Opcode.MOVE_16, receiver + 1, receiver + 2));
                    for (int i = prefix.size() - 1; i >= 0; i--) implementation.addInstruction(0, prefix.get(i));
                }
                edits.put(cls.getType() + "->" + method.getName(), count);
                methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                    method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
                    new ImmutableMethodImplementation(restore ? originalRegisters : originalRegisters + parameterWords + 1,
                        implementation.getInstructions(), implementation.getTryBlocks(), implementation.getDebugItems())));
            }
            classes.add(new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods));
        }
        if (edits.size() != 10) throw new IllegalStateException("Incomplete settings hooks: " + edits);
        DexFileFactory.writeDexFile(args[2], new ImmutableDexFile(dex.getOpcodes(), classes));
        edits.forEach((method, count) -> System.out.println(method + ": " + count));
    }
}
