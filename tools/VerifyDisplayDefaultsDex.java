import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.File;
import java.util.*;

/** Compare the device-family startup selector with the unmodified port baseline. */
public final class VerifyDisplayDefaultsDex {
    private static byte[] selector(String apk) throws Exception {
        var container = DexFileFactory.loadDexContainer(new File(apk), Opcodes.forApi(33));
        List<Method> matches = new ArrayList<>();
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cls : container.getEntry(entry).getDexFile().getClasses()) {
                if (!cls.getType().equals("Lai/nreal/nebula/manager/XREALManager;")) continue;
                for (Method method : cls.getMethods()) {
                    if (!method.getName().equals("getFinalDisplayConfig")) continue;
                    var body = method.getImplementation();
                    if (!method.getParameterTypes().isEmpty() || !method.getReturnType().equals("I") || body == null)
                        throw new IllegalStateException("Unexpected display startup selector");
                    matches.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                            method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
                            new ImmutableMethodImplementation(body.getRegisterCount(), body.getInstructions(), body.getTryBlocks(), List.of())));
                }
            }
        }
        if (matches.size() != 1) throw new IllegalStateException("Expected one display selector: " + matches.size());
        var cls = new ImmutableClassDef("Lai/nreal/nebula/manager/XREALManager;", 1,
                "Ljava/lang/Object;", List.of(), null, Set.of(), List.of(), matches);
        MemoryDataStore data = new MemoryDataStore();
        DexPool.writeTo(data, new ImmutableDexFile(Opcodes.forApi(33), List.of(cls)));
        return data.getData();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("BASELINE_APK CANDIDATE_APK");
        if (!Arrays.equals(selector(args[0]), selector(args[1])))
            throw new IllegalStateException("Device-specific startup display defaults differ from baseline");
        System.out.println("Original device-specific display selector retained byte for byte");
    }
}
