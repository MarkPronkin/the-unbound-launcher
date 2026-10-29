package dev.beampro.port;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;

/** Choose the app-surface route before Unity opens an app, without using its GL context. */
public final class AppSurfacePolicy {
    static final String FLAG="unbound-app-surface-fallback";
    private static boolean installed;
    private AppSurfacePolicy() {}

    public static synchronized void install(Context context) {
        if(installed)return;
        installed=true;
        final File flag=new File(context.getFilesDir(),FLAG);
        // Unknown/failed capability checks retain the stock overlay. Clear any
        // previous result before the bounded probe, including after app restore.
        write(flag,false);
        Thread probe=new Thread(()->{
            try { write(flag,probe()); }
            catch(RuntimeException failure){Log.w("UnboundColor","App surface capability unavailable",failure);}
        },"UnboundColorProbe");
        probe.setDaemon(true);probe.start();
        try { probe.join(1500); }
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }

    static boolean useCopy(String extensions) {
        return extensions!=null&&!AppColorCompat.hasExtension(extensions,"GL_EXT_texture_format_sRGB_override");
    }

    static void write(File file,boolean copy) {
        AtomicFile atomic=new AtomicFile(file);FileOutputStream stream=null;
        try {
            stream=atomic.startWrite();stream.write(copy?1:0);atomic.finishWrite(stream);
        } catch(Exception failure) {
            if(stream!=null)atomic.failWrite(stream);
            Log.w("UnboundColor","Cannot save app surface capability",failure);
        }
    }

    static boolean probe() {
        EGLDisplay display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if(display==EGL14.EGL_NO_DISPLAY)throw new IllegalStateException("No EGL display");
        int[] versions=new int[2];
        if(!EGL14.eglInitialize(display,versions,0,versions,1))throw new IllegalStateException("Cannot initialize EGL");
        EGLContext context=EGL14.EGL_NO_CONTEXT;
        EGLSurface surface=EGL14.EGL_NO_SURFACE;
        try {
            EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
            int[] attributes={EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_NONE};
            if(!EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0)||count[0]!=1)
                throw new IllegalStateException("No GLES3 pbuffer config");
            context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
            if(context==EGL14.EGL_NO_CONTEXT)throw new IllegalStateException("Cannot create capability context");
            surface=EGL14.eglCreatePbufferSurface(display,configs[0],
                new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
            if(surface==EGL14.EGL_NO_SURFACE||!EGL14.eglMakeCurrent(display,surface,surface,context))
                throw new IllegalStateException("Cannot bind capability surface");
            String extensions=GLES20.glGetString(GLES20.GL_EXTENSIONS);
            boolean copy=useCopy(extensions);
            Log.i("UnboundColor","app_surface="+(copy?"copy":"overlay")
                +" renderer="+GLES20.glGetString(GLES20.GL_RENDERER)+" extensions_read="+(extensions!=null));
            return copy;
        } finally {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface);
            if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);
            EGL14.eglTerminate(display);
            EGL14.eglReleaseThread();
        }
    }
}
