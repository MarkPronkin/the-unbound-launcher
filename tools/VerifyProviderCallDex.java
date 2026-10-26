import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

/** Android 11 must load both the helper and its provider adapter without Android 12 types. */
public final class VerifyProviderCallDex {
    public static void main(String[] args) throws Exception {
        var dex=DexFileFactory.loadDexFile(args[0],Opcodes.forApi(33));
        int attribution=0,publish=0;boolean adapter=false;
        for(ClassDef cls:dex.getClasses()) {
            boolean launcher=cls.getType().equals("Ldev/beampro/bridge/LaunchServer;");
            boolean compat=cls.getType().equals("Ldev/beampro/bridge/ProviderCallCompat;");
            adapter|=compat;
            if(!launcher&&!compat)continue;
            for(Method method:cls.getMethods()) {
                if(method.getImplementation()==null)continue;
                for(Instruction instruction:method.getImplementation().getInstructions()) {
                    if(!(instruction instanceof ReferenceInstruction ref))continue;
                    Reference value=ref.getReference();
                    String owner=value instanceof MethodReference m?m.getDefiningClass():
                            value instanceof TypeReference t?t.getType():
                            value instanceof FieldReference f?f.getDefiningClass():"";
                    if(owner.startsWith("Landroid/content/AttributionSource"))
                        throw new IllegalStateException("Helper directly links Android 12-only attribution: "+value);
                    if(launcher&&value instanceof MethodReference m
                            &&m.getDefiningClass().equals("Ldev/beampro/bridge/ProviderCallCompat;")) {
                        if(m.getName().equals("shellAttribution"))attribution++;
                        if(m.getName().equals("publish"))publish++;
                    }
                }
            }
        }
        if(!adapter||attribution!=2||publish!=1)throw new IllegalStateException("Provider compatibility adapter not wired");
        System.out.println("Provider publication and shell attribution use the Android 11-compatible adapter");
    }
}
