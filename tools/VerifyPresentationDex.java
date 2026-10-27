import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import java.io.File;
import java.util.*;

/** Audit the always-on phone-owned Presentation path and Mirror routing in the helper. */
public final class VerifyPresentationDex {
    public static void main(String[] args)throws Exception {
        var apk=DexFileFactory.loadDexContainer(new File(args[0]),Opcodes.forApi(33));
        Map<String,String> expected=Map.of("startUnityActivityInternal","startUnity","moveToVirtualDisplay","prepareSwitch",
                "readyForDynamicSwitchDPImpl","dynamicReady","readyForRestartSessionImpl","restartReady","getFakeActivity","renderingActivity");
        Set<String> found=new HashSet<>();int installs=0,focus=0;boolean settingClass=false,settingCall=false;
        for(String entry:apk.getDexEntryNames())for(ClassDef cls:apk.getEntry(entry).getDexFile().getClasses()) {
            if(cls.getType().equals("Ldev/beampro/settings/ExperimentalPresentation;"))settingClass=true;
            for(Method method:cls.getMethods())if(method.getImplementation()!=null)
                for(Instruction instruction:method.getImplementation().getInstructions())if(instruction instanceof ReferenceInstruction ref) {
                    if(!(ref.getReference() instanceof MethodReference call))continue;
                    if(cls.getType().equals("Lai/nreal/activitylife/NRXRApp;")&&expected.containsKey(method.getName())
                            &&call.getDefiningClass().equals("Ldev/beampro/port/PresentationOnlyCompat;")
                            &&call.getName().equals(expected.get(method.getName())))found.add(method.getName());
                    if(cls.getType().equals("Ldev/beampro/port/PortStatus;")&&call.getDefiningClass().equals("Ldev/beampro/port/PresentationOnlyCompat;")
                            &&call.getName().equals("install"))installs++;
                    if(cls.getType().equals("Ldev/beampro/adb/LocalBridgeProvider;")&&call.getDefiningClass().equals("Ldev/beampro/settings/ExperimentalPresentation;"))settingCall=true;
                    if(cls.getType().equals("Lai/nreal/nebula/space/LaunchSpaceAcrivity;")&&method.getName().equals("onWindowFocusChanged")
                            &&call.getDefiningClass().equals("Ldev/beampro/port/PresentationOnlyCompat;")&&call.getName().equals("windowFocus"))focus++;
                }
        }
        if(!found.equals(expected.keySet())||installs!=1||focus!=1||settingClass||settingCall)
            throw new IllegalStateException("Incomplete always-on Presentation integration");
        var helper=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));int routes=0;boolean state=false,presentationOnly=false;
        for(ClassDef cls:helper.getClasses()) {
            state|=cls.getType().equals("Ldev/beampro/bridge/PresentationModeState;");
            for(Method method:cls.getMethods())if(method.getImplementation()!=null)
                for(Instruction instruction:method.getImplementation().getInstructions())if(instruction instanceof ReferenceInstruction ref
                        &&ref.getReference() instanceof MethodReference call) {
                    if(cls.getType().equals("Ldev/beampro/bridge/ManagedDisplayPolicy;")
                            &&call.getDefiningClass().equals("Ldev/beampro/bridge/ManagedDisplayPolicy$Platform;")
                            &&call.getName().equals("connectionPreference"))routes++;
                    if(call.getName().equals("presentationOnly"))presentationOnly=true;
                }
        }
        if(routes<2||state||presentationOnly)
            throw new IllegalStateException("Mirror routing or removed Desktop mode is inconsistent: "+routes+","+state+","+presentationOnly);
        System.out.println("Always-on phone-owned Presentation, no Settings toggle and Mirror route restoration are wired");
    }
}
