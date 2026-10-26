import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import java.util.HashSet;
import java.util.Set;

/** Verify the two scoped hooks and the per-process client initialization. */
public final class VerifyQuickAccessDex {
    public static void main(String[] args) throws Exception {
        var container = DexFileFactory.loadDexContainer(new java.io.File(args[0]), Opcodes.forApi(33));
        Set<String> found = new HashSet<>();
        for (String name : container.getDexEntryNames()) {
            for (var cls : container.getEntry(name).getDexFile().getClasses()) {
                for (var method : cls.getMethods()) {
                    if (cls.getType().equals("Ldev/beampro/port/QuickAccessCompat;")) found.add("client:" + method.getName());
                    if (method.getImplementation() == null) continue;
                    for (var instruction : method.getImplementation().getInstructions()) {
                        if (!(instruction instanceof ReferenceInstruction)) continue;
                        var reference = ((ReferenceInstruction) instruction).getReference();
                        if (!(reference instanceof MethodReference)) continue;
                        var call = (MethodReference) reference;
                        if (cls.getType().equals("Lcom/xreal/evapro/WifiHelper;") && call.getName().equals("getSSID")) {
                            if (!call.getDefiningClass().equals("Ldev/beampro/port/QuickAccessCompat;")) {
                                throw new IllegalStateException("Wi-Fi still reads a redacted SSID directly");
                            }
                            found.add("wifi-hook");
                        }
                        if (cls.getType().equals("Lcom/xreal/evapro/AppTaskUtil;") && method.getName().equals("getPackageNameByIntent")
                                && call.getDefiningClass().equals("Ldev/beampro/port/QuickAccessCompat;")
                                && call.getName().equals("getPackageNameByIntent")) found.add("intent-hook");
                        if (cls.getType().equals("Ldev/beampro/port/PortStatus;") && method.getName().equals("install")
                                && call.getDefiningClass().equals("Ldev/beampro/port/QuickAccessCompat;")
                                && call.getName().equals("install")) found.add("initialization");
                    }
                }
            }
        }
        Set<String> expected = Set.of("wifi-hook", "intent-hook", "initialization",
                "client:getSSID", "client:getPackageNameByIntent", "client:install");
        if (!found.containsAll(expected)) throw new IllegalStateException("Incomplete quick-access hooks: " + found);
        System.out.println("Verified Wi-Fi SSID hook, settings navigation hook, and initialized helper client");
    }
}
