package dev.beampro.port;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

/** Opaque immersive application window behind the SDK's non-focusable Presentation. */
public final class ArBackdropActivity extends Activity {
    private static final String TOKEN="unbound.arBackdropToken";
    private static final HashMap<Integer,Lease> leases=new HashMap<>();
    private static int nextToken;
    private Lease lease;

    private static final class Lease {
        final int display,token;
        final WeakReference<Activity> owner;
        WeakReference<View> root;
        WeakReference<ArBackdropActivity> backdrop=new WeakReference<>(null);
        Lease(Activity owner,View root){this.owner=new WeakReference<>(owner);this.root=new WeakReference<>(root);
            display=root.getDisplay().getDisplayId();token=++nextToken;}
    }

    static void ensure(Activity owner,View root) {
        if(owner==null||owner.isFinishing()||owner.isDestroyed()||root==null||!root.isAttachedToWindow()
                ||!DisplayStartupCompat.xreal(root.getDisplay())||!DisplayStartupCompat.stereoEdid(root.getDisplay()))return;
        int display=root.getDisplay().getDisplayId();
        Lease current=leases.get(display);
        if(current!=null&&current.owner.get()==owner){current.root=new WeakReference<>(root);return;}
        if(current!=null)release(current);
        Lease requested=new Lease(owner,root);leases.put(display,requested);
        Intent intent=new Intent().setComponent(new ComponentName(owner.getPackageName(),ArBackdropActivity.class.getName()))
                .putExtra(TOKEN,requested.token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        |Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS|Intent.FLAG_ACTIVITY_NO_ANIMATION);
        Bundle options=ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle();
        options.putInt("android.activity.windowingMode",1);
        try{owner.startActivity(intent,options);}
        catch(RuntimeException failure){leases.remove(display);Log.w("UnboundStereo","Cannot start AR backdrop",failure);}
    }

    static void releaseMissing(Activity owner,Set<Integer> live) {
        for(Lease current:new ArrayList<>(leases.values()))
            if(current.owner.get()==owner&&!live.contains(current.display))release(current);
    }

    private static void release(Lease current) {
        if(leases.get(current.display)==current)leases.remove(current.display);
        ArBackdropActivity activity=current.backdrop.get();
        if(activity!=null&&!activity.isFinishing())activity.finishAndRemoveTask();
    }

    @Override protected void onCreate(Bundle saved) {
        setTheme(android.R.style.Theme_Material_NoActionBar_Fullscreen);
        super.onCreate(saved);
        int display=getDisplay()==null?-1:getDisplay().getDisplayId();
        lease=leases.get(display);
        Activity owner=lease==null?null:lease.owner.get();
        View root=lease==null?null:lease.root.get();
        if(lease==null||lease.token!=getIntent().getIntExtra(TOKEN,0)||owner==null||owner.isFinishing()
                ||owner.isDestroyed()||root==null||!root.isAttachedToWindow()
                ||root.getDisplay()==null||root.getDisplay().getDisplayId()!=display
                ||!DisplayStartupCompat.xreal(getDisplay())||!DisplayStartupCompat.stereoEdid(getDisplay())) {
            lease=null;finishAndRemoveTask();return;
        }
        lease.backdrop=new WeakReference<>(this);
        Window window=getWindow();
        window.setBackgroundDrawableResource(android.R.color.black);
        window.setFormat(PixelFormat.OPAQUE);
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                |WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
        if(Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(false);
        View background=new View(this);background.setBackgroundColor(Color.BLACK);setContentView(background);
        immersive();
        Log.i("UnboundStereo","Immersive backdrop display="+display+"; phone/app input focus preserved");
    }

    private void immersive() {
        if(lease==null||isFinishing())return;
        View decor=getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                |View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if(Build.VERSION.SDK_INT>=30) {
            WindowInsetsController controller=decor.getWindowInsetsController();
            if(controller!=null){controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());}
        }
    }
    @Override protected void onResume(){super.onResume();immersive();}
    @Override public void onAttachedToWindow(){super.onAttachedToWindow();immersive();}
    @Override protected void onDestroy(){
        if(lease!=null&&lease.backdrop.get()==this&&leases.get(lease.display)==lease)leases.remove(lease.display);
        super.onDestroy();
    }
}
