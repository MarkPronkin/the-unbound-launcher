package dev.beampro.adb;

import android.content.Context;
import android.os.Binder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Short-lived Shizuku UserService. It can start only this APK's bundled helper. */
public final class ShizukuStarterService extends Binder {
    static final String TOKEN = "dev.beampro.adb.SHIZUKU_STARTER";
    static final int START = 1, DESTROY = 16777115;
    private final Context context;

    public ShizukuStarterService(Context context) { this.context = context; }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == DESTROY) { System.exit(0); return true; }
        if (code != START) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(TOKEN);
        if (Binder.getCallingUid() != context.getApplicationInfo().uid)
            throw new SecurityException("Only this launcher may start its helper");
        int uid = Process.myUid();
        if (uid != 0 && uid != 2000)
            throw new SecurityException("Shizuku must run as root or Android shell");
        if (data.dataAvail() != 0) throw new IllegalArgumentException("Unexpected startup arguments");
        java.lang.Process process = null;
        try {
            // The original helper requires UID 2000, including its provider's publish check.
            // A root-backed Shizuku therefore drops to shell for this fixed command only.
            // Check the actual child UID before extracting or launching anything.
            String command = "[ \"$(id -u)\" = 2000 ] || { echo UNBOUND_SHELL_IDENTITY_REQUIRED; exit 1; }; "
                    + HelperStarter.command(context);
            ProcessBuilder builder = uid == 0
                    ? new ProcessBuilder(suExecutable(), "2000", "-c", command)
                    : new ProcessBuilder("/system/bin/sh", "-c", command);
            android.util.Log.i("UnboundShizuku", "Starting bundled helper: Shizuku UID=" + uid + ", helper UID=2000");
            process = builder.redirectErrorStream(true).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) throw new java.io.IOException("Helper startup timed out");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 32768) throw new java.io.IOException("Unexpected helper output");
                    output.write(buffer, 0, count);
                }
            }
            String result = new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            if (process.exitValue() != 0 || !result.contains("UNBOUND_HELPER_STARTED"))
                throw new java.io.IOException("Bundled helper could not start: " + result);
            reply.writeNoException();
        } catch (Exception error) {
            reply.writeException(new IllegalStateException(error.getMessage(), error));
        } finally { if (process != null) process.destroy(); }
        return true;
    }
    private static String suExecutable() {
        for (String path : new String[]{"/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"})
            if (new java.io.File(path).canExecute()) return path;
        return "su";
    }
}
