package dev.beampro.standalone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.WindowManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;

/** One user-approved capture session. Two source regions are not two independent task displays. */
public final class CaptureService extends Service {
    private static CaptureService instance;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private FrameSink sink;
    private int screenWidth,screenHeight;
    private String first,second;
    private JSONObject split;
    private final long[] hashes=new long[2];
    private final int[] samples=new int[2], changes=new int[2];
    private boolean testing, stopped;
    private boolean secondLaunched;
    public static CaptureService get() { return instance; }
    public boolean isTesting() { return testing; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null || "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if(projection!=null) return START_NOT_STICKY;
        Results.init(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("capture","Standalone screen sharing",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CaptureService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Unbound screen sharing").setContentText("Tap to return. Stop ends capture and releases the display.")
                .setContentIntent(open).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).setOngoing(true).build();
        startForeground(41,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        try {
            Intent data=intent.getParcelableExtra("consent");
            if(data==null || intent.getIntExtra("result",0)!=-1) throw new IllegalArgumentException("Screen sharing requires fresh user consent");
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(-1,data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { if(!stopped) { Results.set("capture_stop_reason","system_or_user"); stopSelf(); } }
            },handler);
            Rect bounds=getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
            screenWidth=bounds.width(); screenHeight=bounds.height();
            sink=new FrameSink(Math.max(1,screenWidth/2),Math.max(1,screenHeight/2),handler,this::frame);
            display=projection.createVirtualDisplay("unbound-consented-capture",Math.max(1,screenWidth/2),Math.max(1,screenHeight/2),
                    getResources().getDisplayMetrics().densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,sink.reader.getSurface(),null,handler);
            if(display==null) throw new IllegalStateException("Screen capture returned no display");
            instance=this; Results.set("capture_active",true);
            // Do not leave a forgotten test capture running indefinitely.
            handler.postDelayed(this::stopSelf,120000);
        } catch(Exception error) { Results.set("capture_error",error.toString()); stopSelf(); }
        return START_NOT_STICKY;
    }
    public void beginSplit(String a,String b) {
        if(testing) throw new IllegalStateException("A split-screen test is already running");
        first=a; second=b; testing=true; secondLaunched=false;
        for(int i=0;i<2;i++) { hashes[i]=0; samples[i]=0; changes[i]=0; }
        split=new JSONObject(); Results.put(split,"source","one_user_consented_phone_display");
        Results.put(split,"independent_virtual_displays",false);
        Results.put(split,"packages",new JSONArray().put(a).put(b));
        Results.set("split_capture",split); Results.set("split_capture_two_apps_passed",false);
        handler.postDelayed(this::verifyPair,9000);
    }
    public void hostReady(MainActivity host) {
        if(!testing || secondLaunched || stopped || host.isDestroyed()) return;
        // OxygenOS Canvas can recreate the caller with a smaller fullscreen task
        // instead of reporting AOSP's multi-window mode. Preserve orchestration in
        // the foreground service across that recreation, and inspect real bounds.
        Rect actual=host.getWindowManager().getCurrentWindowMetrics().getBounds();
        boolean smaller=actual.height()<screenHeight*3/4 || actual.width()<screenWidth*3/4;
        boolean inSplit=host.isInMultiWindowMode()||smaller;
        Results.put(split,"host_bounds",actual.flattenToString());
        if(!inSplit) { handler.postDelayed(() -> hostReady(host),250); return; }
        secondLaunched=true; splitLaunch(true);
        try { host.startActivity(MainActivity.launchIntent(host,second)); }
        catch(Exception error) { Results.put(split,"second_launch_error",error.toString()); Results.set("split_capture",split); }
    }
    public void splitLaunch(boolean accepted) {
        Results.put(split,"launcher_entered_multi_window",accepted);
        if(!accepted) Results.put(split,"launch_error","Android did not enter split screen for the adjacent launch");
        Results.set("split_capture",split);
    }
    private boolean visiblePair() {
        PointerService pointer=PointerService.get();
        if(pointer==null || first==null || second==null) return false;
        Rect a=pointer.bounds(first,0), b=pointer.bounds(second,0);
        return a!=null && b!=null && !a.isEmpty() && !b.isEmpty() && !Rect.intersects(a,b);
    }
    private void verifyPair() {
        if(!testing || stopped) return;
        boolean visible=visiblePair(); Results.put(split,"two_disjoint_app_windows_visible",visible);
        if(PointerService.get()!=null) Results.put(split,"window_geometry",PointerService.get().geometry());
        if(!visible) { Results.put(split,"error","The two selected apps were not both visible in separate windows"); finishTest(); return; }
        PointerService pointer=PointerService.get(); JSONArray input=new JSONArray(); Results.put(split,"input",input);
        pointer.checkRealApp(first,0,a -> {
            if(!testing || stopped) return;
            input.put(a); pointer.checkRealApp(second,0,b -> {
                if(!testing || stopped) return;
                input.put(b); handler.postDelayed(this::finishTest,1500);
            });
        });
    }
    private void frame(Bitmap bitmap) {
        if(!testing) return;
        PointerService pointer=PointerService.get(); if(pointer==null) return;
        String[] names={first,second};
        for(int i=0;i<names.length;i++) {
            Rect app=pointer.bounds(names[i],0); if(app==null || app.isEmpty()) continue;
            Rect crop=new Rect(app.left*bitmap.getWidth()/screenWidth,app.top*bitmap.getHeight()/screenHeight,
                    app.right*bitmap.getWidth()/screenWidth,app.bottom*bitmap.getHeight()/screenHeight);
            if(!crop.intersect(0,0,bitmap.getWidth(),bitmap.getHeight()) || crop.isEmpty()) continue;
            long hash=FrameSink.cropHash(bitmap,crop);
            if(samples[i]>0 && hash!=hashes[i]) changes[i]++;
            hashes[i]=hash; samples[i]++;
            if(samples[i]==2 || samples[i]%8==0) {
                Bitmap image=Bitmap.createBitmap(bitmap,crop.left,crop.top,crop.width(),crop.height());
                try(FileOutputStream output=new FileOutputStream(new File(getFilesDir(),"split-app-"+i+".png"))) { image.compress(Bitmap.CompressFormat.PNG,100,output); }
                catch(java.io.IOException error) { android.util.Log.e("UnboundStandalone","Cannot save crop",error); }
                if(image!=bitmap) image.recycle();
            }
        }
    }
    private void finishTest() {
        if(!testing || stopped) return;
        JSONArray crops=new JSONArray();
        for(int i=0;i<2;i++) {
            JSONObject data=new JSONObject(); Results.put(data,"package",i==0?first:second);
            Results.put(data,"captured_samples",samples[i]); Results.put(data,"changed_frames",changes[i]);
            Results.put(data,"last_hash",Long.toHexString(hashes[i])); crops.put(data);
        }
        Results.put(split,"crops",crops);
        JSONArray input=split.optJSONArray("input");
        boolean passed=visiblePair() && samples[0]>1 && samples[1]>1 && changes[0]>0 && changes[1]>0
                && input!=null && input.length()==2 && input.optJSONObject(0).optBoolean("observed_response")
                && input.optJSONObject(1).optBoolean("observed_response");
        Results.put(split,"complete",true); Results.put(split,"passed",passed);
        Results.set("split_capture",split); Results.set("split_capture_two_apps_passed",passed);
        sink.save(new File(getFilesDir(),"split-source.png"));
        Results.set("capture_frames",sink.stats()); testing=false;
        PointerService pointer=PointerService.get(); if(pointer!=null) pointer.clear();
        stopSelf();
    }
    @Override public void onDestroy() {
        stopped=true; handler.removeCallbacksAndMessages(null);
        if(testing && split!=null) { Results.put(split,"interrupted",true); Results.set("split_capture",split); }
        PointerService pointer=PointerService.get(); if(pointer!=null) pointer.clear();
        if(display!=null) { display.release(); display=null; }
        if(sink!=null) { Results.set("capture_frames",sink.stats()); sink.close(); sink=null; }
        if(projection!=null) { projection.stop(); projection=null; }
        if(instance==this) instance=null; Results.set("capture_active",false);
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
