import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import java.util.*;

/** Ensure the production capture path uses the capability adapter, not old wrappers. */
public final class VerifyRecentPreviewDex {
    public static void main(String[] args)throws Exception {
        var dex=DexFileFactory.loadDexFile(args[0],Opcodes.forApi(33));
        String adapter="Ldev/beampro/bridge/HeadlessScreenCapture;";
        int captures=0,reads=0;boolean present=false,service=false,dynamicArgs=false;
        for(ClassDef cls:dex.getClasses()) {
            boolean preview=cls.getType().equals("Ldev/beampro/bridge/RecentAppPreviews;");
            boolean capture=cls.getType().equals(adapter);present|=capture;
            if(!preview&&!capture)continue;
            for(Method method:cls.getMethods()) {
                if(method.getImplementation()==null)continue;
                for(Instruction ins:method.getImplementation().getInstructions())if(ins instanceof ReferenceInstruction ref) {
                    if(ref.getReference() instanceof StringReference text) {
                        if(text.getString().contains("WindowManagerGlobal")||text.getString().equals("android.window.ScreenCapture$CaptureArgs$Builder"))
                            throw new IllegalStateException("Preview still uses old app-bound capture path");
                        if(capture&&text.getString().equals("android.os.ServiceManager"))service=true;
                    }
                    if(ref.getReference() instanceof MethodReference call) {
                        if(preview&&call.getDefiningClass().equals(adapter)) {
                            if(call.getName().equals("capture"))captures++;
                            if(call.getName().equals("readVirtualDisplay"))reads++;
                        }
                        if(capture&&call.getDefiningClass().equals("Ljava/lang/reflect/Method;")&&call.getName().equals("getParameterTypes"))dynamicArgs=true;
                        if(call.getDefiningClass().equals("Landroid/hardware/display/DisplayManager;")
                                ||call.getDefiningClass().equals("Landroid/view/WindowManagerGlobal;")
                                ||call.getDefiningClass().equals("Landroid/content/Context;")&&call.getName().equals("getSystemService"))
                            throw new IllegalStateException("Preview still depends on app-bound service/cache: "+call);
                    }
                }
            }
        }
        if(!present||!service||!dynamicArgs||captures!=1||reads!=2)
            throw new IllegalStateException("Headless preview path not wired: captures="+captures+" reads="+reads);
        System.out.println("Recent previews use Binder capture API types, direct display reads and ownership revalidation");
    }
}
