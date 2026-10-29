package com.unity3d.player;

import android.content.Context;
import android.opengl.*;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Controlled native boundary: real GLES window buffers, with Unity's surface override semantics.
 *  The production Unity library and XR renderer still require a separate physical-device check. */
public class UnityPlayer extends FrameLayout implements SurfaceHolder.Callback {
    public int resumes,pauses;
    public boolean focused;
    private boolean mMainDisplayOverride;
    public volatile long frames;
    public volatile int phoneDestroys;
    private final SurfaceView mGlView;
    private static final class EngineQueue {Handler a;}
    private final EngineQueue m_MainThread=new EngineQueue();
    private final HandlerThread worker=new HandlerThread("fixture-unity-render");
    private final Handler render;
    private EGLDisplay display;
    private EGLContext context;
    private EGLConfig config;
    private EGLSurface output;
    private boolean running;
    public UnityPlayer(Context context) {
        super(context);worker.start();render=new Handler(worker.getLooper());
        m_MainThread.a=render;
        mGlView=new SurfaceView(context);mGlView.getHolder().addCallback(this);addView(mGlView);
    }
    private final Runnable draw=new Runnable(){public void run(){
        if(output==null||!running)return;
        GLES20.glClearColor((frames%255)/255f,0.25f,0.5f,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        // Like production Unity, surface loss stops new frames until another target arrives.
        if(!EGL14.eglSwapBuffers(display,output))return;
        frames++;render.postDelayed(this,16);
    }};
    private void target(Surface surface) {
        render.removeCallbacks(draw);
        if(display==null) {
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] version=new int[2];
            if(!EGL14.eglInitialize(display,version,0,version,1))throw new AssertionError("EGL init failed");
            EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
            EGL14.eglChooseConfig(display,new int[]{EGL14.EGL_RENDERABLE_TYPE,4,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE},0,configs,0,1,count,0);
            config=configs[0];context=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        }
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
        if(output!=null){EGL14.eglDestroySurface(display,output);output=null;}
        if(surface!=null) {
            output=EGL14.eglCreateWindowSurface(display,config,surface,new int[]{EGL14.EGL_NONE},0);
            if(output==EGL14.EGL_NO_SURFACE||!EGL14.eglMakeCurrent(display,output,output,context))throw new AssertionError("Fixture target failed");
            draw.run();
        }
    }
    private void setSurface(Surface surface) {
        CountDownLatch done=new CountDownLatch(1);
        render.post(()->{try{target(surface);}finally{done.countDown();}});
        try{if(!done.await(3,TimeUnit.SECONDS))throw new AssertionError("Fixture detach timeout");}
        catch(InterruptedException e){throw new AssertionError(e);}
    }
    // A busy graphics queue makes the real public API's remove-before-adopt race observable.
    private boolean updateDisplayInternal(int id,Surface surface) {
        render.post(()->{SystemClock.sleep(120);target(surface);});return true;
    }
    public boolean hasOverride(){return mMainDisplayOverride;}
    public boolean displayChanged(int id,Surface surface) {
        if(id!=0)throw new AssertionError("Only the primary Unity surface may change");
        mMainDisplayOverride=surface!=null;
        if(mMainDisplayOverride)removeView(mGlView);else if(mGlView.getParent()==null)addView(mGlView);
        setSurface(surface);return true;
    }
    public void surfaceCreated(SurfaceHolder holder){if(!mMainDisplayOverride)setSurface(holder.getSurface());}
    public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){if(!mMainDisplayOverride)setSurface(holder.getSurface());}
    public void surfaceDestroyed(SurfaceHolder holder){phoneDestroys++;if(!mMainDisplayOverride)setSurface(null);}
    public void resume(){resumes++;render.post(()->{running=true;render.removeCallbacks(draw);draw.run();});}
    public void pause(){pauses++;render.post(()->{running=false;render.removeCallbacks(draw);});}
    public void windowFocusChanged(boolean value){focused=value;}
    public void destroy(){setSurface(null);render.post(()->{if(display!=null){EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);}worker.quitSafely();});}
    public static void UnitySendMessage(String a,String b,String c){}
}
