import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.*;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.File;
import java.util.*;

/** Run the selected signed APK's actual handoff code in the isolated Android fixture. */
public final class HandoffFixtureDex {
    static boolean selected(String type) {
        return List.of("ConnectionHandoff", "ConnectionAnimation", "PhonePointerCompat").stream()
                .anyMatch(name -> type.equals("Ldev/beampro/port/" + name + ";") || type.startsWith("Ldev/beampro/port/" + name + "$"));
    }
    static int authorities;
    static ClassDef fixtureAuthority(ClassDef cls) {
        List<Method> methods = new ArrayList<>();
        for (Method m : cls.getMethods()) {
            MethodImplementation impl = m.getImplementation();
            if (impl == null) { methods.add(m); continue; }
            List<Instruction> code = new ArrayList<>();
            for (Instruction instruction : impl.getInstructions()) {
                if (instruction instanceof ReferenceInstruction r && r.getReference() instanceof StringReference s
                        && s.getString().equals("content://dev.markpronkin.unbound.unbound.bridge")) {
                    var ref = new ImmutableStringReference("content://dev.beampro.portprobe.bridge");
                    instruction = instruction instanceof Instruction21c i
                            ? new ImmutableInstruction21c(instruction.getOpcode(), i.getRegisterA(), ref)
                            : new ImmutableInstruction31c(instruction.getOpcode(), ((Instruction31c)instruction).getRegisterA(), ref);
                    authorities++;
                }
                code.add(instruction);
            }
            methods.add(new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),
                    m.getAnnotations(),m.getHiddenApiRestrictions(),new ImmutableMethodImplementation(impl.getRegisterCount(),code,impl.getTryBlocks(),impl.getDebugItems())));
        }
        return new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods);
    }
    public static void main(String[] args) throws Exception {
        var baseline = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.forApi(33));
        var fixture = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        List<ClassDef> all = new ArrayList<>(); int copied = 0;
        for (ClassDef cls : fixture.getClasses()) if (!selected(cls.getType())) all.add(cls);
        for (String entry : baseline.getDexEntryNames()) for (ClassDef cls : baseline.getEntry(entry).getDexFile().getClasses())
            if (selected(cls.getType())) { all.add(fixtureAuthority(cls)); copied++; }
        if (copied < 6 || authorities != 1) throw new IllegalStateException("Missing baseline handoff classes/authority: " + copied + "/" + authorities);
        DexPool.writeTo(args[2], new ImmutableDexFile(fixture.getOpcodes(), all));
        System.out.println("Transplanted " + copied + " actual handoff classes; only the fixture provider authority changes");
    }
}
