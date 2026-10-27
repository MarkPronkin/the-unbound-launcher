import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import java.io.File;
import java.util.*;

/** Audit the shipped decision, forced/queued UI entries and original low-level OTA guards. */
public final class VerifySkipOtaDex {
    static final String MANAGER="Lai/nreal/nebula/manager/XREALManager;";
    static final String PRES="Lai/nreal/nebula/activity/splash/ota/OtaPresenterImpl;";
    static final Set<String> GUARDS=Set.of("Lai/nreal/glasses/control/OtaManager;->startOta",
        "Lai/nreal/glasses/control/OtaManager;->setGlassToBoot","Lai/nreal/glasses/control/OtaManagerWrap;->startOta",
        "Lai/nreal/glasses/control/OtaManagerWrap;->i","Lai/nreal/glasses/control/ota;->startOta");
    static void require(boolean value,String message) {if(!value)throw new IllegalStateException(message);}
    public static void main(String[] args)throws Exception {
        var container=DexFileFactory.loadDexContainer(new File(args[0]),Opcodes.forApi(33));
        int decisions=0,entries=0;Set<String> guards=new HashSet<>();
        for(String entry:container.getDexEntryNames())for(ClassDef cls:container.getEntry(entry).getDexFile().getClasses())
            for(Method m:cls.getMethods()) {
                if(m.getImplementation()==null)continue;
                List<Instruction> code=new ArrayList<>();m.getImplementation().getInstructions().forEach(code::add);
                String key=cls.getType()+"->"+m.getName();
                for(Instruction instruction:code)if(instruction instanceof ReferenceInstruction ref&&ref.getReference() instanceof MethodReference call)
                    require(!call.getDefiningClass().equals(MANAGER)||!call.getName().equals("startOta"),"A caller can still enter the manager's OTA state: "+key);
                if(key.equals(MANAGER+"->getOtaNum")) {
                    decisions++;int sku=0,identity=0,skip=0,count=0;
                    for(int i=0;i<code.size();i++) {
                        Instruction instruction=code.get(i);
                        if(instruction.getOpcode()==Opcode.CONST_4&&i>=3&&code.subList(i-3,i).stream().allMatch(x->x.getOpcode()==Opcode.NOP)) {
                            int value=((NarrowLiteralInstruction)instruction).getNarrowLiteral();
                            if(value==1)skip++;if(value==0)count++;
                        }
                        if(instruction instanceof ReferenceInstruction ref&&ref.getReference() instanceof MethodReference call) {
                            require(!call.getName().equals("getOtaNum")&&!call.getName().equals("getBoolean"),"Firmware decision still depends on native probe or saved preference");
                            if(call.getDefiningClass().equals("Lai/nreal/glasses/control/OtaManager;")&&call.getName().equals("getSku"))sku++;
                            if(call.getDefiningClass().equals("Lcom/xreal/basicfunc/BasicFuncManager;")&&call.getName().equals("setGlassesType"))identity++;
                        }
                    }
                    require(sku==1&&identity==1&&skip==1&&count==1,"Skip decision or original model/SKU initialization missing");
                }
                if(cls.getType().equals(PRES)&&(m.getName().equals("_goToOta")||m.getName().startsWith("lambda$_goToOta$"))) {
                    entries++;
                    require(code.size()==2&&m.getImplementation().getRegisterCount()==3,"Update UI entry still has work");
                    require(code.get(0).getOpcode()==Opcode.INVOKE_DIRECT&&code.get(1).getOpcode()==Opcode.RETURN_VOID,"Update UI entry does not continue to SDK preparation");
                    var call=(MethodReference)((ReferenceInstruction)code.get(0)).getReference();
                    var invoke=(Instruction35c)code.get(0);
                    require(call.getDefiningClass().equals(PRES)&&call.getName().equals("checkCloudFw")&&call.getParameterTypes().isEmpty()
                        &&call.getReturnType().equals("V")&&invoke.getRegisterCount()==1&&invoke.getRegisterC()==0,"Update continuation loses presenter or invokes wrong SDK entry");
                }
                if(GUARDS.contains(key)) {
                    guards.add(key);
                    require(code.size()==2&&code.get(0).getOpcode()==Opcode.CONST_4&&code.get(1).getOpcode()==Opcode.RETURN
                        &&((NarrowLiteralInstruction)code.get(0)).getNarrowLiteral()==-1,"Low-level firmware/boot guard was changed");
                }
            }
        require(decisions==1&&entries==2&&guards.equals(GUARDS),"Missing production skip-OTA decision, UI entries or firmware guards");
        System.out.println("Skip OTA is unconditional; normal/forced/queued update UI bypassed, model SKU and five firmware/boot guards preserved");
    }
}
