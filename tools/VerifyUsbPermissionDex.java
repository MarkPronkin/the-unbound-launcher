import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

/** Verify the production helper handler and deferred client, beyond class-name presence. */
public final class VerifyUsbPermissionDex {
    public static void main(String[] args)throws Exception {
        var server=DexFileFactory.loadDexFile(args[0],Opcodes.forApi(33));
        int construct=0,grant=0;boolean serverToken=false;
        for(ClassDef cls:server.getClasses())if(cls.getType().startsWith("Ldev/beampro/bridge/LaunchServer"))
            for(Method method:cls.getMethods())if(method.getImplementation()!=null)
                for(Instruction instruction:method.getImplementation().getInstructions())if(instruction instanceof ReferenceInstruction ref) {
                    if(ref.getReference() instanceof StringReference text&&text.getString().equals("dev.beampro.bridge.XREAL_USB"))serverToken=true;
                    if(ref.getReference() instanceof MethodReference call&&call.getDefiningClass().equals("Ldev/beampro/bridge/XrealUsbPermissions;")) {
                        if(call.getName().equals("<init>"))construct++;
                        if(call.getName().equals("grant"))grant++;
                    }
                }
        var client=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));
        int readback=0,callback=0,dialogs=0;boolean clientToken=false,transaction=false;
        for(ClassDef cls:client.getClasses())if(cls.getType().equals("Ldev/beampro/port/UsbPermissionCompat$Request;"))
            for(Method method:cls.getMethods())if(method.getImplementation()!=null)
                for(Instruction instruction:method.getImplementation().getInstructions()) {
                    if(instruction instanceof NarrowLiteralInstruction literal&&literal.getNarrowLiteral()==0x00FFFFF8)transaction=true;
                    if(!(instruction instanceof ReferenceInstruction ref))continue;
                    if(ref.getReference() instanceof StringReference text&&text.getString().equals("dev.beampro.bridge.XREAL_USB"))clientToken=true;
                    if(ref.getReference() instanceof MethodReference call) {
                        if(call.getDefiningClass().equals("Landroid/hardware/usb/UsbManager;")) {
                            if(call.getName().equals("hasPermission"))readback++;
                            if(call.getName().equals("requestPermission"))dialogs++;
                        }
                        if(call.getDefiningClass().equals("Landroid/app/PendingIntent;")&&call.getName().equals("send"))callback++;
                    }
                }
        if(construct!=1||grant!=1||!serverToken||readback!=2||callback!=1||dialogs!=0||!clientToken||!transaction)
            throw new IllegalStateException("Production XREAL USB flow not wired: "+construct+","+grant+","+readback+","+callback+","+dialogs);
        var container=DexFileFactory.loadDexContainer(new java.io.File(args[2]),Opcodes.forApi(33));
        int hooks=0,installed=0;
        for(String entry:container.getDexEntryNames())for(ClassDef cls:container.getEntry(entry).getDexFile().getClasses())
            for(Method method:cls.getMethods())if(method.getImplementation()!=null)
                for(Instruction instruction:method.getImplementation().getInstructions())if(instruction instanceof ReferenceInstruction ref&&ref.getReference() instanceof MethodReference call){
                    if(call.getDefiningClass().equals("Landroid/hardware/usb/UsbManager;")&&call.getName().equals("requestPermission")
                            &&!cls.getType().equals("Ldev/beampro/port/UsbPermissionCompat;"))
                        throw new IllegalStateException("Unhooked USB request: "+method);
                    if(call.getDefiningClass().equals("Ldev/beampro/port/UsbPermissionCompat;")){
                        if(call.getName().equals("install")&&cls.getType().equals("Ldev/beampro/port/PortStatus;"))installed++;
                        if(call.getName().equals("requestPermission")&&!cls.getType().startsWith("Ldev/beampro/port/UsbPermissionCompat"))hooks++;
                    }
                }
        if(hooks!=5||installed!=1)throw new IllegalStateException("Incomplete USB coverage: hooks="+hooks+" install="+installed);
        System.out.println("XREAL USB helper handler, permission readback and dialog-free SDK callback are wired");
        System.out.println("All five original SDK USB requests are intercepted, including controller retries");
    }
}
