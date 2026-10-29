package dev.beampro.bridge;

import dev.beampro.compat.GlassesIdentity;
import android.content.pm.PackageManager;
import android.os.IBinder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** App-only, fixed-component automatic entry after a real XREAL USB attachment. */
final class AutoArEntry {
    static final int TRANSACTION = 0x00FFFFF7;
    static final String TOKEN = "dev.beampro.bridge.AUTO_AR";
    interface Platform {
        int installedUid() throws Exception;
        boolean unlocked() throws Exception;
        boolean grant(Key device) throws Exception;
        void launch() throws Exception;
        void later(Runnable runnable, long delay);
        void log(String message);
    }
    static final class Key {
        final String name;
        final int vendor, product, id;
        Key(String name, int vendor, int product, int id) { this.name=name;this.vendor=vendor;this.product=product;this.id=id; }
        boolean same(Key key) { return key!=null && name.equals(key.name) && vendor==key.vendor && product==key.product && id==key.id; }
    }
    private final Platform platform;
    private final int uid;
    private Pending pending;
    private Key launched;

    AutoArEntry(Platform platform, int uid) { this.platform=platform;this.uid=uid; }
    AutoArEntry(PackageManager packages, int uid, XrealUsbPermissions usb) {
        this(new Platform() {
            final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
            public int installedUid() throws Exception { return packages.getApplicationInfo(XrealUsbPermissions.PACKAGE,0).uid; }
            public boolean unlocked() throws Exception {
                IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"window");
                Object service=Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
                return !(Boolean)Class.forName("android.view.IWindowManager").getMethod("isKeyguardLocked").invoke(service);
            }
            public boolean grant(Key key) throws Exception { return usb.grant(uid,key.name,key.vendor,key.product,key.id); }
            public void launch() throws Exception {
                ShellCommand.Result result=ShellCommand.run(10000,launchCommand(uid));
                if(!result.succeeded())throw new IllegalStateException("Automatic AR entry failed: "+result.output);
            }
            public void later(Runnable runnable,long delay) { worker.schedule(runnable,delay,TimeUnit.MILLISECONDS); }
            public void log(String message) { System.out.println("BEAM_AUTO_AR "+message); }
        },uid);
    }
    static String[] launchCommand(int uid) {
        // DeX may be the focused display when USB attaches. Connection setup
        // belongs on the phone; the SDK launches its glasses renderer separately.
        return new String[]{"/system/bin/am","start","-W","--user",Integer.toString(uid/100000),
                "--display","0","--windowingMode","1",
                "-n","dev.markpronkin.unbound/ai.nreal.nebula.activity.splash.SplashActivity",
                "-a","android.hardware.usb.action.USB_DEVICE_ATTACHED"};
    }
    synchronized void request(int caller, Key key, boolean attached) throws Exception {
        if(caller!=uid || platform.installedUid()!=uid)throw new SecurityException("Automatic entry is restricted to the installed launcher");
        if(key.name==null || !GlassesIdentity.usb(key.vendor,key.product))throw new SecurityException("Automatic entry requires XREAL USB hardware");
        if(!attached) {
            if(pending!=null && pending.key.same(key))pending=null;
            if(key.same(launched))launched=null;
            return;
        }
        if(key.same(launched) || (pending!=null && pending.key.same(key)))return;
        pending=new Pending(key);
        platform.log("queued device="+key.name);
        platform.later(pending,750);
    }
    private final class Pending implements Runnable {
        final Key key;
        Pending(Key key) { this.key=key; }
        boolean current() { synchronized(AutoArEntry.this) { return pending==this; } }
        public void run() {
            if(!current())return;
            try {
                if(platform.installedUid()!=uid)throw new SecurityException("Launcher installation changed");
                if(!platform.unlocked()) { platform.later(this,1000);return; }
                // Re-enumerate and validate in the existing isolated USB worker just
                // before launch. Never decode UsbDevice in the system-context helper.
                if(!platform.grant(key)) { synchronized(AutoArEntry.this){if(pending==this)pending=null;}return; }
                if(!current())return;
                if(!platform.unlocked()) { platform.later(this,1000);return; }
                platform.launch();
                synchronized(AutoArEntry.this) { if(pending==this) { launched=key;pending=null; } }
                platform.log("started device="+key.name);
            } catch(Exception error) {
                platform.log("failed "+error);
                synchronized(AutoArEntry.this) { if(pending==this)pending=null; }
            }
        }
    }
}
