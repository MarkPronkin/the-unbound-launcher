package dev.beampro.notificationchecks;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import java.lang.reflect.*;

/** Exercise the APK's notification adapter and post a real cross-app tap action. */
public final class NotificationDeviceChecks extends Instrumentation {
    private Bundle arguments;
    public static final class HostActivity extends Activity { }
    @Override public void onCreate(Bundle args) { arguments=args; start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context context=getTargetContext();
            NotificationManager manager=context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("checks","Launcher notification checks",NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent activity=PendingIntent.getActivity(context,1,new Intent(context,HostActivity.class),PendingIntent.FLAG_IMMUTABLE);
            Notification notice=new Notification.Builder(context,"checks").setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(arguments.getString("title","Unbound app notification test"))
                    .setContentText("A real app notification with a tap action")
                    .setContentIntent(activity).build();
            if ("post".equals(arguments.getString("mode"))) {
                manager.notify("unbound-check",42,notice);
                result.putString("stream","Posted app notification with activity PendingIntent\n"); finish(-1,result); return;
            }
            if ("cancel".equals(arguments.getString("mode"))) {
                manager.cancelAll(); result.putString("stream","Cancelled test notifications\n"); finish(-1,result); return;
            }
            Class<?> helperClass=Class.forName("com.xreal.evapro.NotifyHelper");
            Class<?> infoClass=Class.forName("com.xreal.evapro.NotifyInfo");
            Object helper=helperClass.getMethod("getInstance").invoke(null);
            Field contextField=helperClass.getDeclaredField("mContext");contextField.setAccessible(true);contextField.set(helper,context);
            Method getIntent=helperClass.getMethod("getIntent",infoClass);
            int checks=0;
            PendingIntent broadcast=PendingIntent.getBroadcast(context,2,new Intent("dev.beampro.CHECK"),PendingIntent.FLAG_IMMUTABLE);
            PendingIntent service=PendingIntent.getService(context,3,new Intent("dev.beampro.CHECK").setPackage(context.getPackageName()),PendingIntent.FLAG_IMMUTABLE);
            for(PendingIntent action:new PendingIntent[]{null,activity,broadcast,service}) {
                Notification notification=new Notification.Builder(context,"checks").setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Notification check").setContentText("Keep notification delivery independent of tap-action inspection")
                        .setContentIntent(action).build();
                StatusBarNotification sbn=new StatusBarNotification(context.getPackageName(),context.getPackageName(),42,"checks",
                        android.os.Process.myUid(),android.os.Process.myPid(),0,notification,android.os.Process.myUserHandle(),System.currentTimeMillis());
                Object info=infoClass.getConstructor(Context.class,StatusBarNotification.class).newInstance(context,sbn);
                if(getIntent.invoke(helper,info)!=null)throw new AssertionError("Use the renderer's native-app fallback");
                if(!"Notification check".equals(infoClass.getMethod("getTitle").invoke(info)))throw new AssertionError("Title changed");
                if(!context.getPackageName().equals(infoClass.getMethod("getPackageName").invoke(info)))throw new AssertionError("Package changed");
                Object creator=helperClass.getMethod("getCreatorPackage",infoClass).invoke(helper,info);
                if(action==null?creator!=null:!context.getPackageName().equals(creator))throw new AssertionError("Native-app tap target lost");
                checks+=4;
            }
            result.putString("stream","PASS notification adapter checks: "+checks+"\n");finish(-1,result);
        } catch(Throwable failure) {
            while(failure instanceof InvocationTargetException)failure=((InvocationTargetException)failure).getCause();
            result.putString("stream","FAIL notification adapter: "+failure+"\n");finish(1,result);
        }
    }
}
