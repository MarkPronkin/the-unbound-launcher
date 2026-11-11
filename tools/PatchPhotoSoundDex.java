import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Guard the photo cue before SoundPool.load; keep the stock playback body intact. */
public final class PatchPhotoSoundDex {
    static final String TARGET = "Lai/nreal/android/toolkitsproxy/MediaActionHelper;";
    static final String COMPAT = "Ldev/beampro/port/ExperimentalOptionsCompat;";

    static byte[] bytes(ClassDef cls) throws Exception {
        var store = new MemoryDataStore();
        DexPool.writeTo(store, new ImmutableDexFile(Opcodes.forApi(33), List.of(cls)));
        return store.getData();
    }

    static ClassDef patched(ClassDef cls) {
        if (!cls.getType().equals(TARGET)) return cls;
        List<Method> methods = new ArrayList<>();
        int edits = 0;
        for (Method method : cls.getMethods()) {
            if (!method.getName().equals("play")) { methods.add(method); continue; }
            if (!method.getParameterTypes().equals(List.of("I", "F")) || !method.getReturnType().equals("V")
                    || AccessFlags.STATIC.isSet(method.getAccessFlags()) || method.getImplementation() == null
                    || method.getImplementation().getRegisterCount() != 6)
                throw new IllegalStateException("Unexpected photo sound helper signature or registers");
            int loads = 0;
            for (Instruction instruction : method.getImplementation().getInstructions()) {
                if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference call) {
                    if (call.getDefiningClass().equals(COMPAT)) throw new IllegalStateException("Photo sound already patched");
                    if (call.getDefiningClass().equals("Landroid/media/SoundPool;") && call.getName().equals("load")) loads++;
                }
            }
            if (loads != 1) throw new IllegalStateException("Expected one stock sound load");
            var body = new MutableMethodImplementation(method.getImplementation());
            // Original p0=v3, resId=v4 and volume=v5 remain in their original registers.
            // v0 is a stock local, overwritten by the original first instruction.
            body.addInstruction(0, new BuilderInstruction22c(Opcode.IGET_OBJECT, 0, 3,
                    new ImmutableFieldReference(TARGET, "mContext", "Landroid/content/Context;")));
            body.addInstruction(1, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 4, 0, 0, 0,
                    new ImmutableMethodReference(COMPAT, "suppressPhotoSound", List.of("Landroid/content/Context;", "I"), "Z")));
            body.addInstruction(2, new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
            body.addInstruction(3, new BuilderInstruction10x(Opcode.RETURN_VOID));
            var original = body.newLabelForIndex(4);
            body.addInstruction(3, new BuilderInstruction21t(Opcode.IF_EQZ, 0, original));
            methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                    method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body));
            edits++;
        }
        if (edits != 1) throw new IllegalStateException("Missing photo sound play method");
        return new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("patch", "restore").contains(args[0]))
            throw new IllegalArgumentException("patch|restore INPUT OUTPUT BASELINE");
        boolean restore = args[0].equals("restore");
        var input = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        var baseline = DexFileFactory.loadDexFile(args[3], Opcodes.forApi(33));
        Map<String, ClassDef> expected = new HashMap<>();
        Set<String> changed = new HashSet<>();
        for (ClassDef cls : baseline.getClasses()) {
            ClassDef replacement = patched(cls);
            if (replacement != cls) changed.add(cls.getType());
            expected.put(cls.getType(), restore ? replacement : cls);
        }
        if (changed.size() != 1) throw new IllegalStateException("Missing photo sound helper class");
        List<ClassDef> output = new ArrayList<>();
        for (ClassDef cls : input.getClasses()) {
            ClassDef reference = expected.remove(cls.getType());
            if (reference == null || !Arrays.equals(bytes(cls), bytes(reference)))
                throw new IllegalStateException("Unexpected photo sound DEX change: " + cls.getType());
            output.add(restore ? cls : patched(cls));
        }
        if (!expected.isEmpty()) throw new IllegalStateException("Missing DEX classes");
        if (restore) Files.write(Path.of(args[2]), Files.readAllBytes(Path.of(args[3])));
        else DexPool.writeTo(args[2], new ImmutableDexFile(input.getOpcodes(), output));
        System.out.println(args[0] + ": optional photo sound guard; stock playback and remaining DEX preserved");
    }
}
