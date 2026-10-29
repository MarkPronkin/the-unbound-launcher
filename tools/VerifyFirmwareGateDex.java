import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.*;
import java.io.File;
import java.util.*;

/** Audit the packaged gate, rather than relying on the current source tree. */
public final class VerifyFirmwareGateDex {
    static final String CONNECTION="Ldev/beampro/port/GlassesConnectionCompat;";
    static final String SESSION="Ldev/beampro/port/GlassesConnectionCompat$Session;";
    public static void main(String[] args)throws Exception {
        var container=DexFileFactory.loadDexContainer(new File(args[0]),Opcodes.forApi(33));
        Set<String> classes=new HashSet<>(),writers=new HashSet<>();
        boolean gate=false,reader=false,view=false,approved=false;
        for(String entry:container.getDexEntryNames())for(ClassDef cls:container.getEntry(entry).getDexFile().getClasses()) {
            classes.add(cls.getType());
            for(Method m:cls.getMethods())if(m.getImplementation()!=null) {
                int index=0,check=-1,sku=-1;boolean readsApproval=false;
                for(var instruction:m.getImplementation().getInstructions()) {
                    if(instruction instanceof ReferenceInstruction ref) {
                        var r=ref.getReference();
                        if(r instanceof FieldReference f&&f.getDefiningClass().equals(SESSION)&&f.getName().equals("firmwareAccepted")) {
                            if(instruction.getOpcode().name().startsWith("IPUT"))writers.add(m.getName());
                            if(instruction.getOpcode().name().startsWith("IGET"))readsApproval=true;
                        }
                        if(r instanceof MethodReference call&&call.getDefiningClass().equals(SESSION)&&call.getName().equals("checkFirmware"))check=index;
                        if(r instanceof StringReference s) {
                            if(s.getString().equals("getSku"))sku=index;
                            if(cls.getType().equals(SESSION)&&s.getString().equals("readFWVersion"))reader=true;
                            if(cls.getType().equals("Ldev/beampro/port/FirmwareWarningView;")&&s.getString().equals("I know it won’t work; continue regardless"))view=true;
                        }
                        if(r instanceof FieldReference f&&f.getDefiningClass().equals("Ldev/beampro/port/FirmwarePolicy$Result;")&&f.getName().equals("supported"))approved=true;
                    }
                    index++;
                }
                if(cls.getType().equals(CONNECTION)&&m.getName().equals("startSdk"))gate=readsApproval&&check>=0&&sku>check;
            }
        }
        if(!classes.containsAll(Set.of("Ldev/beampro/port/FirmwarePolicy;","Ldev/beampro/port/FirmwareWarningView;",SESSION))
                ||!gate||!reader||!view||!approved||!writers.equals(Set.of("resumeAfterFirmware","startSdk")))
            throw new IllegalStateException("Incomplete firmware gate: gate="+gate+", reader="+reader+", view="+view+", approved="+approved+", writers="+writers);
        System.out.println("Packaged firmware gate precedes SKU/SDK initialization; exact-release policy, explicit override and stop screen are present");
    }
}
