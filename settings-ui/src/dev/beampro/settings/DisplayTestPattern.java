package dev.beampro.settings;

import android.app.Activity;
import android.app.Presentation;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

/** A static per-eye grid drawn directly to the physical glasses display; no Unity or tracking. */
final class DisplayTestPattern extends Presentation {
    private final boolean stereo;
    DisplayTestPattern(Activity activity,Display display,boolean stereo) {
        super(activity,display,android.R.style.Theme_Material_NoActionBar_Fullscreen);this.stereo=stereo;
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View grid=new View(getContext()) {
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.BLACK);
                float eyeWidth=getWidth()/(stereo?2f:1f);
                for(int eye=0;eye<(stereo?2:1);eye++){
                    int save=canvas.save();canvas.translate(eye*eyeWidth,0);canvas.clipRect(0,0,eyeWidth,getHeight());
                    float h=getHeight(),cx=eyeWidth/2,cy=h/2,size=Math.min(eyeWidth,h);
                    paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(size/300);paint.setColor(0xff244239);
                    for(int i=1;i<8;i++){
                        canvas.drawLine(eyeWidth*i/8,0,eyeWidth*i/8,h,paint);
                        canvas.drawLine(0,h*i/8,eyeWidth,h*i/8,paint);
                    }
                    paint.setColor(0xffa4e8c9);paint.setStrokeWidth(size/160);
                    canvas.drawRect(size*.05f,size*.05f,eyeWidth-size*.05f,h-size*.05f,paint);
                    canvas.drawCircle(cx,cy,size*.18f,paint);
                    canvas.drawLine(cx-size*.25f,cy,cx+size*.25f,cy,paint);
                    canvas.drawLine(cx,cy-size*.25f,cx,cy+size*.25f,paint);
                    paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTextSize(size*.045f);canvas.drawText("UNBOUND DISPLAY TEST",cx,h*.17f,paint);
                    paint.setTextSize(size*.032f);canvas.drawText(stereo?"3D SBS · 60 Hz":"2D · 60 Hz",cx,h*.85f,paint);
                    canvas.restoreToCount(save);
                }
            }
        };
        grid.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(grid);
    }
}
