import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import java.io.File;
import java.util.*;

/** Check that the shipped APK calls the recovery adapter on every audited entry. */
public final class VerifyConnectionStartupDex {
    public static void main(String[] args)throws Exception {
        var container=DexFileFactory.loadDexContainer(new File(args[0]),Opcodes.forApi(33));
        Map<String,Integer> calls=new HashMap<>();
        for(String entry:container.getDexEntryNames())for(ClassDef cls:container.getEntry(entry).getDexFile().getClasses()) {
            if(cls.getType().startsWith("Ldev/beampro/port/GlassesConnectionCompat"))continue;
            for(Method m:cls.getMethods())if(m.getImplementation()!=null)for(var instruction:m.getImplementation().getInstructions())
                if(instruction instanceof ReferenceInstruction ref&&ref.getReference() instanceof MethodReference call
                        &&call.getDefiningClass().equals("Ldev/beampro/port/GlassesConnectionCompat;"))calls.merge(call.getName(),1,Integer::sum);
        }
        Map<String,Integer> expected=Map.of("install",1,"begin",1,"prepare",1,"startSdk",1,"confirm",1,
            "cancel",1,"newIntent",1,"usbDisplayRemoved",1,"launch",3,"acceptSdkCallback",1);
        if(!calls.equals(expected))throw new IllegalStateException("Incomplete production connection recovery: "+calls);
        System.out.println("Connection lifecycle, USB/display recovery and stale native callback guard are wired in the production APK");
    }
}
