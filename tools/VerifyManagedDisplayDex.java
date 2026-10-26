import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;

/** Verify the production helper actually starts management, rather than merely packaging it. */
public final class VerifyManagedDisplayDex {
    public static void main(String[] args)throws Exception {
        var dex=DexFileFactory.loadDexFile(args[0],Opcodes.forApi(33));
        int calls=0,snapshotCalls=0,legacyEnableCalls=0;boolean watchdog=false,policy=false,reader=false;
        java.util.Set<String> guardStrings=new java.util.HashSet<>();
        java.util.Set<String> capabilityStrings=new java.util.HashSet<>();
        for(ClassDef cls:dex.getClasses()) {
            watchdog|=cls.getType().equals("Ldev/beampro/bridge/ManagedDisplay$Watchdog;");
            policy|=cls.getType().equals("Ldev/beampro/bridge/ManagedDisplayPolicy;");
            reader|=cls.getType().equals("Ldev/beampro/bridge/DisplaySnapshotReader;");
            if(cls.getType().equals("Ldev/beampro/bridge/ManagedDisplay$AndroidPlatform;")) {
                for(Method method:cls.getMethods())
                    for(Instruction ins:method.getImplementation().getInstructions())
                        if(ins instanceof ReferenceInstruction ref) {
                            if(method.getName().equals("unlockedAndCool")&&ref.getReference() instanceof StringReference value)guardStrings.add(value.getString());
                            if(method.getName().equals("<init>")&&ref.getReference() instanceof StringReference value)capabilityStrings.add(value.getString());
                            if(ref.getReference() instanceof MethodReference target) {
                                if(method.getName().equals("screens")&&target.getDefiningClass().equals("Ldev/beampro/bridge/DisplaySnapshotReader;")&&target.getName().equals("read"))snapshotCalls++;
                                if(target.getDefiningClass().equals("Landroid/hardware/display/DisplayManager;")&&target.getName().equals("getDisplays"))
                                    throw new IllegalStateException("Policy discovery still uses the callback-fed client cache");
                                if(target.getDefiningClass().equals("Landroid/content/Context;")
                                        ||target.getDefiningClass().equals("Landroid/app/KeyguardManager;")
                                        ||target.getDefiningClass().equals("Landroid/view/WindowManagerGlobal;")
                                        ||target.getDefiningClass().equals("Landroid/provider/Settings$Secure;"))
                                    throw new IllegalStateException("Display adapter still requires app-bound services: "+target);
                            }
                        }
            }
            if(cls.getType().equals("Ldev/beampro/bridge/ManagedDisplayPolicy;"))
                for(Method method:cls.getMethods())if(method.getName().equals("tick"))
                    for(Instruction ins:method.getImplementation().getInstructions())
                        if(ins instanceof ReferenceInstruction ref&&ref.getReference() instanceof MethodReference target
                                &&target.getDefiningClass().equals("Ldev/beampro/bridge/ManagedDisplayPolicy$Platform;")
                                &&target.getName().equals("enableWithoutDesktopPreference"))legacyEnableCalls++;
            if(!cls.getType().equals("Ldev/beampro/bridge/LaunchServer;"))continue;
            for(Method method:cls.getMethods())if(method.getName().equals("run"))
                for(Instruction ins:method.getImplementation().getInstructions())
                    if(ins instanceof ReferenceInstruction ref&&ref.getReference() instanceof MethodReference target
                            &&target.getDefiningClass().equals("Ldev/beampro/bridge/ManagedDisplay;")&&target.getName().equals("start"))calls++;
        }
        if(calls!=1||snapshotCalls!=1||!watchdog||!policy||!reader)throw new IllegalStateException("Managed display helper not wired: "+calls+" snapshots="+snapshotCalls);
        if(!guardStrings.containsAll(java.util.List.of("android.view.IWindowManager","isKeyguardLocked","android.os.IThermalService","getCurrentThermalStatus")))
            throw new IllegalStateException("Direct display guards are missing");
        if(legacyEnableCalls!=1||!capabilityStrings.containsAll(java.util.List.of("getConnectionPreference","enableConnectedDisplay")))
            throw new IllegalStateException("Legacy connected-display enable fallback is missing");
        System.out.println("Managed-display startup, legacy connected-display enable, direct snapshots and guards, independent watchdog and policy are wired in the production helper");
    }
}
