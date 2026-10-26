import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.*;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.builder.*;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import java.util.*;

/** Reversible early startup guards and deferred overlay-dependent application initialization. */
public final class PatchPermissionDex {
    static final String SETUP="Ldev/beampro/settings/PermissionSetupActivity;";
    static final String APP="Lai/nreal/nebula/NebulaApplication;";
    static final Set<String> ACTIVITIES=Set.of("Lai/nreal/nebula/activity/splash/SplashActivity;",
        "Lai/nreal/nebula/activity/setting/SettingActivity;");
    public static void main(String[] args) throws Exception {
        boolean restore=args[0].equals("restore");
        if (!restore && !args[0].equals("patch")) throw new IllegalArgumentException("patch|restore INPUT OUTPUT");
        var dex=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));
        List<ClassDef> classes=new ArrayList<>(); int guards=0, initializers=0;
        for (ClassDef cls:dex.getClasses()) {
            if (!cls.getType().equals(APP) && !ACTIVITIES.contains(cls.getType())) {classes.add(cls);continue;}
            List<Method> methods=new ArrayList<>();
            for (Method method:cls.getMethods()) {
                if (!method.getName().equals("onCreate")) {methods.add(method);continue;}
                var code=new MutableMethodImplementation(method.getImplementation());
                int registers=code.getRegisterCount();
                if(cls.getType().equals(APP)) {
                    for(int i=0;i<code.getInstructions().size();i++) {
                        var instruction=code.getInstructions().get(i);
                        if (!(instruction instanceof ReferenceInstruction) || !(((ReferenceInstruction)instruction).getReference() instanceof MethodReference)) continue;
                        var ref=(MethodReference)((ReferenceInstruction)instruction).getReference();
                        if (!(ref.getDefiningClass().equals(restore?SETUP:APP) && ref.getName().equals(restore?"initialize":"mainProcessInit"))) continue;
                        var call=(Instruction35c)instruction;
                        var target=restore ? new ImmutableMethodReference(APP,"mainProcessInit",List.of(),"V")
                            : new ImmutableMethodReference(SETUP,"initialize",List.of("Landroid/app/Application;"),"V");
                        code.replaceInstruction(i,new BuilderInstruction35c(restore?Opcode.INVOKE_DIRECT:Opcode.INVOKE_STATIC,
                            1,call.getRegisterC(),0,0,0,0,target)); initializers++;
                    }
                } else {
                    if (!method.getParameterTypes().equals(List.of("Landroid/os/Bundle;"))) throw new IllegalStateException("Unexpected onCreate");
                    int originalRegisters=registers-(restore?3:0), saved=originalRegisters;
                    if(restore) {
                        int at=-1;
                        for(int i=0;i<code.getInstructions().size();i++) {
                            var instruction=code.getInstructions().get(i);
                            if(instruction instanceof ReferenceInstruction && ((ReferenceInstruction)instruction).getReference() instanceof MethodReference) {
                                var ref=(MethodReference)((ReferenceInstruction)instruction).getReference();
                                if(ref.getDefiningClass().equals(SETUP) && ref.getName().equals("guard")) at=i;
                            }
                        }
                        if(at<0 || code.getInstructions().get(at+1).getOpcode()!=Opcode.MOVE_RESULT
                                || code.getInstructions().get(at+2).getOpcode()!=Opcode.IF_EQZ
                                || code.getInstructions().get(at+3).getOpcode()!=Opcode.RETURN_VOID) throw new IllegalStateException("Guard missing");
                        for(int i=0;i<4;i++)code.removeInstruction(at);
                        for(int i=0;i<3;i++)code.removeInstruction(0);
                        registers=originalRegisters;
                    } else {
                        int at=-1;
                        for(int i=0;i<code.getInstructions().size();i++) {
                            var instruction=code.getInstructions().get(i);
                            if(instruction.getOpcode()==Opcode.INVOKE_SUPER && instruction instanceof ReferenceInstruction) {
                                var ref=(MethodReference)((ReferenceInstruction)instruction).getReference();
                                if(ref.getName().equals("onCreate")) {at=i+1;break;}
                            }
                        }
                        if(at<0)throw new IllegalStateException("No superclass onCreate");
                        Label proceed=code.newLabelForIndex(at);
                        var target=new ImmutableMethodReference(SETUP,"guard",List.of("Landroid/app/Activity;"),"Z");
                        code.addInstruction(at,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,saved,1,target));
                        code.addInstruction(at+1,new BuilderInstruction11x(Opcode.MOVE_RESULT,saved+1));
                        code.addInstruction(at+2,new BuilderInstruction21t(Opcode.IF_EQZ,saved+1,proceed));
                        code.addInstruction(at+3,new BuilderInstruction10x(Opcode.RETURN_VOID));
                        code.addInstruction(0,new BuilderInstruction32x(Opcode.MOVE_OBJECT_16,originalRegisters-1,originalRegisters+2));
                        code.addInstruction(0,new BuilderInstruction32x(Opcode.MOVE_OBJECT_16,originalRegisters-2,originalRegisters+1));
                        code.addInstruction(0,new BuilderInstruction32x(Opcode.MOVE_OBJECT_16,saved,originalRegisters+1));
                        registers=originalRegisters+3;
                    }
                    guards++;
                }
                methods.add(new ImmutableMethod(method.getDefiningClass(),method.getName(),method.getParameters(),method.getReturnType(),
                    method.getAccessFlags(),method.getAnnotations(),method.getHiddenApiRestrictions(),
                    new ImmutableMethodImplementation(registers,code.getInstructions(),code.getTryBlocks(),code.getDebugItems())));
            }
            classes.add(new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods));
        }
        if(guards!=2 || initializers!=1)throw new IllegalStateException("Incomplete startup patch");
        DexFileFactory.writeDexFile(args[2],new ImmutableDexFile(dex.getOpcodes(),classes));
        System.out.println("Permission startup: "+guards+" Activity guards; "+initializers+" deferred initializer");
    }
}
