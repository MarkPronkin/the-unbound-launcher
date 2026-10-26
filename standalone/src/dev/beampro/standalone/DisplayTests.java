package dev.beampro.standalone;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.Presentation;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;

/** Public API launch/capture backend and concurrent two-app feasibility tests. */
public final class DisplayTests implements AutoCloseable {
    private final Activity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayList<VirtualDisplay> displays=new ArrayList<>();
    private final ArrayList<FrameSink> sinks=new ArrayList<>();
    private final ArrayList<Presentation> presentations=new ArrayList<>();
    private boolean running;
    private boolean anyPassed;
    // Private, own-content, public/own, presentation/own, public/presentation/own.
    private static final int[] FLAGS={0,8,9,10,11};
    public DisplayTests(Activity owner) { activity=owner; }
    public void run(String first,String second,Runnable done) {
        if(running) return;
        running=true; anyPassed=false; Results.set("virtual_display_cases",new JSONArray());
        Results.set("independent_virtual_apps_passed",false);
        positiveControl(() -> attempt(first,second,0,done));
    }
    private VirtualDisplay create(String name,int flags,FrameSink sink) {
        VirtualDisplay display=activity.getSystemService(DisplayManager.class).createVirtualDisplay(name,800,600,200,sink.reader.getSurface(),flags);
        if(display==null) throw new IllegalStateException("Virtual display allocation returned null");
        displays.add(display); return display;
    }
    private void positiveControl(Runnable next) {
        JSONObject result=new JSONObject(); Results.put(result,"case","own_presentation_positive_control");
        try {
            FrameSink sink=new FrameSink(800,600,handler,null); sinks.add(sink);
            VirtualDisplay display=create("unbound-own-content",10,sink);
            handler.postDelayed(() -> {
                if(!running) return;
                try {
                    Presentation presentation=new Presentation(activity,display.getDisplay()); presentations.add(presentation);
                    TextView view=new TextView(presentation.getContext()); view.setText("Unbound own-content control");
                    view.setTextColor(Color.WHITE); view.setBackgroundColor(0xff1c7d6e); presentation.setContentView(view); presentation.show();
                    Results.put(result,"shown",true);
                } catch(Exception error) { Results.put(result,"error",error.toString()); }
                handler.postDelayed(() -> {
                    Results.put(result,"capture",sink.stats());
                    Results.put(result,"passed",sink.nonblackFrames>0);
                    sink.save(new File(activity.getFilesDir(),"own-content.png"));
                    Results.addCase(result); release(); next.run();
                },1600);
            },350);
        } catch(Exception error) { Results.put(result,"error",error.toString()); Results.addCase(result); release(); next.run(); }
    }
    private void attempt(String first,String second,int index,Runnable done) {
        if(!running) return;
        if(index==FLAGS.length) { running=false; release(); done.run(); return; }
        JSONObject result=new JSONObject(); Results.put(result,"flags",FLAGS[index]); Results.put(result,"case","two_real_apps");
        JSONArray apps=new JSONArray(); Results.put(result,"apps",apps);
        ArrayList<Runnable> launches=new ArrayList<>();
        ArrayList<FrameSink> appSinks=new ArrayList<>();
        for(String name:new String[]{first,second}) {
            JSONObject app=new JSONObject(); apps.put(app); Results.put(app,"package",name);
            int appIndex=appSinks.size(); appSinks.add(null);
            try {
                Intent target=MainActivity.launchIntent(activity,name);
                FrameSink sink=new FrameSink(800,600,handler,null); sinks.add(sink);
                VirtualDisplay display=create("unbound-"+name+"-"+FLAGS[index],FLAGS[index],sink);
                appSinks.set(appIndex,sink);
                Results.put(app,"display_id",display.getDisplay().getDisplayId());
                Results.put(app,"allocated",true);
                launches.add(() -> {
                    try {
                        int id=display.getDisplay().getDisplayId();
                        boolean allowed=activity.getSystemService(ActivityManager.class).isActivityStartAllowedOnDisplay(activity,id,target);
                        Results.put(app,"activity_start_allowed",allowed);
                        // Attempt once so the actual exception is evidence; never retry on display 0.
                        activity.startActivity(target,ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle());
                        Results.put(app,"launch_requested",true);
                    } catch(Exception error) { Results.put(app,"launch_error",error.toString()); }
                });
            } catch(Exception error) { Results.put(app,"allocation_error",error.toString()); }
        }
        handler.postDelayed(() -> {
            if(!running) return;
            for(Runnable launch:launches) launch.run();
            handler.postDelayed(() -> {
                if(!running) return;
                boolean rendered=apps.length()==2 && sinks.size()==2;
                for(int i=0;i<apps.length();i++) {
                    JSONObject app=apps.optJSONObject(i);
                    FrameSink sink=appSinks.get(i);
                    if(sink!=null) Results.put(app,"capture",sink.stats());
                    PointerService pointer=PointerService.get();
                    boolean visible=pointer!=null && pointer.bounds(app.optString("package"),app.optInt("display_id",-1))!=null;
                    Results.put(app,"accessibility_target_visible",visible);
                    rendered &= app.optBoolean("launch_requested") && sink!=null && sink.nonblackFrames>1 && visible;
                }
                Results.put(result,"both_rendered",rendered);
                // Rendering is not input verification. Only mark the full result after both callbacks.
                if(rendered) {
                    PointerService pointer=PointerService.get(); JSONArray input=new JSONArray(); Results.put(result,"input",input);
                    pointer.checkRealApp(first,apps.optJSONObject(0).optInt("display_id"),a -> {
                        if(!running) return;
                        input.put(a); pointer.checkRealApp(second,apps.optJSONObject(1).optInt("display_id"),b -> {
                            if(!running) return;
                            input.put(b);
                            anyPassed |= a.optBoolean("observed_response")&&b.optBoolean("observed_response");
                            Results.set("independent_virtual_apps_passed",anyPassed);
                            Results.addCase(result); release(); attempt(first,second,index+1,done);
                        });
                    });
                } else { Results.addCase(result); release(); attempt(first,second,index+1,done); }
            },1800);
        },350);
    }
    private void release() {
        for(Presentation p:presentations) { try{p.dismiss();}catch(Exception ignored){} } presentations.clear();
        for(VirtualDisplay display:displays) display.release(); displays.clear();
        for(FrameSink sink:sinks) sink.close(); sinks.clear();
    }
    @Override public void close() { running=false; handler.removeCallbacksAndMessages(null); release(); }
}
