package dev.beampro.port;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** Checks the actual Android warning, including scroll access on small/rotated phones. */
public final class FirmwareWarningChecks extends Instrumentation {
    int checks;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle output=new Bundle();
        try {
            ConnectionPreviewActivity a=(ConnectionPreviewActivity)startActivitySync(new Intent(getTargetContext(),ConnectionPreviewActivity.class)
                    .putExtra("firmware",true).putExtra("hold",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            runOnMainSync(()->{
                require(a.firmware.getWidth()==a.getWindow().getDecorView().getWidth(),"Warning fills the phone");
                require(find(a.firmware,"Unsupported\nfirmware.")!=null,"Warning title");
                require(find(a.firmware,"Exit")!=null,"Exit is present");
                require(find(a.firmware,"xreal.com/ota  ↗")!=null,"Official updater is present");
                TextView proceed=find(a.firmware,"I know it won’t work; continue regardless");
                require(proceed!=null&&proceed.isEnabled(),"Explicit override is available after the read finishes");
                Rect visible=new Rect();
                require(proceed.getGlobalVisibleRect(visible)&&visible.height()>=32,"Override is visible without scrolling");
                require(find(a.firmware,"Exit").getGlobalVisibleRect(visible)&&visible.height()>=32,"Exit is visible without scrolling");
                FirmwarePolicy.Result pending=FirmwarePolicy.unavailable(6,"The firmware read is still finishing.");
                a.firmware.update(pending,true);
                require(!find(a.firmware,"Finishing firmware check…").isEnabled(),"A pending native read cannot overlap SDK initialization");
                a.firmware.update(pending,false);
                scroll(a.firmware).fullScroll(View.FOCUS_DOWN);
            });
            waitForIdleSync();
            runOnMainSync(()->{
                Rect rect=new Rect();
                TextView proceed=find(a.firmware,"I know it won’t work; continue regardless");
                require(proceed.getGlobalVisibleRect(rect)&&rect.height()>=32,"Override remains reachable by scrolling");
                require(find(a.firmware,"Exit").getGlobalVisibleRect(rect)&&rect.height()>=32,"Exit remains reachable by scrolling");
                proceed.performClick();require(a.overrides==1&&a.animation!=null,"Override invokes continuation once");
                int[] exits={0},overrides={0};
                FirmwareWarningView v=new FirmwareWarningView(a,pendingResult(),()->exits[0]++,()->overrides[0]++);
                v.show(a);find(v,"Exit").performClick();
                require(exits[0]==1&&overrides[0]==0,"Exit never invokes the override");
                a.finish();
            });
            output.putString("stream","Firmware warning runtime checks passed: "+checks+"\n");finish(-1,output);
        } catch(Throwable error){output.putString("stream","Firmware warning failed after "+checks+": "+error+"\n");finish(0,output);}
    }
    private FirmwarePolicy.Result pendingResult(){return FirmwarePolicy.unavailable(6,"Firmware could not be verified.");}
    private ScrollView scroll(View v){
        if(v instanceof ScrollView)return (ScrollView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ScrollView r=scroll(((ViewGroup)v).getChildAt(i));if(r!=null)return r;}
        return null;
    }
    private TextView find(View v,String value){
        if(v instanceof TextView&&value.contentEquals(((TextView)v).getText()))return (TextView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView r=find(((ViewGroup)v).getChildAt(i),value);if(r!=null)return r;}
        return null;
    }
    private void require(boolean value,String detail){checks++;if(!value)throw new AssertionError(detail);}
}
