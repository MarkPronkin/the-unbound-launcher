import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.*;

/**
 * Inspect a running debug APK, or exercise the same CastUtil service toggle as Quick Access.
 *
 * Usage: java --add-modules jdk.jdi tools/WirelessMirroringControl.java PORT [inspect|cycle|unity-cycle [COUNT]]
 * Forward PORT to the app's JDWP pid with adb first. Inspection never creates a CastUtil or WFD
 * manager. Cycling changes the real service state and restores the original state on success.
 * A surviving toggle does not establish that the device can receive an actual Miracast stream.
 */
public final class WirelessMirroringControl {
    private static final String CAST_CLASS = "ai.nreal.cast.CastUtil";
    private final VirtualMachine vm;
    private final List<String> uncaught = new ArrayList<>();
    private final Map<String, ReferenceType> preparedClasses = new HashMap<>();
    private final ThreadReference main;

    private WirelessMirroringControl(VirtualMachine vm) {
        this.vm = vm;
        // ART/JDI can retain a negative classesByName lookup across a lazy Unity init.
        // Observe preparation before requesting it and use the event's actual ReferenceType.
        for (String pattern : List.of("ai.nreal.cast.*", "com.qualcomm.wfd.client.*", "dev.beampro.port.WirelessCastCompat")) {
            ClassPrepareRequest request = vm.eventRequestManager().createClassPrepareRequest();
            request.addClassFilter(pattern);
            request.setSuspendPolicy(EventRequest.SUSPEND_NONE);
            request.enable();
        }
        refreshClasses();
        main = vm.allThreads().stream().filter(t -> t.name().equals("main")).findFirst().orElseThrow();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3
                || (args.length > 1 && !Set.of("inspect", "cycle", "unity-cycle").contains(args[1]))
                || (args.length > 2 && args[1].equals("inspect"))) {
            throw new IllegalArgumentException("Usage: WirelessMirroringControl JDWP_PORT [inspect|cycle|unity-cycle [COUNT]]");
        }
        String mode = args.length > 1 ? args[1] : "inspect";
        boolean cycle = !mode.equals("inspect");
        int count = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        if (count < 1 || count > 20) throw new IllegalArgumentException("COUNT must be 1..20");
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        Map<String, Connector.Argument> options = connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1");
        options.get("port").setValue(args[0]);
        options.get("timeout").setValue("10000");
        VirtualMachine vm = connector.attach(options);
        try {
            WirelessMirroringControl control = new WirelessMirroringControl(vm);
            ExceptionRequest exceptions = vm.eventRequestManager().createExceptionRequest(null, false, true);
            exceptions.setSuspendPolicy(EventRequest.SUSPEND_NONE);
            exceptions.enable();
            System.out.println("Attached to " + vm.name() + "; mode=" + mode);
            control.snapshot("initial");
            control.reportGate();
            if (mode.equals("unity-cycle")) control.unityCycle(count);
            else if (mode.equals("cycle")) control.cycle(count);
            control.rejectUncaught();
            System.out.println(cycle ? "PASS: requested service transitions completed and process/worker survived"
                    : "Inspection complete; no casting service state was changed");
        } finally {
            try { vm.dispose(); } catch (VMDisconnectedException ignored) { }
        }
    }

    private void refreshClasses() {
        for (ReferenceType type : vm.allClasses()) preparedClasses.put(type.name(), type);
    }

    private ReferenceType loaded(String name) {
        ReferenceType known = preparedClasses.get(name);
        if (known != null) return known;
        List<ReferenceType> types = vm.classesByName(name);
        if (types.isEmpty()) return null;
        preparedClasses.put(name, types.get(0));
        return types.get(0);
    }

    private ObjectReference cast() {
        ClassType type = (ClassType) loaded(CAST_CLASS);
        return type == null ? null : (ObjectReference) type.getValue(type.fieldByName("mInstance"));
    }

    private void reportGate() throws Exception {
        onMain(thread -> {
            ClassType debug = (ClassType) loaded("ai.nreal.cast.DebugSetting");
            System.out.println("DebugSetting.isMiracastEnable=" + (debug == null ? "class not loaded"
                    : debug.invokeMethod(thread, method(debug, "isMiracastEnable", "()Z"), List.of(), 0)));
            ClassType compat = (ClassType) loaded("dev.beampro.port.WirelessCastCompat");
            if (compat != null) {
                List<Method> getters = compat.methodsByName("getUnavailableReason", "()Ljava/lang/String;");
                if (!getters.isEmpty()) {
                    System.out.println("WirelessCastCompat.getUnavailableReason=" + describe(
                            compat.invokeMethod(thread, getters.get(0), List.of(), 0)));
                }
            }
        });
        snapshotCompat();
    }

    private void snapshotCompat() {
        ReferenceType compat = loaded("dev.beampro.port.WirelessCastCompat");
        System.out.println("WirelessCastCompat=" + (compat == null ? "class not loaded" : "loaded"));
        if (compat != null) {
            for (String name : List.of("app", "unavailableReason", "loggedReason")) {
                Field item = compat.fieldByName(name);
                if (item != null) System.out.println("WirelessCastCompat." + name + "=" + describe(compat.getValue(item)));
            }
        }
    }

    private static Method method(ReferenceType type, String name, String signature) {
        List<Method> matches = type.methodsByName(name, signature);
        if (matches.size() != 1) throw new IllegalStateException("Expected one " + type.name() + "." + name + signature);
        return matches.get(0);
    }

    private static Value field(ObjectReference object, String name) {
        if (object == null) return null;
        Field field = object.referenceType().fieldByName(name);
        return field == null ? null : object.getValue(field);
    }

    private static String describe(Value value) {
        if (value == null) return "null";
        if (value instanceof StringReference) return ((StringReference) value).value();
        if (value instanceof ObjectReference) {
            ObjectReference object = (ObjectReference) value;
            return object.referenceType().name() + "#" + object.uniqueID();
        }
        return value.toString();
    }

    private void snapshot(String label) {
        ObjectReference cast = cast();
        System.out.println("--- " + label + " ---");
        System.out.println("CastUtil.class=" + (loaded(CAST_CLASS) == null ? "not loaded" : "loaded"));
        System.out.println("CastUtil=" + describe(cast));
        if (cast != null) {
            for (String name : List.of("mActivity", "mCastBridge", "mCastHandler", "mWfdManager",
                    "mServerStarted", "mEventRegistered", "mIsDiscoverable", "mIsMiraCastOn", "mCurrentBjChannel")) {
                System.out.println("CastUtil." + name + "=" + describe(field(cast, name)));
            }
            ObjectReference manager = (ObjectReference) field(cast, "mWfdManager");
            if (manager != null) {
                for (String name : List.of("mContext", "mEventHandler", "mActionListener", "whisperer", "mIsPaused")) {
                    System.out.println("WfdManager." + name + "=" + describe(field(manager, name)));
                }
            }
            ThreadReference worker = worker();
            System.out.println("castutil_worker=" + (worker == null ? "null" : describe(worker)
                    + "; name=" + worker.name() + "; status=" + worker.status()));
        }
        ReferenceType service = loaded("com.qualcomm.wfd.client.ServiceUtil");
        System.out.println("ServiceUtil=" + (service == null ? "class not loaded" : "loaded"));
        if (service != null) {
            for (String name : List.of("mServiceAlreadyBound", "uniqueInstance", "eventHandler")) {
                Field item = service.fieldByName(name);
                if (item != null) System.out.println("ServiceUtil." + name + "=" + describe(service.getValue(item)));
            }
        }
        ReferenceType unity = loaded("com.unity3d.player.UnityPlayer");
        if (unity != null && unity.fieldByName("currentActivity") != null) {
            System.out.println("UnityPlayer.currentActivity=" + describe(unity.getValue(unity.fieldByName("currentActivity"))));
        }
        snapshotCompat();
        System.out.println("main_status=" + main.status());
    }

    private ThreadReference worker() {
        ObjectReference handler = (ObjectReference) field(cast(), "mCastHandler");
        ObjectReference looper = (ObjectReference) field(handler, "mLooper");
        return (ThreadReference) field(looper, "mThread");
    }

    private void cycle(int count) throws Exception {
        ObjectReference cast = cast();
        if (cast == null) throw new IllegalStateException("CastUtil is not initialized; use unity-cycle to trigger the real lazy initializer");
        Value originalValue = field(cast, "mServerStarted");
        if (!(originalValue instanceof BooleanValue)) throw new IllegalStateException("Missing mServerStarted flag");
        boolean original = ((BooleanValue) originalValue).value();
        if (worker() == null) throw new IllegalStateException("CastUtil worker is not initialized");
        System.out.println("Cycling the real CastUtil startCastService/stopCastService path " + count + " times");
        for (int i = 1; i <= count; i++) {
            transition(!original, "cycle " + i + " first");
            transition(original, "cycle " + i + " restore");
        }
        snapshot("final (original service state restored)");
    }

    private void transition(boolean enabled, String label) throws Exception {
        String name = enabled ? "startCastService" : "stopCastService";
        System.out.println(label + ": invoking " + name + "()");
        ObjectReference cast = cast();
        onMain(thread -> cast.invokeMethod(thread, method(cast.referenceType(), name, "()V"), List.of(), 0));
        awaitTransition(enabled, name, label);
    }

    private void awaitTransition(boolean enabled, String name, String label) throws Exception {
        // The real call posts to castutil. Resume the event thread before waiting, so the
        // worker and main-thread service callbacks can run normally between observations.
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            handle(vm.eventQueue().remove(100));
            rejectUncaught();
            ThreadReference worker = worker();
            if (worker != null && worker.status() == ThreadReference.THREAD_STATUS_ZOMBIE) {
                throw new IllegalStateException("CastUtil worker died after " + name);
            }
            main.status(); // Round trip also detects a disconnected/dead process.
        }
        refreshClasses();
        ObjectReference cast = cast();
        if (cast == null) throw new IllegalStateException(name + " did not initialize CastUtil");
        if (worker() == null) throw new IllegalStateException(name + " did not initialize the CastUtil worker");
        boolean actual = ((BooleanValue) field(cast, "mServerStarted")).value();
        if (actual != enabled) throw new IllegalStateException(name + " did not set mServerStarted=" + enabled);
        if (!((BooleanValue) field(cast, "mEventRegistered")).value()
                || field(cast, "mActivity") == null || field(cast, "mCastBridge") == null) {
            throw new IllegalStateException(name + " left CastUtil initialization incomplete");
        }
        snapshot(label + "; server state verified after 3 seconds resumed");
    }

    private void unityCycle(int count) throws Exception {
        ObjectReference initial = cast();
        boolean original = initial != null && ((BooleanValue) field(initial, "mServerStarted")).value();
        if (initial == null) System.out.println("CastUtil not initialized; treating original service state as off");
        System.out.println("Cycling real Unity Toggle_WirelessCast.OnSwitchWirelessCast " + count + " times");
        for (int i = 1; i <= count; i++) {
            unityTransition(!original, "Unity cycle " + i + " first");
            unityTransition(original, "Unity cycle " + i + " restore");
        }
        reportGate();
        snapshot("final (original service state restored through Unity)");
    }

    private void unityTransition(boolean expected, String label) throws Exception {
        ClassType unity = (ClassType) loaded("com.unity3d.player.UnityPlayer");
        if (unity == null) throw new IllegalStateException("Unity is not loaded; enter AR Space first");
        Method send = method(unity, "UnitySendMessage", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        System.out.println(label + ": sending Toggle_WirelessCast.OnSwitchWirelessCast; expecting enabled=" + expected);
        onMain(thread -> unity.invokeMethod(thread, send, List.of(vm.mirrorOf("Toggle_WirelessCast"),
                vm.mirrorOf("OnSwitchWirelessCast"), vm.mirrorOf("")), 0));
        awaitTransition(expected, "Unity toggle", label);
    }

    @FunctionalInterface private interface MainAction { void run(ThreadReference thread) throws Exception; }

    private void onMain(MainAction action) throws Exception {
        ReferenceType handler = loaded("android.os.Handler");
        if (handler == null) throw new IllegalStateException("android.os.Handler not loaded");
        BreakpointRequest request = vm.eventRequestManager().createBreakpointRequest(
                method(handler, "dispatchMessage", "(Landroid/os/Message;)V").location());
        request.addThreadFilter(main);
        request.addCountFilter(1);
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        request.enable();
        try {
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (System.nanoTime() < deadline) {
                EventSet events = vm.eventQueue().remove(250);
                if (events == null) continue;
                boolean hit = false;
                try {
                    for (Event event : events) {
                        if (event instanceof BreakpointEvent && event.request() == request) {
                            hit = true;
                            request.disable();
                            action.run(((BreakpointEvent) event).thread());
                        } else observe(event);
                    }
                } finally { events.resume(); }
                rejectUncaught();
                if (hit) return;
            }
            throw new IllegalStateException("No main-thread Handler callback within 15 seconds");
        } finally { vm.eventRequestManager().deleteEventRequest(request); }
    }

    private void handle(EventSet events) {
        if (events == null) return;
        try { for (Event event : events) observe(event); }
        finally { events.resume(); }
    }

    private void observe(Event event) {
        if (event instanceof ClassPrepareEvent) {
            ReferenceType type = ((ClassPrepareEvent) event).referenceType();
            preparedClasses.put(type.name(), type);
            System.out.println("Class prepared: " + type.name());
        } else if (event instanceof ExceptionEvent) {
            ExceptionEvent failure = (ExceptionEvent) event;
            ObjectReference exception = failure.exception();
            String summary = exception.referenceType().name() + ": " + describe(field(exception, "detailMessage"))
                    + " on " + failure.thread().name() + " at " + failure.location();
            uncaught.add(summary);
            System.err.println("UNCAUGHT: " + summary);
        } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
            throw new IllegalStateException("App process exited during inspection/regression");
        }
    }

    private void rejectUncaught() {
        if (!uncaught.isEmpty()) throw new IllegalStateException("Uncaught app exception(s): " + uncaught);
    }
}
