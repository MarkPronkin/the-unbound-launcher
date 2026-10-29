import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue;
import java.util.*;

/** Check the shipped endpoints, including collisions hidden by developer-only feature gates. */
public final class VerifyPhoneScreenDex {
    static int transaction(ClassDef cls) {
        for (Field field : cls.getStaticFields())
            if (field.getName().equals("TRANSACTION") && field.getInitialValue() instanceof IntEncodedValue value)
                return value.getValue();
        return -1;
    }

    static ClassDef find(DexFile dex, String type) {
        for (ClassDef cls : dex.getClasses()) if (cls.getType().equals(type)) return cls;
        throw new IllegalStateException("Missing phone-screen endpoint: " + type);
    }

    static boolean wired(Method method, int code, String owner, String name) {
        return wired(method, code, owner, name, "dev.beampro.bridge.PHONE_SCREEN");
    }

    static boolean wired(Method method, int code, String owner, String name, String descriptor) {
        if (method.getImplementation() == null) return false;
        boolean number = false, token = false, call = false;
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof NarrowLiteralInstruction literal && literal.getNarrowLiteral() == code) number = true;
            if (instruction instanceof ReferenceInstruction ref) {
                if (ref.getReference() instanceof StringReference text && text.getString().equals(descriptor)) token = true;
                if (ref.getReference() instanceof MethodReference target && target.getDefiningClass().equals(owner)
                        && target.getName().equals(name)) call = true;
            }
        }
        return number && token && call;
    }

    public static void main(String[] args) throws Exception {
        var server = DexFileFactory.loadDexFile(args[0], Opcodes.forApi(33));
        var client = DexFileFactory.loadDexFile(args[1], Opcodes.forApi(33));
        Map<Integer, String> codes = new HashMap<>();
        for (ClassDef cls : server.getClasses()) {
            int code = transaction(cls);
            if (code < 0) continue;
            String previous = codes.putIfAbsent(code, cls.getType());
            if (previous != null)
                throw new IllegalStateException("Binder transaction collision at 0x" + Integer.toHexString(code)
                        + ": " + previous + " and " + cls.getType());
        }
        int code = transaction(find(server, "Ldev/beampro/bridge/PhoneScreenPower;"));
        if (code < 0 || code != transaction(find(client, "Ldev/beampro/port/PhoneScreenCompat;")))
            throw new IllegalStateException("Phone-screen client and helper transactions differ");
        // These older endpoints have inline dispatch codes in LaunchServer.
        if (code >= 0x00FFFFF9 && code <= 0x00FFFFFE)
            throw new IllegalStateException("Phone-screen transaction collides with recents or control");
        int handlers = 0, requests = 0;
        for (ClassDef cls : server.getClasses()) if (cls.getType().startsWith("Ldev/beampro/bridge/LaunchServer"))
            for (Method method : cls.getMethods()) if (method.getName().equals("onTransact")
                    && wired(method, code, "Ldev/beampro/bridge/PhoneScreenPower;", "request")) handlers++;
        for (Method method : find(client, "Ldev/beampro/port/PhoneScreenCompat$Session;").getMethods())
            if (method.getName().equals("request") && wired(method, code, "Landroid/os/IBinder;", "transact")) requests++;
        if (handlers != 1 || requests != 1)
            throw new IllegalStateException("Phone-screen Binder routing is incomplete: " + handlers + "," + requests);
        int suspends = 0, restores = 0;
        for (ClassDef cls : client.getClasses()) if (cls.getType().startsWith("Ldev/beampro/port/PhoneScreenCompat"))
            for (Method method : cls.getMethods()) if (method.getImplementation() != null)
                for (Instruction instruction : method.getImplementation().getInstructions())
                    if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference call
                            && call.getDefiningClass().equals("Ldev/beampro/port/PhonePointerCompat;")) {
                        if (call.getName().equals("suspend")) suspends++;
                        if (call.getName().equals("restore")) restores++;
                    }
        // D8 can duplicate finally blocks for normal, caught and uncaught exits.
        if (suspends < 2 || restores < 1)
            throw new IllegalStateException("Phone-screen pointer suspension, renewal and restoration are not wired: " + suspends + "," + restores);
        int gestureCode = transaction(find(server, "Ldev/beampro/bridge/TrackpadGestures;"));
        if (gestureCode < 0 || gestureCode != transaction(find(client, "Ldev/beampro/port/TrackpadGestureCompat;")))
            throw new IllegalStateException("Trackpad gesture client and helper transactions differ");
        int gestureHandlers = 0, gestureRequests = 0, gestureInstalls = 0;
        for (ClassDef cls : server.getClasses()) if (cls.getType().startsWith("Ldev/beampro/bridge/LaunchServer"))
            for (Method method : cls.getMethods()) if (method.getName().equals("onTransact")
                    && wired(method, gestureCode, "Ldev/beampro/bridge/TrackpadGestures;", "request",
                             "dev.beampro.bridge.TRACKPAD_GESTURES")) gestureHandlers++;
        for (ClassDef cls : client.getClasses()) for (Method method : cls.getMethods()) {
            if (cls.getType().startsWith("Ldev/beampro/port/TrackpadGestureCompat$Session;")
                    && wired(method, gestureCode, "Landroid/os/IBinder;", "transact",
                             "dev.beampro.bridge.TRACKPAD_GESTURES")) gestureRequests++;
            if (cls.getType().equals("Ldev/beampro/port/PortStatus;") && method.getImplementation() != null)
                for (Instruction instruction : method.getImplementation().getInstructions())
                    if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference call
                            && call.getDefiningClass().equals("Ldev/beampro/port/TrackpadGestureCompat;")
                            && call.getName().equals("install")) gestureInstalls++;
        }
        if (gestureHandlers != 1 || gestureRequests != 1 || gestureInstalls != 1)
            throw new IllegalStateException("Trackpad gesture lifecycle/Binder routing is incomplete");
        System.out.println("Phone-screen client/helper routing matches; all helper transaction IDs are distinct");
        System.out.println("Motion pointer suspension, renewal and restoration are wired to the screen-power session");
        System.out.println("Trackpad gesture lifecycle and scoped client/helper transactions are wired");
    }
}
