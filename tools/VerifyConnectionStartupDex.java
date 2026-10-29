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
        Map<String,Integer> pingCalls=new HashMap<>();
        int ethernetCalls=0,networkCalls=0;
        for(String entry:container.getDexEntryNames())for(ClassDef cls:container.getEntry(entry).getDexFile().getClasses()) {
            if(cls.getType().equals("Ldev/beampro/port/PingCallbackCompat$Pending;"))
                for(Method m:cls.getMethods())if(m.getImplementation()!=null)for(var instruction:m.getImplementation().getInstructions())
                    if(instruction instanceof ReferenceInstruction ref&&ref.getReference() instanceof MethodReference call
                            &&call.getDefiningClass().equals("Ldev/beampro/port/GlassesNetworkCompat;")) {
                        if(!call.getName().equals("deliver")||!call.getParameterTypes().equals(List.of("Ljava/lang/reflect/Method;","Ljava/lang/Object;","Z")))
                            throw new IllegalStateException("Unexpected glasses network adapter call");
                        networkCalls++;
                    }
            if(cls.getType().startsWith("Ldev/beampro/port/GlassesConnectionCompat"))continue;
            if(cls.getType().startsWith("Ldev/beampro/port/PingCallbackCompat"))continue;
            if(cls.getType().startsWith("Ldev/beampro/port/EthernetReadinessCompat"))continue;
            for(Method m:cls.getMethods())if(m.getImplementation()!=null)for(var instruction:m.getImplementation().getInstructions())
                if(instruction instanceof ReferenceInstruction ref&&ref.getReference() instanceof MethodReference call) {
                    if(call.getDefiningClass().equals("Ldev/beampro/port/GlassesConnectionCompat;"))calls.merge(call.getName(),1,Integer::sum);
                    if(call.getDefiningClass().equals("Ldev/beampro/port/PingCallbackCompat;"))pingCalls.merge(call.getName(),1,Integer::sum);
                    if(call.getDefiningClass().equals("Ldev/beampro/port/EthernetReadinessCompat;")&&call.getName().equals("ping")) {
                        if(!cls.getType().equals("Lcom/xreal/glasses/servicesdk/helper/PingGSeriesHelper$2;")||!m.getName().equals("run"))
                            throw new IllegalStateException("Unexpected Ethernet readiness caller");
                        ethernetCalls++;
                    }
                }
        }
        Map<String,Integer> expected=Map.ofEntries(Map.entry("install",1),Map.entry("begin",1),Map.entry("prepare",1),
            Map.entry("startSdk",1),Map.entry("confirm",1),Map.entry("cancel",1),Map.entry("newIntent",1),
            Map.entry("usbDisplayRemoved",1),Map.entry("launch",3),Map.entry("acceptSdkCallback",1),Map.entry("status",1));
        if(!calls.equals(expected))throw new IllegalStateException("Incomplete production connection recovery: "+calls);
        if(!pingCalls.equals(Map.of("wrap",1,"released",1)))throw new IllegalStateException("Incomplete SDK ping cancellation guard: "+pingCalls);
        if(ethernetCalls!=1)throw new IllegalStateException("Missing SDK Ethernet readiness delegation");
        if(networkCalls!=1)throw new IllegalStateException("Missing scoped native startup network selection");
        System.out.println("Connection lifecycle, USB/display recovery and stale native callback guard are wired in the production APK");
    }
}
