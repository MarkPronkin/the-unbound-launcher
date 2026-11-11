package dev.beampro.settings;

import static dev.beampro.settings.Signature.*;
import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;

/** Guided tests are reachable on the phone and export through the existing private debug provider. */
final class DisplayTestsPage extends MenuPage {
    private final DisplayTests tests;
    private final Runnable observer=this::render;
    private boolean exporting;
    private String exportStatus;
    private File export;
    DisplayTestsPage(Activity activity){super(activity);tests=DisplayTests.get(activity);}
    @Override protected void onStart(){super.onStart();getWindow().setBackgroundDrawable(new ColorDrawable(BACKGROUND));window(getWindow());tests.enter(activity,observer);render();}
    @Override protected void onStop(){tests.leave(observer);super.onStop();}
    @Override protected void onDestroy(){tests.release(activity);super.onDestroy();}
    void render(){
        int previous=scrollPosition();
        View back=back(activity,"Back",v->dismiss());back.setTag("display-tests-back");
        AdaptiveLayout.DetailPage page=new AdaptiveLayout.DetailPage(activity,back);scroll=page.scroll;
        page.root.setContentDescription("Display tests");
        add(page.intro,display(activity,"Display tests"),8,0);
        add(page.intro,text(activity,"Test the glasses output without starting AR Space. Connect one pair of glasses and keep this page open. Close this page to resume automatic AR entry.",SUPPORTING,TEXT_SECONDARY),12,0);
        page.body.addView(heading(activity,"Current output"));
        TextView output=text(activity,tests.output(),BODY,TEXT);output.setTag("display-tests-output");add(page.body,output,4,0);
        TextView status=text(activity,tests.status(),SUPPORTING,TEXT_SECONDARY);
        status.setTag("display-tests-status");status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(page.body,status,8,0);
        if(!tests.countdown().isEmpty())add(page.body,text(activity,tests.countdown(),SUPPORTING,TEXT_SECONDARY),8,0);
        if(tests.saving())add(page.body,text(activity,"Saving display snapshots…",SUPPORTING,TEXT_SECONDARY),8,0);

        page.body.addView(heading(activity,"Run a test"));
        add(page.body,text(activity,"The 3D test first checks 2D, then tries 3840 × 1080 SBS at 60 Hz. A successful timing shows a grid and one centered circle per eye for 20 seconds. Each test requests a return to 2D when finished, stopped, or when you leave this page. If recovery fails, unplug and reconnect the glasses.",SUPPORTING,TEXT_SECONDARY),4,0);
        boolean available=!tests.busy()&&!tests.saving()&&!exporting;
        button(page.body,"Check connection","display-tests-check",available,()->{export=null;exportStatus=null;tests.check();});
        button(page.body,"Test 2D · 60 Hz","display-tests-2d",available,()->{export=null;exportStatus=null;tests.start(DisplayTestRun.Kind.TWO_D);});
        button(page.body,"Test 3D · SBS 60 Hz","display-tests-sbs",available,()->{export=null;exportStatus=null;tests.start(DisplayTestRun.Kind.SBS);});
        Button stop=Signature.button(activity,tests.recovering()?"Returning to 2D…":tests.nativeWork()?"Stop and return to 2D":"Return glasses to 2D",PRIMARY);
        stop.setTag("display-tests-restore");stop.setEnabled(!exporting&&!tests.recovering()&&(tests.nativeWork()||available));stop.setOnClickListener(v->{export=null;exportStatus=null;tests.stop();});add(page.body,stop,12,0);
        if(tests.canFeedback()){
            page.body.addView(heading(activity,"How did the pattern look?"));
            button(page.body,"Pattern looked clear","display-tests-clear",!exporting,()->{export=null;exportStatus=null;tests.feedback(true);});
            button(page.body,"Broken or missing","display-tests-broken",!exporting,()->{export=null;exportStatus=null;tests.feedback(false);});
        }
        page.body.addView(heading(activity,"Save the results"));
        add(page.body,text(activity,"Test results, requested modes, observed resolutions and display/USB diagnostics stay on your phone and are included in the debug file. These tests do not take screenshots or record head movement. Some phones restrict driver information; those restrictions are recorded. Debug log collection is optional.",SUPPORTING,TEXT_SECONDARY),4,0);
        if(exportStatus!=null)add(page.body,text(activity,exportStatus,SUPPORTING,TEXT_SECONDARY),8,0);
        button(page.body,exporting?"Preparing debug file…":"Create debug file","display-tests-export",available,()->{
            exporting=true;exportStatus="Preparing the test results and display diagnostics…";render();
            DebugData data=DebugData.get(activity);
            data.files.execute(()->{
                File result=null;String error=null;
                try{result=data.export();}catch(Exception failure){error=failure.getMessage();}
                File saved=result;String problem=error;
                activity.runOnUiThread(()->{exporting=false;export=saved;exportStatus=saved==null?"Could not create the debug file: "+problem:"Debug file ready. Share it or save it to Downloads.";if(isShowing())render();});
            });
        });
        if(export!=null){
            button(page.body,"Share debug file","display-tests-share",!exporting,()->{
                try{activity.startActivity(Intent.createChooser(DebugData.share(activity,export),"Share display test results"));}
                catch(Exception failure){exportStatus="Could not share the file. Save it to Downloads instead.";render();}
            });
            button(page.body,"Save to Downloads","display-tests-save",!exporting,()->{
                exporting=true;render();
                DebugData.get(activity).files.execute(()->{
                    String message;
                    try{DebugData.get(activity).saveToDownloads(export);message="Saved to Downloads.";}catch(Exception failure){message="Could not save: "+failure.getMessage();}
                    String result=message;activity.runOnUiThread(()->{exporting=false;exportStatus=result;if(isShowing())render();});
                });
            });
        }
        setContentView(page.root);scroll.post(()->scroll.scrollTo(0,previous));
    }
    private void button(LinearLayout parent,String label,String tag,boolean enabled,Runnable click){
        Button button=Signature.button(activity,label,SECONDARY);button.setTag(tag);button.setEnabled(enabled);
        button.setOnClickListener(v->click.run());add(parent,button,12,0);
    }
}
