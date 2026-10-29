import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import java.io.File;
import java.util.*;

/** Verify that the signed application waits for actual controller/renderer readiness. */
public final class VerifyConnectionHandoffDex {
    static final String PORT = "Ldev/beampro/port/";
    static boolean calls(Method method, String owner, String name) {
        if (method.getImplementation() != null) for (var instruction : method.getImplementation().getInstructions())
            if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference call
                    && call.getDefiningClass().equals(PORT + owner + ";") && call.getName().equals(name)) return true;
        return false;
    }
    public static void main(String[] args) throws Exception {
        var container = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.forApi(33));
        Set<String> wired = new HashSet<>();
        for (String entry : container.getDexEntryNames()) for (ClassDef cls : container.getEntry(entry).getDexFile().getClasses())
            for (Method method : cls.getMethods()) {
                String owner = cls.getType();
                if (owner.equals(PORT + "PortStatus;") && method.getName().equals("install") && calls(method, "ConnectionHandoff", "install")) wired.add("lifecycle");
                if (owner.equals(PORT + "GlassesConnectionCompat$Session;") && method.getName().equals("result")) {
                    if (calls(method, "ConnectionAnimation", "connected")) throw new IllegalStateException("SDK success must not finish the connection animation");
                    if (calls(method, "ConnectionHandoff", "start")) wired.add("readiness-before-completion");
                }
                if (owner.equals(PORT + "ConnectionHandoff;") && method.getName().equals("start") && calls(method, "ConnectionAnimation", "coverLaunch")) wired.add("continuous-cover");
                if (owner.startsWith(PORT + "ConnectionHandoff$") && method.getName().equals("onActivityPostCreated")) wired.add("controller-before-first-draw");
                if (owner.equals(PORT + "ConnectionHandoff$Controller;") && method.getName().equals("run")) {
                    if (calls(method, "PhonePointerCompat", "readyFrame")) throw new IllegalStateException("Optional native pointer state cannot gate startup");
                    if (calls(method, "ConnectionHandoff", "controllerView") && calls(method, "ConnectionHandoff", "glassesView")) wired.add("phone-and-glasses-gates");
                    if (calls(method, "ConnectionHandoff$DrawnView", "ready")) wired.add("completed-frame-gates");
                }
                if (owner.equals(PORT + "ConnectionHandoff$DrawnView;") && method.getName().equals("<init>") && method.getImplementation() != null)
                    for (var instruction : method.getImplementation().getInstructions()) if (instruction instanceof ReferenceInstruction ref
                            && ref.getReference() instanceof MethodReference call && call.getDefiningClass().equals("Landroid/view/ViewTreeObserver;")
                            && call.getName().equals("registerFrameCommitCallback")) wired.add("frame-commit");
                if (owner.startsWith(PORT + "ConnectionHandoff$") && calls(method, "ConnectionAnimation", "connected")) wired.add("ready-morph");
                if (owner.startsWith(PORT + "ConnectionHandoff$") && calls(method, "ConnectionAnimation", "reveal")) wired.add("ready-fade");
            }
        Set<String> expected = Set.of("lifecycle", "readiness-before-completion", "continuous-cover", "controller-before-first-draw",
                "completed-frame-gates", "phone-and-glasses-gates", "ready-morph", "ready-fade", "frame-commit");
        if (!wired.equals(expected)) throw new IllegalStateException("Missing startup readiness wiring: " + wired);
        System.out.println("Continuous cover, controller/glasses frame commits and ready-only morph/fade are wired; optional pointer state cannot gate startup");
    }
}
