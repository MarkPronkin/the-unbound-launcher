package dev.beampro.port;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

/** Keep the :space process eligible to render while another phone app is in front. */
public final class ArSessionService extends Service {
    static final String TOKEN="unbound.arSession";
    private static final String CHANNEL="unbound-ar-session",STOP="unbound.stopArSession";
    private static final int NOTICE=7320;
    private long token;

    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        long requested=intent==null?0:intent.getLongExtra(TOKEN,0);
        long current=PresentationOnlyCompat.sessionToken();
        if(requested==0||requested!=current) {
            if(token==0||token!=current)stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        token=requested;
        if(STOP.equals(intent.getAction())) {
            PresentationOnlyCompat.stopSession(token);stopSelf();return START_NOT_STICKY;
        }
        android.app.Activity owner=PresentationOnlyCompat.renderingActivity();
        if(owner==null||owner.isFinishing()||owner.isDestroyed()){stopSelf();return START_NOT_STICKY;}
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"AR Space",NotificationManager.IMPORTANCE_LOW));
        Intent back=new Intent(owner.getIntent()).setComponent(owner.getComponentName())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open=PendingIntent.getActivity(this,NOTICE,back,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,NOTICE,new Intent(this,ArSessionService.class)
                .setAction(STOP).putExtra(TOKEN,token),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        boolean ready=PresentationOnlyCompat.outputReady(token);
        Notification notice=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(ready?"AR Space is running":"Starting AR Space")
                .setContentText(ready?"Tap to return to the trackpad.":"Waiting for glasses output. Tap to return.")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null,"Stop AR",stop).build()).build();
        try {
            startForeground(NOTICE,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            PresentationOnlyCompat.foreground(token,true);
            Log.i("UnboundPresentation","AR foreground service active in renderer process");
        } catch(RuntimeException failure) {
            Log.w("UnboundPresentation","Cannot start AR foreground service",failure);stopSelf();
        }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        PresentationOnlyCompat.foreground(token,false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
