package dev.beampro.port;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Real external-image samples through the APK's copy fragments, on an emulator. */
public final class AppColorDeviceChecks {
    private static final int WIDTH=256, HEIGHT=9;
    private static final String VERTEX="#version 300 es\n"
        +"out vec2 textureCoord; out vec2 vs_TEXCOORD0;\n"
        +"void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));"
        +"textureCoord=p;vs_TEXCOORD0=p;gl_Position=vec4(p*2.0-1.0,0.0,1.0);}";
    private static int shader(int type,String source) {
        int id=GLES20.glCreateShader(type);
        GLES20.glShaderSource(id,source);GLES20.glCompileShader(id);
        int[] status=new int[1];GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,status,0);
        if(status[0]==0)throw new AssertionError(GLES20.glGetShaderInfoLog(id));
        return id;
    }
    private static int program(String fragment) {
        int vertex=shader(GLES20.GL_VERTEX_SHADER,VERTEX);
        int pixel=shader(GLES20.GL_FRAGMENT_SHADER,fragment);
        int id=GLES20.glCreateProgram();GLES20.glAttachShader(id,vertex);GLES20.glAttachShader(id,pixel);
        GLES20.glLinkProgram(id);
        int[] status=new int[1];GLES20.glGetProgramiv(id,GLES20.GL_LINK_STATUS,status,0);
        if(status[0]==0)throw new AssertionError(GLES20.glGetProgramInfoLog(id));
        GLES20.glDeleteShader(vertex);GLES20.glDeleteShader(pixel);
        return id;
    }
    private static void glCheck(String label) {
        int error=GLES20.glGetError();
        if(error!=GLES20.GL_NO_ERROR)throw new AssertionError(label+" GL error "+error);
    }
    private static int color(int x,int y) {
        int r=x,g=x,b=x;
        switch(y) {
            case 1:g=b=0;break;
            case 2:r=b=0;break;
            case 3:r=g=0;break;
            case 4:r=0;break;
            case 5:g=0;break;
            case 6:b=0;break;
            case 7:g=64;b=192;break;
            case 8:r=255-x;g=x;b=128;break;
        }
        return 0xff000000|r<<16|g<<8|b;
    }
    public static void main(String[] args) throws Exception {
        // New candidates expose a separate, isolated capability probe. Read it
        // from the candidate DEX rather than recompiling it into this fixture.
        try {
            Class<?> policy=Class.forName("dev.beampro.port.AppSurfacePolicy");
            java.lang.reflect.Method probe=policy.getDeclaredMethod("probe");probe.setAccessible(true);
            System.out.println("ROUTING_COPY "+probe.invoke(null));
        } catch(ClassNotFoundException olderCandidate) {}
        EGLDisplay display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version=new int[2];
        if(!EGL14.eglInitialize(display,version,0,version,1))throw new AssertionError("eglInitialize");
        EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
        int[] config={EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE};
        if(!EGL14.eglChooseConfig(display,config,0,configs,0,1,count,0)||count[0]<1)throw new AssertionError("EGL config");
        EGLContext context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
            new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
        EGLSurface pbuffer=EGL14.eglCreatePbufferSurface(display,configs[0],
            new int[]{EGL14.EGL_WIDTH,WIDTH,EGL14.EGL_HEIGHT,HEIGHT,EGL14.EGL_NONE},0);
        if(!EGL14.eglMakeCurrent(display,pbuffer,pbuffer,context))throw new AssertionError("eglMakeCurrent");
        Object owner=new Object();int external=AppColorCompat.generateExternalTexture(owner);
        // This fixture verifies the software fallback on an emulator without the
        // optional override. A real phone is never installed or changed by it.
        if(!AppColorCompat.needsSoftwareDecode(owner))throw new AssertionError("Fixture needs a GPU without hardware override");
        SurfaceTexture consumer=new SurfaceTexture(external);consumer.setDefaultBufferSize(WIDTH,HEIGHT);
        Surface producer=new Surface(consumer);
        Canvas canvas=producer.lockCanvas(null);Paint paint=new Paint();
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++) {
            paint.setColor(color(x,y));canvas.drawRect(x,y,x+1,y+1,paint);
        }
        producer.unlockCanvasAndPost(canvas);consumer.updateTexImage();
        int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);int target=ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,target);
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D,1,GLES30.GL_SRGB8_ALPHA8,WIDTH,HEIGHT);
        GLES20.glGenFramebuffers(1,ids,0);int framebuffer=ids[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,target,0);
        if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)
            throw new AssertionError("sRGB framebuffer incomplete");
        GLES20.glGetFramebufferAttachmentParameteriv(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,
            GLES30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING,ids,0);
        if(ids[0]!=GLES30.GL_SRGB)throw new AssertionError("Target is not sRGB");
        GLES20.glViewport(0,0,WIDTH,HEIGHT);GLES20.glDisable(GLES20.GL_DITHER);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(AppColorCompat.EXTERNAL,external);
        System.out.println("RENDERER "+GLES20.glGetString(GLES20.GL_RENDERER));
        System.out.println("DATASPACE "+consumer.getDataSpace());
        int checked=0;
        for(int i=0;i<args.length;i++) {
            String source=new String(Files.readAllBytes(Paths.get(args[i])),java.nio.charset.StandardCharsets.UTF_8);
            int current=program(source);GLES20.glUseProgram(current);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(current,"_MainTex"),0);
            int decode=GLES20.glGetUniformLocation(current,"_DecodeGamma");
            if(decode>=0)GLES20.glUniform1f(decode,1.0f);
            int tint=GLES20.glGetUniformLocation(current,"_Color");
            if(tint>=0)GLES20.glUniform4f(tint,1.0f,1.0f,1.0f,1.0f);
            int edge=GLES20.glGetUniformLocation(current,"_AlphaEdge");
            if(edge>=0)GLES20.glUniform1f(edge,0.01f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,3);
            ByteBuffer pixels=ByteBuffer.allocateDirect(WIDTH*HEIGHT*4);
            GLES20.glReadPixels(0,0,WIDTH,HEIGHT,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);
            glCheck(args[i]);int maxError=0;int worstX=0,worstY=0;
            // SurfaceTexture's transform may flip Y. Check the orientation
            // against either reference, then require every RGB component.
            int normal=error(pixels,false),flipped=error(pixels,true);
            boolean flip=flipped<normal;
            for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++) {
                int expected=color(x,flip?HEIGHT-1-y:y);
                for(int channel=0;channel<3;channel++) {
                    int delta=Math.abs((pixels.get((y*WIDTH+x)*4+channel)&255)-((expected>>(16-channel*8))&255));
                    if(delta>maxError){maxError=delta;worstX=x;worstY=y;}
                    checked++;
                }
            }
            System.out.println("SAMPLE "+args[i]+" max_rgb_error="+maxError+" worst="+worstX+","+worstY);
            // The last two fragments intentionally decode twice / never decode.
            if(i>=args.length-2) {
                if(maxError<60)throw new AssertionError("Invalid-decode control did not detect a transfer error");
            } else if(maxError>1)throw new AssertionError("RGB round trip differs by "+maxError);
            GLES20.glDeleteProgram(current);
        }
        GLES20.glDeleteFramebuffers(1,new int[]{framebuffer},0);
        GLES20.glDeleteTextures(2,new int[]{external,target},0);
        producer.release();consumer.release();
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display,pbuffer);EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);
        System.out.println("PASS app color RGB checks: "+checked);
    }
    private static int error(ByteBuffer pixels,boolean flip) {
        int max=0;
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++) {
            int expected=color(x,flip?HEIGHT-1-y:y);
            for(int c=0;c<3;c++)max=Math.max(max,Math.abs((pixels.get((y*WIDTH+x)*4+c)&255)-((expected>>(16-c*8))&255)));
        }
        return max;
    }
}
