package dev.beampro.settings.preview;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import dev.beampro.settings.AppMenuActivity;
import dev.beampro.settings.AppMenuStore;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/** Real Android storage and disconnected Activity tests. No glasses/Unity/helper. */
public final class AppMenuChecks extends Instrumentation {
    int passed;
    static final String TUTORIAL="TutorialWidgetContent", TV="LiveWindowWidgetContent", PHOTO="PhotoWidgetContent";
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            File file=new File(getTargetContext().getExternalFilesDir(null),"AppDetails/EPHInfoConfig/LayoutConfig.json");
            new AppMenuStore(getTargetContext());
            file.getParentFile().mkdirs();
            byte[] backup=file.exists()?Files.readAllBytes(file.toPath()):null;
            try {
                Files.write(file.toPath(),("{\""+TUTORIAL+"\":5,\""+PHOTO+"\":0,\""+TV+"\":9,\"missing.package\":2147483647}").getBytes(StandardCharsets.UTF_8));
                AppMenuStore store=new AppMenuStore(getTargetContext());
                require(store.order(Arrays.asList(TUTORIAL,TV,PHOTO)).equals(Arrays.asList(PHOTO,TUTORIAL,TV)),"imports actual Unity layout");
                store.save(Arrays.asList(TV,PHOTO),Arrays.asList(TUTORIAL));
                JSONObject saved=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
                require(saved.getInt(TV)==0 && saved.getInt(PHOTO)==1,"compact visible positions");
                require(saved.getInt(TUTORIAL)==Integer.MAX_VALUE,"hidden sentinel in shared layout");
                require(saved.getInt("missing.package")==Integer.MAX_VALUE,"unavailable hidden app retained");
                AppMenuStore reopened=new AppMenuStore(getTargetContext());
                require(reopened.isHidden(TUTORIAL),"hidden survives fresh store");
                require(reopened.order(Arrays.asList(TUTORIAL,TV,PHOTO)).equals(Arrays.asList(TV,PHOTO,TUTORIAL)),"order survives fresh store");
                Files.write(file.toPath(),"{\"changed.in.glasses\":0}".getBytes(StandardCharsets.UTF_8));
                boolean rejected=false;
                try { reopened.save(Arrays.asList(PHOTO,TV),Arrays.asList(TUTORIAL)); } catch(java.io.IOException expected){rejected=true;}
                require(rejected,"concurrent AR changes rejected");
                require(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8).contains("changed.in.glasses"),"concurrent file preserved");
                Files.write(file.toPath(),"invalid json".getBytes(StandardCharsets.UTF_8)); rejected=false;
                try { new AppMenuStore(getTargetContext()); } catch(java.io.IOException expected){rejected=true;}
                require(rejected,"corrupt file rejected");
                require(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8).equals("invalid json"),"corrupt file preserved");
                Files.write(file.toPath(),"{}".getBytes(StandardCharsets.UTF_8));
                AppMenuActivity activity=open();
                awaitCatalog(activity);
                final AppMenuActivity initial=activity;
                runOnMainSync(() -> {
                    require(find(initial,"App menu")!=null,"editor opens disconnected");
                    require(!((Button)findDescription(initial,"Save app menu")).isEnabled(),"opening does not change layout");
                    ((EditText)findDescription(initial,"Search apps or package names")).setText(TUTORIAL);
                    ListView list=findList(initial.getWindow().getDecorView());
                    require(list.getAdapter().getCount()==1,"package search finds one app");
                    View row=list.getAdapter().getView(0,null,list);
                    require(SettingsUiChecks.findText(row,"Tutorial")!=null,"search result uses readable label");
                    try { invoke(initial,"move",new Class[]{String.class,int.class},TUTORIAL,2); } catch(Exception e){throw new RuntimeException(e);}
                    require(((Button)findDescription(initial,"Save app menu")).isEnabled(),"reorder creates draft");
                    ((Button)findDescription(initial,"Save app menu")).performClick();
                });
                saved=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
                require(saved.getInt(TUTORIAL)==2,"filtered move saves global position");
                runOnMainSync(activity::finish); activity=open();awaitCatalog(activity);
                final AppMenuActivity restored=activity;
                runOnMainSync(() -> {
                    EditText search=(EditText)findDescription(restored,"Search apps or package names"); search.setText("no-such-application-123");
                    require(findList(restored.getWindow().getDecorView()).getAdapter().getCount()==0,"empty search result");
                    search.setText("tutorialwidgetcontent");
                    require(findList(restored.getWindow().getDecorView()).getAdapter().getCount()==1,"case insensitive package search");
                    try {
                        invoke(restored,"checkpoint",new Class[]{});
                        java.lang.reflect.Field visible=AppMenuActivity.class.getDeclaredField("visible"),hidden=AppMenuActivity.class.getDeclaredField("hidden");visible.setAccessible(true);hidden.setAccessible(true);
                        ((java.util.List)visible.get(restored)).remove(TUTORIAL); ((java.util.List)hidden.get(restored)).add(TUTORIAL);
                        invoke(restored,"refresh",new Class[]{});
                    } catch(Exception e){throw new RuntimeException(e);}
                    ((Button)findDescription(restored,"Save app menu")).performClick();
                });
                require(new AppMenuStore(getTargetContext()).isHidden(TUTORIAL),"hidden draft saves to native layout");
                runOnMainSync(restored::finish);activity=open();awaitCatalog(activity); final AppMenuActivity hiddenActivity=activity;
                runOnMainSync(() -> {
                    findDescription(hiddenActivity,"Show hidden apps").performClick();
                    ListView list=findList(hiddenActivity.getWindow().getDecorView());require(list.getAdapter().getCount()==1,"Hidden tab after Activity restart");
                    View row=list.getAdapter().getView(0,null,list);require(SettingsUiChecks.findText(row,"Tutorial")!=null,"hidden app remains findable");
                    ((EditText)findDescription(hiddenActivity,"Search apps or package names")).setText("photo");require(list.getAdapter().getCount()==0,"hidden tab filters search");
                    ((EditText)findDescription(hiddenActivity,"Search apps or package names")).setText("");
                });
                runOnMainSync(hiddenActivity::finish);
            } finally { if(backup==null)file.delete();else Files.write(file.toPath(),backup); }
            result.putString("stream","App menu runtime checks passed: "+passed+"\n");finish(-1,result);
        } catch(Throwable error) { result.putString("stream","App menu check failed after "+passed+": "+error+"\n");finish(0,result); }
    }
    private AppMenuActivity open(){return (AppMenuActivity)startActivitySync(new Intent(getTargetContext(),AppMenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    private void awaitCatalog(AppMenuActivity activity)throws Exception {
        java.lang.reflect.Field loading=AppMenuActivity.class.getDeclaredField("loading");loading.setAccessible(true);
        for(int i=0;i<100;i++){waitForIdleSync();boolean[] busy={true};runOnMainSync(()->{try{busy[0]=loading.getBoolean(activity);}catch(Exception e){throw new RuntimeException(e);}});if(!busy[0])return;Thread.sleep(50);}
        throw new AssertionError("catalog loading timeout");
    }
    static Object invoke(Object owner,String name,Class[] types,Object...args)throws Exception{java.lang.reflect.Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,args);}
    void require(boolean condition,String message){if(!condition)throw new AssertionError(message);passed++;}
    static TextView find(AppMenuActivity activity,String text){return SettingsUiChecks.findText(activity.getWindow().getDecorView(),text);}
    static View findDescription(AppMenuActivity activity,String text){return findDescription(activity.getWindow().getDecorView(),text);}
    static View findDescription(View root,String text){if(root.getContentDescription()!=null && text.contentEquals(root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=findDescription(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    static ListView findList(View root){if(root instanceof ListView)return (ListView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){ListView found=findList(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
}
