import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.util.*;

/** Replace a fixed set of receiver call sites without changing registers or instruction sizes. */
public final class PatchReceiverRegistrations {
    private static final String CONTEXT = "Landroid/content/Context;";
    private static final String COMPAT = "Ldev/beampro/port/ReceiverRegistration;";
    private static final String INTENT = "Landroid/content/Intent;";
    private static final String RECEIVER = "Landroid/content/BroadcastReceiver;";
    private static final String FILTER = "Landroid/content/IntentFilter;";

    record Site(String caller, String owner, String helper) {}

    static final List<Site> MAIN = List.of(
        new Site("Lai/nreal/nebula/space/SystemBroadcastReceiver;->registerReceiver()V", CONTEXT, "internal"),
        new Site("Lai/nreal/android/nebulaspaceproxy/badge/SamsungBadgeHelper;->startMonitorBadge(" + CONTEXT + ")V",
                 CONTEXT, "samsungBadge"));
    static final List<Site> SDK = List.of(
        new Site("Lcom/xreal/evapro/LaunchEntry;->init(" + CONTEXT + ")V", CONTEXT, "internal"),
        new Site("Lcom/xreal/evapro/ProjectionHelper;->init(" + CONTEXT + ")V", CONTEXT, "internal"),
        new Site("Lcom/rockchip/wfd/WifiDisplayService;->onCreate()V", "Lcom/rockchip/wfd/WifiDisplayService;", "internal"),
        new Site("Lcom/nreal/appinstall/ApkManager;->listenComplete()V", CONTEXT, "downloadComplete"),
        new Site("Lcom/jeroenvanpienbroek/nativekeyboard/NativeKeyboard;->registerToSmsBroadcastReceiver()V",
                 "Landroid/app/Activity;", "smsRetriever"));

    static Method rewrite(Method method, Site site, boolean restore) {
        var oldCall = new ImmutableMethodReference(site.owner(), "registerReceiver", List.of(RECEIVER, FILTER), INTENT);
        var newCall = new ImmutableMethodReference(COMPAT, site.helper(), List.of(CONTEXT, RECEIVER, FILTER), INTENT);
        var impl = method.getImplementation();
        List<Instruction> code = new ArrayList<>();
        int changes = 0;
        for (Instruction ins : impl.getInstructions()) {
            if (ins instanceof Instruction35c call && call.getRegisterCount() == 3
                    && call.getOpcode() == (restore ? Opcode.INVOKE_STATIC : Opcode.INVOKE_VIRTUAL)
                    && call.getReference().equals(restore ? newCall : oldCall)) {
                ins = new ImmutableInstruction35c(restore ? Opcode.INVOKE_VIRTUAL : Opcode.INVOKE_STATIC, 3,
                    call.getRegisterC(), call.getRegisterD(), call.getRegisterE(), 0, 0, restore ? oldCall : newCall);
                changes++;
            }
            code.add(ins);
        }
        if (changes != 1) throw new IllegalStateException("Expected one receiver registration in " + site.caller());
        return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(), method.getReturnType(),
            method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
            new ImmutableMethodImplementation(impl.getRegisterCount(), code, impl.getTryBlocks(), impl.getDebugItems()));
    }

    public static void main(String[] args) throws Exception {
        List<Site> sites = switch (args[0]) {
            case "classes.dex" -> MAIN;
            case "classes2.dex" -> SDK;
            default -> throw new IllegalArgumentException(args[0]);
        };
        boolean restore = args[1].equals("restore");
        var pending = new HashMap<String, Site>();
        for (Site site : sites) pending.put(site.caller(), site);
        var input = DexFileFactory.loadDexFile(args[2], Opcodes.forApi(33));
        List<ClassDef> classes = new ArrayList<>();
        for (ClassDef cls : input.getClasses()) {
            List<Method> methods = new ArrayList<>();
            boolean changed = false;
            for (Method method : cls.getMethods()) {
                Site site = pending.remove(method.toString());
                methods.add(site == null ? method : rewrite(method, site, restore));
                changed |= site != null;
            }
            classes.add(changed ? new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(),
                cls.getInterfaces(), cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods) : cls);
        }
        if (!pending.isEmpty()) throw new IllegalStateException("Missing receiver registrations " + pending.keySet());
        DexPool.writeTo(args[3], new ImmutableDexFile(input.getOpcodes(), classes));
    }
}
