package dev.beampro.adb;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import moe.shizuku.api.BinderContainer;
import rikka.shizuku.ShizukuProvider;

/** Minimal zero-dependency Shizuku v13 / Sui client for starting the bundled shell helper. */
public final class ShizukuClient {
    public static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    public static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";
    public static final String SERVICE_DESCRIPTOR = "moe.shizuku.server.IShizukuService";
    public static final String APPLICATION_DESCRIPTOR = "moe.shizuku.server.IShizukuApplication";
    public static final String REMOTE_PROCESS_DESCRIPTOR = "moe.shizuku.server.IRemoteProcess";
    public static final int API_VERSION = 13;

    // IShizukuService transactions
    static final int TRANSACTION_getVersion = IBinder.FIRST_CALL_TRANSACTION + 2; // 3
    static final int TRANSACTION_getUid = IBinder.FIRST_CALL_TRANSACTION + 3; // 4
    static final int TRANSACTION_checkPermission = IBinder.FIRST_CALL_TRANSACTION + 4; // 5
    static final int TRANSACTION_newProcess = IBinder.FIRST_CALL_TRANSACTION + 7; // 8
    static final int TRANSACTION_attachApplication_v11 = IBinder.FIRST_CALL_TRANSACTION + 13; // 14
    static final int TRANSACTION_requestPermission = IBinder.FIRST_CALL_TRANSACTION + 14; // 15
    static final int TRANSACTION_checkSelfPermission = IBinder.FIRST_CALL_TRANSACTION + 15; // 16
    static final int TRANSACTION_shouldShowRequestPermissionRationale = IBinder.FIRST_CALL_TRANSACTION + 16; // 17
    static final int TRANSACTION_attachApplication_v13 = IBinder.FIRST_CALL_TRANSACTION + 17; // 18

    // IShizukuApplication transactions
    static final int TRANSACTION_bindApplication = IBinder.FIRST_CALL_TRANSACTION + 1; // 2
    static final int TRANSACTION_dispatchRequestPermissionResult = IBinder.FIRST_CALL_TRANSACTION + 2; // 3

    // IRemoteProcess transactions
    static final int TRANSACTION_getOutputStream = IBinder.FIRST_CALL_TRANSACTION; // 1
    static final int TRANSACTION_getInputStream = IBinder.FIRST_CALL_TRANSACTION + 1; // 2
    static final int TRANSACTION_getErrorStream = IBinder.FIRST_CALL_TRANSACTION + 2; // 3
    static final int TRANSACTION_waitFor = IBinder.FIRST_CALL_TRANSACTION + 3; // 4
    static final int TRANSACTION_exitValue = IBinder.FIRST_CALL_TRANSACTION + 4; // 5
    static final int TRANSACTION_destroy = IBinder.FIRST_CALL_TRANSACTION + 5; // 6

    // Attach / permission bundle keys
    static final String ATTACH_API_VERSION = "shizuku:attach-api-version";
    static final String ATTACH_PACKAGE_NAME = "shizuku:attach-package-name";
    static final String ATTACH_REPLY_UID = "shizuku:attach-reply-uid";
    static final String ATTACH_REPLY_VERSION = "shizuku:attach-reply-version";
    static final String ATTACH_REPLY_PERMISSION_GRANTED = "shizuku:attach-reply-permission-granted";
    static final String ATTACH_REPLY_SHOULD_SHOW_RATIONALE = "shizuku:attach-reply-should-show-request-permission-rationale";
    static final String REQUEST_PERMISSION_REPLY_ALLOWED = "shizuku:request-permission-reply-allowed";

    private static final int SUI_BRIDGE_TRANSACTION = ('_' << 24) | ('S' << 16) | ('U' << 8) | 'I';
    private static final int SUI_ACTION_GET_BINDER = 2;

    private static final Object LOCK = new Object();
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile IBinder serviceBinder;
    private static volatile boolean attached;
    private static volatile boolean permissionGranted;
    private static volatile boolean shouldShowRationale;
    private static volatile int serverUid = -1;
    private static volatile int serverVersion = -1;
    private static volatile PermissionCallback pendingCallback;

    public interface Listener {
        void onStateChanged();
    }

    public interface PermissionCallback {
        void onResult(boolean granted);
    }

    private static final IBinder APPLICATION_BINDER = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == TRANSACTION_bindApplication) {
                data.enforceInterface(APPLICATION_DESCRIPTOR);
                Bundle bundle = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                if (bundle != null) {
                    serverUid = bundle.getInt(ATTACH_REPLY_UID, -1);
                    serverVersion = bundle.getInt(ATTACH_REPLY_VERSION, -1);
                    permissionGranted = bundle.getBoolean(ATTACH_REPLY_PERMISSION_GRANTED, false);
                    shouldShowRationale = bundle.getBoolean(ATTACH_REPLY_SHOULD_SHOW_RATIONALE, false);
                    attached = true;
                }
                notifyListeners();
                return true;
            }
            if (code == TRANSACTION_dispatchRequestPermissionResult) {
                data.enforceInterface(APPLICATION_DESCRIPTOR);
                data.readInt(); // requestCode
                Bundle bundle = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                final boolean allowed = bundle != null && bundle.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, false);
                permissionGranted = allowed;
                final PermissionCallback callback = pendingCallback;
                pendingCallback = null;
                notifyListeners();
                if (callback != null) {
                    postMain(() -> callback.onResult(allowed));
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private ShizukuClient() {}

    public static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    public static IBinder peekBinder() {
        return serviceBinder;
    }

    public static void onBinderReceived(IBinder newBinder, Context context) {
        if (newBinder == null || !newBinder.pingBinder()) return;
        String packageName = context == null ? "com.xreal.evapro.nebula" : context.getPackageName();
        synchronized (LOCK) {
            if (serviceBinder == newBinder && attached) return;
            serviceBinder = newBinder;
            attached = false;
            permissionGranted = false;
            serverUid = -1;
            try {
                newBinder.linkToDeath(() -> {
                    synchronized (LOCK) {
                        if (serviceBinder == newBinder) {
                            serviceBinder = null;
                            attached = false;
                            permissionGranted = false;
                            serverUid = -1;
                        }
                    }
                    notifyListeners();
                }, 0);
            } catch (RemoteException dead) {
                serviceBinder = null;
                return;
            }
            attachApplication(newBinder, packageName);
        }
        notifyListeners();
    }

    private static void attachApplication(IBinder binder, String packageName) {
        Bundle args = new Bundle();
        args.putInt(ATTACH_API_VERSION, API_VERSION);
        args.putString(ATTACH_PACKAGE_NAME, packageName);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeStrongBinder(APPLICATION_BINDER);
            data.writeInt(1);
            args.writeToParcel(data, 0);
            if (binder.transact(TRANSACTION_attachApplication_v13, data, reply, 0)) {
                reply.readException();
                return;
            }
        } catch (Throwable fallbackToV11) {
            // Fall back to the pre-v13 attachApplication(IShizukuApplication, String) contract.
        } finally {
            reply.recycle();
            data.recycle();
        }
        Parcel legacyData = Parcel.obtain();
        Parcel legacyReply = Parcel.obtain();
        try {
            legacyData.writeInterfaceToken(SERVICE_DESCRIPTOR);
            legacyData.writeStrongBinder(APPLICATION_BINDER);
            legacyData.writeString(packageName);
            if (binder.transact(TRANSACTION_attachApplication_v11, legacyData, legacyReply, 0)) {
                legacyReply.readException();
            }
        } catch (Throwable ignored) {
        } finally {
            legacyReply.recycle();
            legacyData.recycle();
        }
    }

    public static boolean tryInitSui(Context context) {
        IBinder current = serviceBinder;
        if (current != null && current.pingBinder()) return true;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            IBinder activity = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "activity");
            if (activity == null) return false;
            data.writeInterfaceToken("android.app.IActivityManager");
            data.writeInt(SUI_ACTION_GET_BINDER);
            if (!activity.transact(SUI_BRIDGE_TRANSACTION, data, reply, 0)) return false;
            reply.readException();
            IBinder suiBinder = reply.readStrongBinder();
            if (suiBinder != null && suiBinder.pingBinder()) {
                onBinderReceived(suiBinder, context);
                return true;
            }
        } catch (Throwable ignored) {
        } finally {
            reply.recycle();
            data.recycle();
        }
        return false;
    }

    private static void pollProviderIfAbsent(Context context) {
        if (context == null) return;
        IBinder current = serviceBinder;
        if (current != null && current.pingBinder()) return;
        try {
            Uri uri = Uri.parse("content://" + context.getPackageName() + ".shizuku");
            Bundle reply = context.getContentResolver().call(uri, ShizukuProvider.METHOD_GET_BINDER, null, null);
            if (reply != null) {
                reply.setClassLoader(BinderContainer.class.getClassLoader());
                BinderContainer container = reply.getParcelable(ShizukuProvider.EXTRA_BINDER);
                if (container != null && container.binder != null && container.binder.pingBinder()) {
                    onBinderReceived(container.binder, context);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static boolean isBinderReady(Context context) {
        IBinder current = serviceBinder;
        if (current != null && current.pingBinder()) return true;
        pollProviderIfAbsent(context);
        current = serviceBinder;
        if (current != null && current.pingBinder()) return true;
        return tryInitSui(context);
    }

    public static boolean isInstalled(Context context) {
        if (isBinderReady(context)) return true;
        if (context == null) return false;
        try {
            PackageManager pm = context.getPackageManager();
            return pm != null && pm.getPackageInfo(MANAGER_PACKAGE, 0) != null;
        } catch (Throwable absent) {
            return false;
        }
    }

    public static boolean hasPermission(Context context) {
        if (!isBinderReady(context)) return false;
        if (attached && permissionGranted) return true;
        IBinder current = serviceBinder;
        if (current == null || !current.pingBinder()) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            if (current.transact(TRANSACTION_checkSelfPermission, data, reply, 0)) {
                reply.readException();
                boolean allowed = reply.readInt() != 0;
                permissionGranted = allowed;
                return allowed;
            }
        } catch (Throwable ignored) {
        } finally {
            reply.recycle();
            data.recycle();
        }
        if (context != null) {
            try {
                if (context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                    permissionGranted = true;
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public static boolean shouldShowRequestPermissionRationale(Context context) {
        if (!isBinderReady(context)) return false;
        if (attached) return shouldShowRationale;
        IBinder current = serviceBinder;
        if (current == null || !current.pingBinder()) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            if (current.transact(TRANSACTION_shouldShowRequestPermissionRationale, data, reply, 0)) {
                reply.readException();
                shouldShowRationale = reply.readInt() != 0;
                return shouldShowRationale;
            }
        } catch (Throwable ignored) {
        } finally {
            reply.recycle();
            data.recycle();
        }
        return false;
    }

    public static int getUid(Context context) throws RemoteException {
        if (!isBinderReady(context)) throw new RemoteException("Shizuku service is not running");
        if (attached && (serverUid == 0 || serverUid == 2000)) return serverUid;
        IBinder current = serviceBinder;
        if (current == null || !current.pingBinder()) throw new RemoteException("Shizuku binder is dead");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            if (!current.transact(TRANSACTION_getUid, data, reply, 0)) {
                throw new RemoteException("Shizuku getUid transaction failed");
            }
            reply.readException();
            int uid = reply.readInt();
            serverUid = uid;
            return uid;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static void requestPermission(Context context, int requestCode, PermissionCallback callback) {
        if (!isBinderReady(context)) {
            if (callback != null) postMain(() -> callback.onResult(false));
            return;
        }
        if (hasPermission(context)) {
            if (callback != null) postMain(() -> callback.onResult(true));
            return;
        }
        IBinder current = serviceBinder;
        if (current == null || !current.pingBinder()) {
            if (callback != null) postMain(() -> callback.onResult(false));
            return;
        }
        pendingCallback = callback;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeInt(requestCode);
            current.transact(TRANSACTION_requestPermission, data, reply, 0);
            reply.readException();
        } catch (Throwable error) {
            pendingCallback = null;
            if (callback != null) postMain(() -> callback.onResult(false));
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static boolean openManager(Context context) {
        if (context == null) return false;
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(MANAGER_PACKAGE);
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    public static String exec(Context context, String command) throws Exception {
        if (!isBinderReady(context)) {
            throw new IOException("Shizuku service is not running. Start Shizuku and try again.");
        }
        if (!hasPermission(context)) {
            throw new SecurityException("Shizuku permission is not granted.");
        }
        int uid = getUid(context);
        if (uid != 2000 && uid != 0) {
            throw new SecurityException("Shizuku did not provide Android shell or root access (uid=" + uid + ")");
        }
        IBinder current = serviceBinder;
        if (current == null || !current.pingBinder()) {
            throw new IOException("Shizuku connection closed before starting command");
        }
        IBinder remoteProcess = newRemoteProcess(current, new String[]{"/system/bin/sh", "-c", "(" + command + ") 2>&1"});
        ScheduledFuture<?> timeout = WATCHDOG.schedule(() -> destroyProcess(remoteProcess), 20, TimeUnit.SECONDS);
        ParcelFileDescriptor stdin = null;
        ParcelFileDescriptor stdout = null;
        ParcelFileDescriptor stderr = null;
        try {
            stdin = getProcessStream(remoteProcess, TRANSACTION_getOutputStream);
            if (stdin != null) {
                try { stdin.close(); } catch (IOException ignored) {}
                stdin = null;
            }
            stdout = getProcessStream(remoteProcess, TRANSACTION_getInputStream);
            stderr = getProcessStream(remoteProcess, TRANSACTION_getErrorStream);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (stdout != null) {
                try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(stdout)) {
                    stdout = null;
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (out.size() + count > 32768) throw new IOException("Unexpected helper output");
                        out.write(buffer, 0, count);
                    }
                }
            }
            if (stderr != null) {
                try (InputStream err = new ParcelFileDescriptor.AutoCloseInputStream(stderr)) {
                    stderr = null;
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = err.read(buffer)) != -1) {
                        if (out.size() + count <= 32768) out.write(buffer, 0, count);
                    }
                }
            }
            waitForProcess(remoteProcess);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            timeout.cancel(false);
            if (stdin != null) try { stdin.close(); } catch (IOException ignored) {}
            if (stdout != null) try { stdout.close(); } catch (IOException ignored) {}
            if (stderr != null) try { stderr.close(); } catch (IOException ignored) {}
        }
    }

    private static IBinder newRemoteProcess(IBinder service, String[] cmd) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeStringArray(cmd);
            data.writeStringArray(null);
            data.writeString(null);
            if (!service.transact(TRANSACTION_newProcess, data, reply, 0)) {
                throw new RemoteException("Shizuku newProcess transaction failed");
            }
            reply.readException();
            IBinder proc = reply.readStrongBinder();
            if (proc == null) throw new RemoteException("Shizuku returned a null process binder");
            return proc;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static ParcelFileDescriptor getProcessStream(IBinder process, int transaction) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR);
            if (!process.transact(transaction, data, reply, 0)) return null;
            reply.readException();
            return reply.readInt() != 0 ? ParcelFileDescriptor.CREATOR.createFromParcel(reply) : null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static int waitForProcess(IBinder process) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR);
            if (!process.transact(TRANSACTION_waitFor, data, reply, 0)) return -1;
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void destroyProcess(IBinder process) {
        if (process == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR);
            process.transact(TRANSACTION_destroy, data, reply, 0);
        } catch (Throwable ignored) {
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void notifyListeners() {
        postMain(() -> {
            for (Listener listener : LISTENERS) {
                try { listener.onStateChanged(); } catch (Throwable ignored) {}
            }
        });
    }

    private static void postMain(Runnable action) {
        Looper main = Looper.getMainLooper();
        if (main == null || Thread.currentThread() == main.getThread()) {
            action.run();
        } else {
            new Handler(main).post(action);
        }
    }

    static void resetForTests() {
        synchronized (LOCK) {
            serviceBinder = null;
            attached = false;
            permissionGranted = false;
            shouldShowRationale = false;
            serverUid = -1;
            serverVersion = -1;
            pendingCallback = null;
            LISTENERS.clear();
        }
    }
}
