package dev.beampro.bridge;

import java.util.concurrent.atomic.AtomicBoolean;

/** A single fixed readiness ping, accessible only to the installed launcher. */
final class GlassesNetworkProbe {
    static final int TRANSACTION = 0x00FFFFF4;
    static final String TOKEN = "dev.beampro.bridge.GLASSES_PING";
    interface Runner { ShellCommand.Result run(long timeout, String... command) throws Exception; }
    private final int launcherUid;
    private final Runner runner;
    private final AtomicBoolean busy = new AtomicBoolean();

    GlassesNetworkProbe(int uid) { this(uid, ShellCommand::run); }
    GlassesNetworkProbe(int uid, Runner runner) { launcherUid = uid; this.runner = runner; }

    ShellCommand.Result ping(int caller) throws Exception {
        if (caller != launcherUid) throw new SecurityException("Glasses readiness is restricted to the installed launcher");
        if (!busy.compareAndSet(false, true)) return new ShellCommand.Result(-1, false, "A glasses ping is still finishing");
        try {
            return runner.run(1500, "/system/bin/ping", "-I", "eth0", "-c", "1", "-W", "1", "169.254.2.1");
        } finally { busy.set(false); }
    }
}
