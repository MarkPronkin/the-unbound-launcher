package dev.beampro.bridge;

import android.content.pm.PackageManager;
import dev.beampro.compat.GlassesIdentity;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single fixed readiness ping, accessible only to the installed launcher. */
final class GlassesNetworkProbe {
    static final int TRANSACTION = 0x00FFFFF4;
    static final String TOKEN = "dev.beampro.bridge.GLASSES_PING";
    interface Runner { ShellCommand.Result run(long timeout, String... command) throws Exception; }
    interface Recovery { String enable(String name, int vendor, int product, int deviceId) throws Exception; }
    private final int launcherUid;
    private final Runner runner;
    private final Recovery recovery;
    private final AtomicBoolean busy = new AtomicBoolean();
    private String recoveryDevice;

    GlassesNetworkProbe(PackageManager packages, int uid) {
        this(uid,ShellCommand::run,new GlassesEthernet(packages,uid)::enableIfDisabled);
    }
    GlassesNetworkProbe(int uid, Runner runner) { this(uid,runner,null); }
    GlassesNetworkProbe(int uid, Runner runner, Recovery recovery) {
        launcherUid=uid;this.runner=runner;this.recovery=recovery;
    }

    ShellCommand.Result ping(int caller) throws Exception {
        return run(caller,null,0,0,0);
    }

    ShellCommand.Result ping(int caller, String name, int vendor, int product, int deviceId) throws Exception {
        if (name==null || name.length()>256 || !GlassesIdentity.usb(vendor,product))
            throw new SecurityException("Ethernet setup requires identified XREAL glasses");
        return run(caller,name,vendor,product,deviceId);
    }

    private ShellCommand.Result run(int caller, String name, int vendor, int product, int deviceId) throws Exception {
        if (caller != launcherUid) throw new SecurityException("Glasses readiness is restricted to the installed launcher");
        if (!busy.compareAndSet(false, true)) return new ShellCommand.Result(-1, false, "A glasses ping is still finishing");
        try {
            ShellCommand.Result result=runner.run(1500, "/system/bin/ping", "-I", "eth0", "-c", "1", "-W", "1", "169.254.2.1");
            if (result.exitCode!=0 && !result.timedOut && name!=null && recovery!=null) {
                String key=name+":"+vendor+":"+product+":"+deviceId;
                if (!key.equals(recoveryDevice)) {
                    recoveryDevice=key; // One attempt per attachment, including denied/unsupported calls.
                    String note;
                    try { note=recovery.enable(name,vendor,product,deviceId); }
                    catch (Exception error) { note="Ethernet recovery failed: "+error; }
                    // A successful enable is not a successful ping. Let the SDK poll again.
                    result=new ShellCommand.Result(result.exitCode,result.timedOut,note+"\n"+result.output);
                }
            }
            return result;
        } finally { busy.set(false); }
    }
}
