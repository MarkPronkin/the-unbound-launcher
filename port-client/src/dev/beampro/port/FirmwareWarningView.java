package dev.beampro.port;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Full-screen phone warning. The only primary action leaves initialization stopped. */
public final class FirmwareWarningView extends FrameLayout {
    private static final int BG=0xff111111, TEXT=0xfff5f5f7, MUTED=0xffa1a1a6,
            RED=0xffff3b4b, PANEL=0xff1d1d1d;
    private final ScrollView scroll;
    private final LinearLayout actions;
    private final TextView detected, recommended, detail;
    private final Button override;

    public FirmwareWarningView(Activity activity, FirmwarePolicy.Result result, Runnable onExit, Runnable onContinue) {
        super(activity);
        setBackgroundColor(BG);setFitsSystemWindows(true);
        View stripe=new View(activity);stripe.setBackgroundColor(RED);
        addView(stripe,new LayoutParams(-1,dp(4),Gravity.TOP));
        scroll=new ScrollView(activity);scroll.setFillViewport(false);scroll.setClipToPadding(false);
        addView(scroll,new LayoutParams(-1,-1,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(26),dp(24),dp(26),dp(12));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        TextView brand=text("THE UNBOUND LAUNCHER",10,MUTED);brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(.2f);body.addView(brand,row(0));
        TextView mark=text("!",43,RED);mark.setGravity(Gravity.CENTER);
        mark.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        mark.setBackground(surface(0xff301316,19,RED));
        LinearLayout.LayoutParams icon=new LinearLayout.LayoutParams(dp(68),dp(68));icon.topMargin=dp(29);
        body.addView(mark,icon);
        TextView eyebrow=text("INITIALIZATION PAUSED",10,RED);eyebrow.setLetterSpacing(.18f);
        body.addView(eyebrow,row(21));
        TextView title=text("Unsupported\nfirmware.",38,TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.setLetterSpacing(-.04f);
        title.setAccessibilityHeading(true);body.addView(title,row(12));
        TextView warning=text("Unbound Launcher will not work with this firmware.",16,TEXT);
        warning.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));warning.setLineSpacing(0,1.4f);
        body.addView(warning,row(21));
        TextView risk=text("Continuing may cause unpredictable behavior in your glasses.",14,MUTED);
        risk.setLineSpacing(0,1.4f);body.addView(risk,row(9));
        LinearLayout versions=new LinearLayout(activity);versions.setOrientation(LinearLayout.VERTICAL);
        versions.setPadding(dp(16),dp(16),dp(16),dp(16));versions.setBackground(surface(PANEL,14,0));
        TextView model=text(result.model,13,TEXT);versions.addView(model,row(0));
        View divider=new View(activity);divider.setBackgroundColor(0xff292929);
        LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,dp(1));line.topMargin=dp(12);line.bottomMargin=dp(12);
        versions.addView(divider,line);
        detected=version(versions,"Detected",result.detected,RED,0);
        recommended=version(versions,"Recommended",result.recommended,TEXT,12);
        body.addView(versions,row(21));
        detail=text(result.detail,12,MUTED);detail.setLineSpacing(0,1.3f);body.addView(detail,row(12));
        detail.setVisibility(unreadable(result)?VISIBLE:GONE);
        detected.setContentDescription(result.detail+" Detected: "+result.detected);
        TextView instructions=text("Install the latest stable firmware from XREAL’s website. If you’re on a beta, return to the stable release.",14,MUTED);
        instructions.setLineSpacing(0,1.4f);body.addView(instructions,row(19));
        Button updater=button("xreal.com/ota  ↗",false);updater.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        updater.setTextColor(TEXT);updater.setPadding(0,dp(9),0,dp(9));
        updater.setOnClickListener(v->{
            try {activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(FirmwarePolicy.UPDATER)));}
            catch(RuntimeException unavailable){android.widget.Toast.makeText(activity,"Open xreal.com/ota in your browser.",android.widget.Toast.LENGTH_LONG).show();}
        });body.addView(updater,row(9));
        actions=new LinearLayout(activity);actions.setOrientation(LinearLayout.VERTICAL);actions.setBackgroundColor(BG);
        actions.setPadding(dp(26),dp(8),dp(26),dp(8));
        addView(actions,new LayoutParams(-1,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
        Button exit=button("Exit",true);exit.setOnClickListener(v->onExit.run());actions.addView(exit,row(0));
        override=button("I know it won’t work; continue regardless",false);override.setTextSize(11);
        override.setPaintFlags(override.getPaintFlags()|android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        override.setOnClickListener(v->onContinue.run());actions.addView(override,row(5));
    }

    private TextView version(LinearLayout parent,String label,String value,int color,int top) {
        LinearLayout row=new LinearLayout(getContext());row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.TOP);
        TextView key=text(label,12,MUTED);row.addView(key,new LinearLayout.LayoutParams(dp(100),-2));
        TextView text=text(value,12,color);text.setGravity(Gravity.END);text.setLineSpacing(0,1.2f);
        row.addView(text,new LinearLayout.LayoutParams(0,-2,1));parent.addView(row,row(top));return text;
    }

    public void update(FirmwarePolicy.Result result,boolean checking) {
        detected.setText(result.detected);recommended.setText(result.recommended);detail.setText(result.detail);
        detail.setVisibility(unreadable(result)?VISIBLE:GONE);
        detected.setContentDescription(result.detail+" Detected: "+result.detected);
        override.setEnabled(!checking);override.setAlpha(checking?.5f:1);
        override.setText(checking?"Finishing firmware check…":"I know it won’t work; continue regardless");
    }

    public void show(Activity activity) {
        Window window=activity.getWindow();window.setBackgroundDrawable(new ColorDrawable(BG));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND|WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                |WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);window.setLayout(-1,-1);window.setWindowAnimations(0);
        window.setStatusBarColor(BG);window.setNavigationBarColor(BG);window.setNavigationBarContrastEnforced(false);
        if(Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(true);
        window.getDecorView().setSystemUiVisibility(0);activity.setFinishOnTouchOutside(false);activity.setContentView(this);
        announceForAccessibility("Initialization paused. Unsupported glasses firmware.");
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int w=MeasureSpec.getSize(widthSpec),h=MeasureSpec.getSize(heightSpec);
        int width=Math.min(w,dp(440)),usable=Math.max(1,h-getPaddingTop()-getPaddingBottom());
        ((LayoutParams)actions.getLayoutParams()).width=width;
        actions.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(usable,MeasureSpec.AT_MOST));
        LayoutParams p=(LayoutParams)scroll.getLayoutParams();p.width=width;p.topMargin=dp(4);
        p.height=Math.max(1,usable-actions.getMeasuredHeight()-dp(4));
        super.onMeasure(widthSpec,heightSpec);
    }
    private boolean unreadable(FirmwarePolicy.Result result){return result.detected.equals("Not readable")||result.detected.equals("Not verified");}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,float size,int color){
        TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);
        t.setIncludeFontPadding(false);return t;
    }
    private LinearLayout.LayoutParams row(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);return p;}
    private GradientDrawable surface(int color,int radius,int stroke){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        if(stroke!=0)d.setStroke(dp(2),stroke);return d;
    }
    private Button button(String value,boolean primary){
        Button b=new Button(getContext());b.setText(value);b.setAllCaps(false);b.setTextSize(primary?15:14);
        b.setTextColor(primary?TEXT:MUTED);b.setMinHeight(dp(primary?53:52));b.setMinimumHeight(dp(primary?53:52));
        b.setPadding(dp(12),dp(12),dp(12),dp(12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44ffffff),surface(primary?0xffc6343e:Color.TRANSPARENT,14,0),null));
        return b;
    }
}
