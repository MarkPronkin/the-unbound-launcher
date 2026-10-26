package dev.beampro.standalone;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;

/** One installable ordinary-app experiment. No privileged permission or companion is requested. */
public final class MainActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayList<String> packages=new ArrayList<>();
    private Spinner first,second;
    private TextView state;
    private Button target;
    private DisplayTests tests;
    private int taps;
    private boolean busy;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); Results.init(this); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(28,30,28,30);
        body.setBackgroundColor(Color.BLACK);
        TextView title=new TextView(this); title.setText("Unbound Standalone"); title.setTextSize(26); body.addView(title);
        TextView description=new TextView(this);
        description.setText("Standalone runtime experiment. Choose two apps, enable pointer access, then test app windows. Screen sharing is only needed for the split-screen capture test. This build does not yet render AR Space."); body.addView(description);
        button(body,"Enable pointer access",() -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        button(body,"Allow service notifications",() -> {
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},5);
        });
        Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        ArrayList<ResolveInfo> apps=new ArrayList<>(getPackageManager().queryIntentActivities(query,0));
        apps.sort(Comparator.comparing(info -> info.loadLabel(getPackageManager()).toString(),String.CASE_INSENSITIVE_ORDER));
        ArrayList<String> labels=new ArrayList<>();
        for(ResolveInfo app:apps) {
            String name=app.activityInfo.packageName;
            if(name.equals(getPackageName()) || packages.contains(name)) continue;
            packages.add(name); labels.add(app.loadLabel(getPackageManager()).toString());
        }
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels);
        first=new Spinner(this); first.setAdapter(adapter); body.addView(first);
        second=new Spinner(this); second.setAdapter(adapter); body.addView(second);
        select(first,"com.finalwire.aida64"); select(second,"com.android.chrome");
        target=button(body,"Pointer test target · 0 taps",() -> { taps++; target.setText("Pointer test target · "+taps+" taps"); });
        button(body,"Test pointer permission",this::testPointer);
        button(body,"Test two independent app windows",this::testVirtualDisplays);
        button(body,"Start screen sharing",() -> startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),41));
        button(body,"Test two apps in split screen",this::testSplit);
        button(body,"Stop screen sharing",() -> stopService(new Intent(this,CaptureService.class)));
        button(body,"Copy test report",() -> getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Unbound standalone results",Results.text())));
        state=new TextView(this); state.setTextSize(13); body.addView(state);
        ScrollView scroll=new ScrollView(this); scroll.addView(body); setContentView(scroll); tests=new DisplayTests(this);
    }
    private void select(Spinner spinner,String name) { int index=packages.indexOf(name); if(index>=0) spinner.setSelection(index); }
    private Button button(LinearLayout parent,String label,Runnable action) {
        Button button=new Button(this); button.setText(label); button.setAllCaps(false); button.setOnClickListener(view -> action.run()); parent.addView(button); return button;
    }
    private boolean ready() {
        if(busy || CaptureService.get()!=null && CaptureService.get().isTesting()) { toast("Wait for the current test to finish."); return false; }
        if(PointerService.get()==null) { toast("Enable pointer access in Accessibility settings first."); return false; }
        if(packages.size()<2 || first.getSelectedItemPosition()==second.getSelectedItemPosition()) { toast("Choose two different installed apps."); return false; }
        return true;
    }
    private void testPointer() {
        if(PointerService.get()==null) { toast("Enable pointer access first."); return; }
        Rect bounds=new Rect(); target.getGlobalVisibleRect(bounds);
        if(bounds.isEmpty()) { toast("Scroll until the pointer test target is visible."); return; }
        int before=taps;
        PointerService.get().selfCheck(bounds,completed -> handler.postDelayed(() -> {
            JSONObject result=new JSONObject(); Results.put(result,"gesture_completed",completed);
            Results.put(result,"actual_click_received",taps==before+1); Results.set("pointer_self_check",result);
            PointerService.get().clear(); refresh();
        },250));
    }
    private String selected(Spinner spinner) { return packages.get(spinner.getSelectedItemPosition()); }
    private void testVirtualDisplays() {
        if(!ready()) return;
        busy=true; String a=selected(first), b=selected(second); PointerService.get().allow(a,b);
        tests.run(a,b,() -> { busy=false; if(PointerService.get()!=null) PointerService.get().clear(); refresh(); });
        refresh();
    }
    private void testSplit() {
        if(!ready()) return;
        CaptureService capture=CaptureService.get();
        if(capture==null) { toast("Start screen sharing and approve sharing the entire screen first."); return; }
        String a=selected(first),b=selected(second); PointerService.get().allow(a,b); capture.beginSplit(a,b);
        try { startActivity(launchIntent(this,a).addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)); }
        catch(Exception error) { Results.set("split_launch_error",error.toString()); stopService(new Intent(this,CaptureService.class)); return; }
        capture.hostReady(this);
    }
    public static Intent launchIntent(Context context,String name) {
        Intent launch=name.equals("com.android.chrome")?new Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com")).setPackage(name)
                :context.getPackageManager().getLaunchIntentForPackage(name);
        if(launch==null || launch.resolveActivity(context.getPackageManager())==null) throw new IllegalArgumentException("No launcher for "+name);
        return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=41) return;
        if(result!=RESULT_OK || data==null) { Results.set("capture_consent","denied"); refresh(); return; }
        Results.set("capture_consent","approved");
        startForegroundService(new Intent(this,CaptureService.class).putExtra("result",result).putExtra("consent",data));
        handler.postDelayed(this::refresh,600);
    }
    @Override protected void onResume() {
        super.onResume(); Results.permissions(this); if(state!=null) refresh();
        CaptureService capture=CaptureService.get(); if(capture!=null) capture.hostReady(this);
    }
    private void refresh() {
        if(state==null) return;
        state.setText((busy?"Running independent-window tests…\n":"")+"Pointer access: "+(PointerService.get()!=null?"enabled":"off")
                +"\nScreen sharing: "+(CaptureService.get()!=null?"active":"off")+"\n\n"+Results.text());
    }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); if(tests!=null) tests.close(); super.onDestroy(); }
}
