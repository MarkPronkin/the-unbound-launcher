package dev.beampro.eglchecks;

import android.app.Instrumentation;
import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Bundle;
import android.view.Surface;

/** The SDK's actual colorspace getter, then real Android EGL window rendering. */
public final class EglColorspaceDeviceChecks extends Instrumentation {
    String variant;
    @Override public void onCreate(Bundle args) {super.onCreate(args);variant=args.getString("variant");start();}
    static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
    @Override public void onStart() {
        Bundle result = new Bundle();
        SurfaceTexture texture = null;
        Surface window = null;
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        StringBuilder evidence = new StringBuilder();
        try {
            System.loadLibrary("egl_"+variant);
            int token = com.xreal.sdk.display.GlassesDisplay.nativeRunOnGlassesDisplay();
            check(token == (variant.equals("original") ? 0x3087 : 0x309D), "SDK getter token");
            evidence.append("SDK token=0x").append(Integer.toHexString(token)).append('\n');
            texture = new SurfaceTexture(false);
            texture.setDefaultBufferSize(64,64);
            window = new Surface(texture);
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            check(EGL14.eglInitialize(display,new int[2],0,new int[2],0),"EGL initialize");
            String extensions = EGL14.eglQueryString(display,EGL14.EGL_EXTENSIONS);
            android.util.Log.i("EglColorspaceCheck", "EGL="+EGL14.eglQueryString(display,EGL14.EGL_VERSION)+" extensions="+extensions);
            check(extensions.contains("EGL_KHR_gl_colorspace"),"GL colorspace supported");
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            check(EGL14.eglChooseConfig(display,new int[]{EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
                    EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE},0,configs,0,1,count,0)
                    && count[0]>0,"GLES3 window config");
            context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
            check(context!=EGL14.EGL_NO_CONTEXT,"GLES3 context");
            for (int colorspace : new int[]{0x308A,0x3089}) {
                surface = EGL14.eglCreateWindowSurface(display,configs[0],window,
                        new int[]{0x3086,0x3084,token,colorspace,EGL14.EGL_NONE},0);
                int error = EGL14.eglGetError();
                evidence.append("colorspace=0x").append(Integer.toHexString(colorspace))
                        .append(" creation error=0x").append(Integer.toHexString(error)).append('\n');
                if(variant.equals("original")) {
                    check(surface==EGL14.EGL_NO_SURFACE && error==0x3004,
                            "Original OpenVG attribute must reproduce EGL_BAD_ATTRIBUTE; got "+error);
                    continue;
                }
                check(surface!=EGL14.EGL_NO_SURFACE && error==EGL14.EGL_SUCCESS,"Patched surface, error="+error);
                check(EGL14.eglMakeCurrent(display,surface,surface,context),"Make window current");
                evidence.append("GPU=").append(GLES30.glGetString(GLES30.GL_RENDERER)).append('\n');
                int[] actual = new int[1];
                check(EGL14.eglQuerySurface(display,surface,token,actual,0) && actual[0]==colorspace,"Requested colorspace retained");
                GLES30.glClearColor(0.25f,0.5f,0.75f,1); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                java.nio.ByteBuffer pixel = java.nio.ByteBuffer.allocateDirect(4);
                GLES30.glReadPixels(0,0,1,1,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,pixel);
                check(GLES30.glGetError()==GLES30.GL_NO_ERROR,"Render and read real window pixel");
                int green = pixel.get(1)&255;
                check(Math.abs(green-(colorspace==0x3089?188:128))<=2,"Framebuffer color encoding, green="+green);
                evidence.append("green=").append(green).append('\n');
                check(EGL14.eglSwapBuffers(display,surface),"Present rendered window");
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(display,surface); surface=EGL14.EGL_NO_SURFACE;
            }
            result.putString("stream",evidence+"PASS "+variant+" actual SDK getter and Android EGL window: linear and sRGB\n");
            finish(-1,result);
        } catch(Throwable error) {
            result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);
        } finally {
            if(display!=EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
                if(surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface);
                if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);
                EGL14.eglTerminate(display);
            }
            if(window!=null)window.release();
            if(texture!=null)texture.release();
        }
    }
}
