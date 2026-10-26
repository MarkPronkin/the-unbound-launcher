package dev.beampro.port;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;

/** Adapts launcher history to the original Unity panel's TaskInfo JNI contract. */
public final class RecentAppsCompat {
    private static final String TOKEN = "dev.beampro.bridge.RECENTS";
    private static Context application;

    public static void install(Context context) { application = context.getApplicationContext(); }

    private static IBinder bridge(Context context) {
        Bundle result = context.getContentResolver().call(
                Uri.parse("content://dev.beampro.portprobe.bridge"), "get", null, null);
        return result == null ? null : result.getBinder("binder");
    }

    public static Object[] getRecentTasks(Context context) {
        ArrayList<Object> tasks = new ArrayList<>();
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        Class<?> type;
        try { type = Class.forName("com.xreal.evapro.TaskInfo"); }
        catch (ClassNotFoundException error) { throw new IllegalStateException(error); }
        try {
            IBinder remote = bridge(context);
            if (remote == null || !remote.isBinderAlive()) return (Object[]) Array.newInstance(type, 0);
            data.writeInterfaceToken(TOKEN);
            if (!remote.transact(0x00FFFFFA, data, reply, 0)) return (Object[]) Array.newInstance(type, 0);
            reply.readException();
            int count = reply.readInt();
            if (count < 0 || count > 32) throw new IllegalStateException("Invalid recent-app count");
            PackageManager packages = context.getPackageManager();
            Field taskId = type.getDeclaredField("taskId");
            taskId.setAccessible(true);
            for (int i = 0; i < count; i++) {
                int id = reply.readInt();
                String name = reply.readString();
                long time = reply.readLong();
                try {
                    Intent launch = packages.getLaunchIntentForPackage(name);
                    if (launch == null) continue; // Removed/disabled apps never leave stale cards.
                    ApplicationInfo info = packages.getApplicationInfo(name, 0);
                    Object task = type.getConstructor().newInstance();
                    taskId.setInt(task, id);
                    type.getField("lastActiveTime").setLong(task, time);
                    type.getMethod("setPackageName", String.class).invoke(task, name);
                    type.getMethod("setAppName", String.class).invoke(task, info.loadLabel(packages).toString());
                    type.getMethod("setIntent", Intent.class).invoke(task, launch);
                    Drawable icon = info.loadIcon(packages);
                    Bitmap bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                    icon.setBounds(0, 0, 128, 128);
                    icon.draw(new Canvas(bitmap));
                    type.getMethod("setIcon", Bitmap.class).invoke(task, bitmap);
                    Bitmap preview = preview(remote, id, bitmap);
                    type.getMethod("setSnapshot", Bitmap.class).invoke(task, preview);
                    tasks.add(task);
                } catch (PackageManager.NameNotFoundException removed) {
                    // An app can disappear between listing and metadata lookup.
                } catch (Exception error) { Log.w("PortRecents", "Cannot load recent app " + name, error); }
            }
            Log.i("PortRecents", "Loaded " + tasks.size() + " launcher recent apps");
        } catch (Exception error) { Log.w("PortRecents", "Cannot read launcher history", error); }
        finally { data.recycle(); reply.recycle(); }
        return tasks.toArray((Object[]) Array.newInstance(type, tasks.size()));
    }

    private static Bitmap preview(IBinder remote, int id, Bitmap icon) {
        Bitmap image = null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOKEN);
            data.writeInt(id);
            if (remote.transact(0x00FFFFF9, data, reply, 0)) {
                reply.readException();
                byte[] bytes = reply.createByteArray();
                if (bytes != null && bytes.length <= 262144) image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
        } catch (Exception error) { Log.w("PortRecents", "Cannot load app preview", error); }
        finally { data.recycle(); reply.recycle(); }
        if (image == null) {
            image = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.drawColor(0xFF202124);
            canvas.drawBitmap(icon, null, new android.graphics.Rect(124, 54, 196, 126), null);
        }
        // Unity's existing thumbnail path imports Bitmap.getHardwareBuffer().
        Bitmap hardware = image.copy(Bitmap.Config.HARDWARE, false);
        image.recycle();
        return hardware;
    }

    public static void removeRecentTask(int id) {
        if (application == null || id >= 0) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            IBinder remote = bridge(application);
            if (remote == null || !remote.isBinderAlive()) return;
            data.writeInterfaceToken(TOKEN);
            data.writeInt(id);
            if (remote.transact(0x00FFFFFB, data, reply, 0)) reply.readException();
        } catch (Exception error) { Log.w("PortRecents", "Cannot dismiss recent app", error); }
        finally { data.recycle(); reply.recycle(); }
    }
}
