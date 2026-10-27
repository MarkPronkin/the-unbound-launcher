import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import java.io.File;
import java.util.*;

/** Verify the fixed background-entry endpoint and on-phone trigger are actually packaged. */
public final class VerifyAutoArEntryDex {
    public static void main(String[] args)throws Exception {
        var server=DexFileFactory.loadDexFile(args[0],Opcodes.forApi(33));int requests=0,constructs=0;
        Set<String> serverStrings=new HashSet<>();
        for(ClassDef cls:server.getClasses())for(Method m:cls.getMethods())if(m.getImplementation()!=null)
            for(Instruction instruction:m.getImplementation().getInstructions())if(instruction instanceof ReferenceInstruction ref){
                if(ref.getReference() instanceof StringReference s&&cls.getType().startsWith("Ldev/beampro/bridge/AutoArEntry"))serverStrings.add(s.getString());
                if(ref.getReference() instanceof MethodReference call&&cls.getType().startsWith("Ldev/beampro/bridge/LaunchServer")
                        &&call.getDefiningClass().equals("Ldev/beampro/bridge/AutoArEntry;")){
                    if(call.getName().equals("request"))requests++;if(call.getName().equals("<init>"))constructs++;
                }
            }
        if(requests!=1||constructs!=1||!serverStrings.containsAll(List.of("isKeyguardLocked",
            "dev.markpronkin.unbound/ai.nreal.nebula.activity.splash.SplashActivity","android.hardware.usb.action.USB_DEVICE_ATTACHED")))
            throw new IllegalStateException("Automatic AR shell endpoint is not wired");
        var apk=DexFileFactory.loadDexContainer(new File(args[1]),Opcodes.forApi(33));int helperReady=0,transacts=0;boolean token=false,setup=false;
        for(String name:apk.getDexEntryNames())for(ClassDef cls:apk.getEntry(name).getDexFile().getClasses())for(Method m:cls.getMethods())if(m.getImplementation()!=null)
            for(Instruction instruction:m.getImplementation().getInstructions())if(instruction instanceof ReferenceInstruction ref){
                boolean receiver=cls.getType().startsWith("Ldev/beampro/adb/AutoGlassesReceiver");
                if(receiver&&ref.getReference() instanceof StringReference s){token|=s.getString().equals("dev.beampro.bridge.AUTO_AR");setup|=s.getString().equals("setup-complete");}
                if(ref.getReference() instanceof MethodReference call){
                    if(!receiver&&call.getDefiningClass().equals("Ldev/beampro/adb/AutoGlassesReceiver;")&&call.getName().equals("helperReady"))helperReady++;
                    if(receiver&&call.getDefiningClass().equals("Landroid/os/IBinder;")&&call.getName().equals("transact"))transacts++;
                }
            }
        if(helperReady!=2||transacts!=1||!token||!setup)throw new IllegalStateException("Automatic USB trigger is incomplete: "+helperReady+","+transacts);
        System.out.println("Private USB trigger, setup gate, helper-ready recovery and fixed-component shell auto-entry are wired");
    }
}
