package dev.beampro.port;

import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A drained render target that survives destruction of the hidden phone SurfaceView. */
final class BackgroundUnitySurface implements AutoCloseable {
    private static final String TAG="UnboundPresentation";
    private final Object player;
    private final Method updateDisplay;
    private final Field displayOverride;
    private final SurfaceView phone;
    private final Handler unityThread;
    private final ImageReader reader;
    private final HandlerThread worker;
    private final CountDownLatch firstFrame=new CountDownLatch(1);
    private boolean closing,released,overridden;
    private long frames;

    private static Field field(Object target,String name) throws Exception {
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try {
            Field found=type.getDeclaredField(name);found.setAccessible(true);return found;
        } catch(NoSuchFieldException absent){}
        throw new NoSuchFieldException(name);
    }
    BackgroundUnitySurface(Object player) throws Exception {
        this.player=player;
        updateDisplay=player.getClass().getDeclaredMethod("updateDisplayInternal",int.class,Surface.class);
        updateDisplay.setAccessible(true);
        displayOverride=field(player,"mMainDisplayOverride");
        if(displayOverride.getBoolean(player))throw new IllegalStateException("Unity already has a primary surface override");
        phone=(SurfaceView)field(player,"mGlView").get(player);
        Object thread=field(player,"m_MainThread").get(player);
        unityThread=(Handler)field(thread,"a").get(thread);
        if(unityThread==null)throw new IllegalStateException("Unity surface queue is not ready");
        View view=(View)player;
        int width=Math.max(1,view.getWidth()),height=Math.max(1,view.getHeight());
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT|HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        worker=new HandlerThread("unbound-background-surface");worker.start();
        try {
            reader.setOnImageAvailableListener(source->{
                synchronized(this) {
                    if(released)return;
                    try(Image image=source.acquireLatestImage()) {
                        if(image!=null) {
                            firstFrame.countDown();
                            if(++frames==1 || frames%300==0)Log.i(TAG,"Background Unity surface frames="+frames);
                        }
                    } catch(RuntimeException failure){Log.w(TAG,"Cannot drain background Unity surface",failure);}
                }
            },new Handler(worker.getLooper()));
            // displayChanged() removes mGlView BEFORE queuing the native handoff. Under load
            // that abandons Unity's current EGL window and can prevent the handoff from running.
            // Keep the view attached, suppress its callbacks, and fence the native queue first.
            displayOverride.setBoolean(player,true);overridden=true;
            if(!Boolean.TRUE.equals(updateDisplay.invoke(player,0,reader.getSurface())))
                throw new IllegalStateException("Unity did not accept its background surface");
            CountDownLatch adopted=new CountDownLatch(1);
            if(!unityThread.post(adopted::countDown)||!adopted.await(3,TimeUnit.SECONDS))
                throw new IllegalStateException("Unity did not acknowledge its background surface");
            if(!firstFrame.await(3,TimeUnit.SECONDS))
                throw new IllegalStateException("Unity did not draw to its background surface");
            Log.i(TAG,"Unity background surface adopted before phone surface loss: "+width+"x"+height);
        } catch(Exception failure) {
            close();throw failure;
        }
    }

    @Override public void close() {
        synchronized(this){if(closing)return;closing=true;}
        try {
            if(overridden) {
                displayOverride.setBoolean(player,false);overridden=false;
                Surface original=phone.getHolder().getSurface();
                updateDisplay.invoke(player,0,original!=null&&original.isValid()?original:null);
            }
        } catch(Exception failure){Log.w(TAG,"Cannot restore Unity phone surface",failure);}
        // Continue draining until Unity has detached the producer. Stopping the consumer
        // before this fence could fill its queue and deadlock the very detach we await.
        if(!unityThread.post(this::release))release();
    }
    private void release() {
        synchronized(this){if(released)return;released=true;reader.close();}
        worker.quitSafely();
        Log.i(TAG,"Unity phone surface restored; background frames="+frames);
    }
}
