import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.util.*;

/** Remove the stock reader and Binder callbacks. Unity still loads both Java adapter names. */
public final class PatchNotificationsDex {
    static final String PREFIX = "Lcom/xreal/evapro/";
    static final String LEGACY = "Lai/nreal/android/nebulaspaceproxy/NebulaNotificationListenerService;";
    static final String HELPER = PREFIX + "NotifyHelper;";
    static final String LISTENER = "Landroid/service/notification/NotificationListenerService;";
    static final List<String> READER_TYPES = List.of(
        "NotifyCallback", "NotifyCallback$Default", "NotifyCallback$Stub", "NotifyCallback$Stub$Proxy",
        "NotifyHelper$1", "NotifyHelper$2", "NotifyService", "NotifyService$1", "NotifyService$NotifyServiceBinder",
        "NotifyServiceManager", "NotifyServiceManager$Default", "NotifyServiceManager$Stub", "NotifyServiceManager$Stub$Proxy");

    public static void main(String[] args) throws Exception {
        String target = switch (args[0]) {
            case "classes.dex" -> LEGACY;
            case "classes2.dex" -> HELPER;
            default -> throw new IllegalArgumentException("Unexpected notification DEX: " + args[0]);
        };
        var api = Opcodes.forApi(33);
        var input = DexFileFactory.loadDexFile(args[1], api);
        var adapters = DexFileFactory.loadDexFile(args[2], api);
        ClassDef adapter = adapters.getClasses().stream().filter(c -> c.getType().equals(target)).findFirst().orElseThrow();
        if (!"Ljava/lang/Object;".equals(adapter.getSuperclass())) throw new IllegalStateException("Expected a plain Java adapter");
        Set<String> pending = new HashSet<>();
        if (target.equals(HELPER)) for (String type : READER_TYPES) pending.add(PREFIX + type + ";");
        String stockListener = target.equals(LEGACY) ? LEGACY : PREFIX + "NotifyService;";
        boolean replaced = false;
        List<ClassDef> output = new ArrayList<>();
        for (ClassDef cls : input.getClasses()) {
            String name = cls.getType();
            if (LISTENER.equals(cls.getSuperclass()) != name.equals(stockListener)) {
                throw new IllegalStateException("Unexpected notification listener in " + args[0] + ": " + name);
            }
            if (pending.remove(name)) continue;
            if (name.equals(target)) {
                output.add(adapter);
                replaced = true;
            } else {
                output.add(cls);
            }
        }
        if (!pending.isEmpty() || !replaced) throw new IllegalStateException("Unexpected stock notification classes");
        DexPool.writeTo(args[3], new ImmutableDexFile(input.getOpcodes(), output));
    }
}
