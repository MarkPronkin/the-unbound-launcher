import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.iface.value.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue;
import com.android.tools.smali.dexlib2.rewriter.*;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Rewrite app-ID string values while preserving Java types, methods and JNI names. */
public final class RepackageDex {
    static DexFile rewrite(DexFile input, String from, String to) {
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return instruction -> {
                    if (!(instruction instanceof ReferenceInstruction ref)
                            || !(ref.getReference() instanceof StringReference text)
                            || !text.getString().contains(from)) return instruction;
                    var replacement = new ImmutableStringReference(text.getString().replace(from, to));
                    if (instruction instanceof OneRegisterInstruction register) {
                        if (instruction.getOpcode() == Opcode.CONST_STRING)
                            return new ImmutableInstruction21c(Opcode.CONST_STRING, register.getRegisterA(), replacement);
                        if (instruction.getOpcode() == Opcode.CONST_STRING_JUMBO)
                            return new ImmutableInstruction31c(Opcode.CONST_STRING_JUMBO, register.getRegisterA(), replacement);
                    }
                    throw new IllegalStateException("Unexpected app-ID string instruction");
                };
            }
            @Override public Rewriter<EncodedValue> getEncodedValueRewriter(Rewriters rewriters) {
                return new EncodedValueRewriter(rewriters) {
                    @Override public EncodedValue rewrite(EncodedValue value) {
                        if (value instanceof StringEncodedValue text)
                            return new ImmutableStringEncodedValue(text.getValue().replace(from, to));
                        return super.rewrite(value);
                    }
                };
            }
        });
        return rewriter.getDexFileRewriter().rewrite(input);
    }
    static byte[] bytes(DexFile dex) throws Exception {
        var store = new MemoryDataStore();
        DexPool.writeTo(store, dex);
        return store.getData();
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("INPUT OUTPUT OLD_PACKAGE NEW_PACKAGE");
        var input = DexFileFactory.loadDexFile(args[0], Opcodes.forApi(33));
        byte[] result = bytes(rewrite(input, args[2], args[3]));
        Path output = Path.of(args[1]);
        Files.write(output, result);
        var relocated = DexFileFactory.loadDexFile(output.toFile(), input.getOpcodes());
        // Canonical comparison covers every class, field, annotation, instruction,
        // exception handler and debug entry after reversing only the app-ID strings.
        if (!Arrays.equals(bytes(input), bytes(rewrite(relocated, args[3], args[2])))) {
            Files.delete(output);
            throw new IllegalStateException("Package relocation changed unrelated DEX semantics");
        }
        System.out.println("Relocated package strings; original DEX semantics verified: " + output.getFileName());
    }
}
