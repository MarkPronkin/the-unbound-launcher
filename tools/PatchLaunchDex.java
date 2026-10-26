import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21s;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.rewriter.MethodRewriter;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Collections;

/** Pinned launch-service extraction/client replacement; no resource or native rewrites. */
public final class PatchLaunchDex {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("MODE INPUT OUTPUT");
        var original = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        List<ClassDef> output = new ArrayList<>();
        Set<String> edits = new TreeSet<>();
        Set<String> settingsEdits = new TreeSet<>();
        Set<String> bluetoothEdits = new TreeSet<>();
        Set<String> quickAccessEdits = new TreeSet<>();
        Set<String> recentAppsEdits = new TreeSet<>();
        DexRewriter quickAccessRewrite = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                return method -> {
                    if (method.getDefiningClass().equals("Lcom/xreal/evapro/AppTaskUtil;")) {
                        String name = method.getName();
                        if (name.equals("getRecentTasks") || name.equals("removeRecentTask")) {
                            boolean list = name.equals("getRecentTasks");
                            if (!method.getParameterTypes().equals(List.of(list ? "Landroid/content/Context;" : "I"))
                                    || !method.getReturnType().equals(list ? "[Lcom/xreal/evapro/TaskInfo;" : "V")) {
                                throw new IllegalStateException("Unexpected recent-apps signature");
                            }
                            List<Instruction> instructions = new ArrayList<>();
                            instructions.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                                    new ImmutableMethodReference("Ldev/beampro/port/RecentAppsCompat;", name,
                                            method.getParameterTypes(), list ? "[Ljava/lang/Object;" : "V")));
                            if (list) {
                                instructions.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
                                instructions.add(new ImmutableInstruction21c(Opcode.CHECK_CAST, 0,
                                        new ImmutableTypeReference("[Lcom/xreal/evapro/TaskInfo;")));
                                instructions.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
                            } else instructions.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
                            recentAppsEdits.add(name);
                            return new ImmutableMethod(method.getDefiningClass(), name, method.getParameters(),
                                    method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
                                    new ImmutableMethodImplementation(1, instructions, Collections.emptyList(), Collections.emptyList()));
                        }
                    }
                    if (!method.getDefiningClass().equals("Lcom/xreal/evapro/AppTaskUtil;")
                            || !method.getName().equals("getPackageNameByIntent")) {
                        return new MethodRewriter(rewriters).rewrite(method);
                    }
                    if (!method.getParameterTypes().equals(List.of("Landroid/content/Intent;", "Landroid/content/Context;"))
                            || !method.getReturnType().equals("Ljava/lang/String;")) {
                        throw new IllegalStateException("Unexpected Intent resolver signature");
                    }
                    quickAccessEdits.add("AppTaskUtil.getPackageNameByIntent");
                    var implementation = new ImmutableMethodImplementation(2, List.of(
                            new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 1, 0, 0, 0,
                                    new ImmutableMethodReference("Ldev/beampro/port/QuickAccessCompat;",
                                            "getPackageNameByIntent", method.getParameterTypes(), method.getReturnType())),
                            new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                            new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), Collections.emptyList(), Collections.emptyList());
                    return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                            method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), implementation);
                };
            }
            @Override public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return instruction -> {
                    if (!(instruction instanceof ReferenceInstruction)) return instruction;
                    var reference = ((ReferenceInstruction) instruction).getReference();
                    if (!(reference instanceof MethodReference)) return instruction;
                    var method = (MethodReference) reference;
                    if (!method.getDefiningClass().equals("Landroid/net/wifi/WifiInfo;") || !method.getName().equals("getSSID")) return instruction;
                    quickAccessEdits.add("WifiHelper.getSSID");
                    var target = new ImmutableMethodReference("Ldev/beampro/port/QuickAccessCompat;", "getSSID",
                            List.of("Landroid/net/wifi/WifiInfo;"), "Ljava/lang/String;");
                    if (instruction.getOpcode() == Opcode.INVOKE_VIRTUAL) {
                        var call = (Instruction35c) instruction;
                        return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, call.getRegisterCount(), call.getRegisterC(),
                                call.getRegisterD(), call.getRegisterE(), call.getRegisterF(), call.getRegisterG(), target);
                    }
                    if (instruction.getOpcode() == Opcode.INVOKE_VIRTUAL_RANGE) {
                        var call = (Instruction3rc) instruction;
                        return new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, call.getStartRegister(), call.getRegisterCount(), target);
                    }
                    throw new IllegalStateException("Unexpected Wi-Fi SSID invocation opcode");
                };
            }
        });
        DexRewriter bluetoothRewrite = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return instruction -> {
                    if (!(instruction instanceof ReferenceInstruction)) return instruction;
                    var reference = ((ReferenceInstruction) instruction).getReference();
                    if (!(reference instanceof MethodReference)) return instruction;
                    var method = (MethodReference) reference;
                    boolean bonded = method.getDefiningClass().equals("Landroid/bluetooth/BluetoothAdapter;")
                            && method.getName().equals("getBondedDevices");
                    boolean connected = method.getDefiningClass().equals("Landroid/bluetooth/BluetoothManager;")
                            && method.getName().equals("getConnectedDevices");
                    if (!bonded && !connected) return instruction;
                    List<String> parameters = new ArrayList<>();
                    parameters.add(method.getDefiningClass());
                    for (var type : method.getParameterTypes()) parameters.add(type.toString());
                    var target = new ImmutableMethodReference("Ldev/beampro/port/BluetoothCompat;",
                            method.getName(), parameters, method.getReturnType());
                    bluetoothEdits.add(method.getDefiningClass() + "->" + method.getName());
                    if (instruction.getOpcode() == Opcode.INVOKE_VIRTUAL) {
                        var call = (Instruction35c) instruction;
                        return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, call.getRegisterCount(), call.getRegisterC(),
                                call.getRegisterD(), call.getRegisterE(), call.getRegisterF(), call.getRegisterG(), target);
                    }
                    if (instruction.getOpcode() == Opcode.INVOKE_VIRTUAL_RANGE) {
                        var call = (Instruction3rc) instruction;
                        return new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, call.getStartRegister(), call.getRegisterCount(), target);
                    }
                    throw new IllegalStateException("Unexpected Bluetooth invocation opcode");
                };
            }
        });
        DexRewriter settingsRewrite = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return instruction -> {
                    if (!(instruction instanceof ReferenceInstruction)) return instruction;
                    var reference = ((ReferenceInstruction) instruction).getReference();
                    if (!(reference instanceof MethodReference)) return instruction;
                    MethodReference method = (MethodReference) reference;
                    String name = null;
                    if (method.getDefiningClass().equals("Landroid/provider/Settings$Global;")) {
                        if (method.getName().equals("putInt") && method.getParameterTypes().size() == 3) name = "putGlobalInt";
                        if (method.getName().equals("getInt") && method.getParameterTypes().size() == 3) name = "getGlobalInt";
                    } else if (method.getDefiningClass().equals("Landroid/provider/Settings$System;")) {
                        if (method.getName().equals("putString")) name = "putSystemString";
                        if (method.getName().equals("getString")) name = "getSystemString";
                    }
                    if (name == null) return instruction;
                    var target = new ImmutableMethodReference("Ldev/beampro/port/SettingsCompat;", name,
                            method.getParameterTypes(), method.getReturnType());
                    settingsEdits.add(method.getDefiningClass() + "->" + method.getName() + " to " + name);
                    if (instruction.getOpcode() == Opcode.INVOKE_STATIC) {
                        var call = (Instruction35c) instruction;
                        return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, call.getRegisterCount(), call.getRegisterC(),
                                call.getRegisterD(), call.getRegisterE(), call.getRegisterF(), call.getRegisterG(), target);
                    }
                    if (instruction.getOpcode() == Opcode.INVOKE_STATIC_RANGE) {
                        var call = (Instruction3rc) instruction;
                        return new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, call.getStartRegister(), call.getRegisterCount(), target);
                    }
                    throw new IllegalStateException("Unexpected settings invocation opcode");
                };
            }
        });
        Set<String> otaBlocks = Set.of(
                "Lai/nreal/glasses/control/OtaManager;->startOta",
                "Lai/nreal/glasses/control/OtaManager;->setGlassToBoot",
                "Lai/nreal/glasses/control/OtaManagerWrap;->startOta",
                "Lai/nreal/glasses/control/OtaManagerWrap;->i",
                "Lai/nreal/glasses/control/ota;->startOta");
        DexRewriter rewrite = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                return method -> {
                    if (!args[0].equals("client")) return new MethodRewriter(rewriters).rewrite(method);
                    String key = method.getDefiningClass() + "->" + method.getName();
                    com.android.tools.smali.dexlib2.iface.MethodImplementation implementation = null;
                    if (otaBlocks.contains(key)) {
                        if (!method.getReturnType().equals("I") || (method.getAccessFlags() & 0x100) != 0) {
                            throw new IllegalStateException("Unexpected OTA method signature: " + key);
                        }
                        int registers = (method.getAccessFlags() & 8) == 0 ? 1 : 0;
                        for (var type : method.getParameterTypes()) registers += type.equals("J") || type.equals("D") ? 2 : 1;
                        implementation = new ImmutableMethodImplementation(Math.max(registers, 1), List.of(
                                new ImmutableInstruction11n(Opcode.CONST_4, 0, -1),
                                new ImmutableInstruction11x(Opcode.RETURN, 0)), Collections.emptyList(), Collections.emptyList());
                        edits.add(key + " returns failure without entering firmware update/boot mode");
                    } else if (key.equals("Lai/nreal/nebula/NebulaApplication;->onCreate")) {
                        var mutable = new MutableMethodImplementation(method.getImplementation());
                        mutable.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                                mutable.getRegisterCount() - 1, 1,
                                new ImmutableMethodReference("Ldev/beampro/port/PortStatus;", "install", List.of("Landroid/content/Context;"), "V")));
                        implementation = mutable;
                        edits.add(key + " checks replacement LaunchService connection");
                    } else if (key.equals("Lai/nreal/activitylife/NRFakeActivity;->onPause")) {
                        // Android can pause a visible secondary-display activity during startup.
                        // The original onStop still suspends Unity when that display is hidden.
                        implementation = new ImmutableMethodImplementation(1, List.of(
                                new ImmutableInstruction35c(Opcode.INVOKE_SUPER, 1, 0, 0, 0, 0, 0,
                                        new ImmutableMethodReference("Landroid/app/Activity;", "onPause", List.of(), "V")),
                                new ImmutableInstruction10x(Opcode.RETURN_VOID)), Collections.emptyList(), Collections.emptyList());
                        edits.add(key + " keeps visible glasses rendering until onStop");
                    }
                    if (implementation == null) return method;
                    return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                            method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), implementation);
                };
            }
            @Override public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return instruction -> {
                    if (instruction.getOpcode() == Opcode.CONST_16 && ((Instruction21s) instruction).getNarrowLiteral() == 1485) {
                        return new ImmutableInstruction21s(Opcode.CONST_16, ((Instruction21s) instruction).getRegisterA(), 1481);
                    }
                    return instruction;
                };
            }
        });
        int removed = 0;
        for (ClassDef cls : original.getClasses()) {
            if (args[0].equals("server")) {
                if (!cls.getType().startsWith("Lai/nreal/nebula/service/")) continue;
                if (cls.getType().equals("Lai/nreal/nebula/service/LaunchManager;")) {
                    cls = rewrite.getClassDefRewriter().rewrite(cls);
                }
                output.add(cls);
            } else if (args[0].equals("client")) {
                if (cls.getType().equals("Lai/nreal/nebula/service/LaunchService;")) { removed++; continue; }
                if (cls.getType().equals("Lai/nreal/nebula/NebulaApplication;") || cls.getType().startsWith("Lai/nreal/glasses/control/")
                        || cls.getType().equals("Lai/nreal/activitylife/NRFakeActivity;")) {
                    cls = rewrite.getClassDefRewriter().rewrite(cls);
                } else if (cls.getType().equals("Lai/nreal/nebula/util/helper/SystemVidPidConfig;")) {
                    cls = settingsRewrite.getClassDefRewriter().rewrite(cls);
                }
                output.add(cls);
            } else if (args[0].equals("compat")) {
                if (cls.getType().equals("Lcom/xreal/evapro/EvaProHelper;")) cls = settingsRewrite.getClassDefRewriter().rewrite(cls);
                if (cls.getType().equals("Lcom/xreal/evapro/BluetoothHelper;")) cls = bluetoothRewrite.getClassDefRewriter().rewrite(cls);
                if (cls.getType().equals("Lcom/xreal/evapro/WifiHelper;") || cls.getType().equals("Lcom/xreal/evapro/AppTaskUtil;")) {
                    cls = quickAccessRewrite.getClassDefRewriter().rewrite(cls);
                }
                output.add(cls);
            } else throw new IllegalArgumentException("Unknown mode");
        }
        if (args[0].equals("client") && removed != 1) throw new IllegalStateException("Expected one original LaunchService");
        if (output.isEmpty()) throw new IllegalStateException("No classes selected");
        DexPool.writeTo(args[2], new ImmutableDexFile(original.getOpcodes(), output));
        if (args[0].equals("client") && edits.size() != 7) throw new IllegalStateException("Expected five OTA guards, one startup hook and one lifecycle fix: " + edits);
        if ((args[0].equals("client") || args[0].equals("compat")) && settingsEdits.size() != 2) {
            throw new IllegalStateException("Expected two scoped settings call rewrites: " + settingsEdits);
        }
        System.out.println(args[0] + ": wrote " + output.size() + " classes");
        for (String edit : edits) System.out.println(edit);
        for (String edit : settingsEdits) System.out.println(edit);
        if (args[0].equals("compat") && bluetoothEdits.size() != 2) throw new IllegalStateException("Expected two optional Bluetooth discovery guards: " + bluetoothEdits);
        for (String edit : bluetoothEdits) System.out.println("Permission-aware optional Bluetooth discovery: " + edit);
        if (args[0].equals("compat") && quickAccessEdits.size() != 2) throw new IllegalStateException("Expected two quick-access hooks: " + quickAccessEdits);
        for (String edit : quickAccessEdits) System.out.println("Signed helper quick-access compatibility: " + edit);
        if (args[0].equals("compat") && !recentAppsEdits.equals(Set.of("getRecentTasks", "removeRecentTask"))) {
            throw new IllegalStateException("Expected two launcher-history hooks: " + recentAppsEdits);
        }
        for (String edit : recentAppsEdits) System.out.println("Launcher-only recent apps: " + edit);
    }
}
